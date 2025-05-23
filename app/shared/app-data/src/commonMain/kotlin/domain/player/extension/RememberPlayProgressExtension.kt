/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.player.extension

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import me.him188.ani.app.data.repository.player.EpisodePlayHistoryRepository
import me.him188.ani.app.domain.episode.EpisodeSession
import me.him188.ani.app.domain.episode.UnsafeEpisodeSessionApi
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import org.koin.core.Koin
import org.openani.mediamp.PlaybackState

/**
 * 记忆播放进度.
 *
 * 在以下情况时保存播放进度:
 * - 切换数据源
 * - 暂停
 * - 播放完成
 */
class RememberPlayProgressExtension(
    private val context: PlayerExtensionContext,
    koin: Koin,
) : PlayerExtension(name = "SaveProgressExtension") {
    private val playProgressRepository: EpisodePlayHistoryRepository by koin.inject()

    override fun onStart(episodeSession: EpisodeSession, backgroundTaskScope: ExtensionBackgroundTaskScope) {
        backgroundTaskScope.launch("MediaSelectorListener") {
            context.sessionFlow.collectLatest { session ->
                session.fetchSelectFlow.collectLatest inner@{ fetchSelect ->
                    if (fetchSelect == null) return@inner

                    fetchSelect.mediaSelector.events.onBeforeSelect.collect {
                        // 切换 数据源 前保存播放进度
                        savePlayProgressOrRemove(session.episodeId)
                    }
                }

            }
        }

        backgroundTaskScope.launch("PlaybackStateListener") {
            val player = context.player
            player.playbackState.collect { playbackState ->
                when (playbackState) {
                    // 加载播放进度
                    PlaybackState.READY -> {
                        val positionMillis = playProgressRepository.getPositionMillisByEpisodeId(episodeSession.episodeId)
                        if (positionMillis == null) {
                            logger.info { "Did not find saved position" }
                        } else {
                            logger.info { "Loaded saved position: $positionMillis, waiting for video properties" }
                            player.mediaProperties.filter { it != null && it.durationMillis > 0L }.firstOrNull()
                            logger.info { "Loaded saved position: $positionMillis, video properties ready, seeking" }
                            withContext(Dispatchers.Main) { // android must call in main thread
                                player.seekTo(positionMillis)
                            }
                        }
                    }

                    PlaybackState.PAUSED -> {
                        savePlayProgressOrRemove(episodeSession.episodeId)
                    }

                    PlaybackState.FINISHED -> {
                        savePlayProgressOrRemove(episodeSession.episodeId)
                    }

                    else -> Unit
                }
            }

        }
    }

    @OptIn(UnsafeEpisodeSessionApi::class)
    override suspend fun onBeforeSwitchEpisode(newEpisodeId: Int) {
        savePlayProgressOrRemove(context.getCurrentEpisodeId())
    }

    @OptIn(UnsafeEpisodeSessionApi::class)
    override suspend fun onClose() {
        savePlayProgressOrRemove(context.getCurrentEpisodeId())
    }

    private suspend fun savePlayProgressOrRemove(
        episodeId: Int
    ) {
        val player = context.player
        val playbackState = player.playbackState.value
        val videoDurationMillis = player.mediaProperties.value?.durationMillis

        if (videoDurationMillis == null || videoDurationMillis <= 0L) {
            return
        }

        when (playbackState) {
            PlaybackState.READY,
            PlaybackState.ERROR -> return

            PlaybackState.FINISHED,
            PlaybackState.PAUSED,
            PlaybackState.PLAYING,
            PlaybackState.PAUSED_BUFFERING -> {
                val currentPositionMillis = withContext(Dispatchers.Main.immediate) {
                    try {
                        player.getCurrentPositionMillis()
                    } catch (e: Error) {
                        // Caused by: java.lang.Error: Invalid memory access
                        // https://github.com/open-ani/animeko/issues/1787
                        0L
                    }
                }

                if (currentPositionMillis <= 0L) {
                    return
                }

                if (videoDurationMillis - currentPositionMillis < 5000 || currentPositionMillis > videoDurationMillis) {
                    playProgressRepository.remove(episodeId)
                } else {
                    playProgressRepository.saveOrUpdate(episodeId, currentPositionMillis)
                }
                return
            }
        }
    }

    companion object : EpisodePlayerExtensionFactory<RememberPlayProgressExtension> {
        override fun create(context: PlayerExtensionContext, koin: Koin): RememberPlayProgressExtension =
            RememberPlayProgressExtension(context, koin)

        private val logger = logger<RememberPlayProgressExtension>()
    }
}