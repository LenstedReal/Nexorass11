package com.lenstedreal.nexorawatch.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.datasource.DefaultHttpDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class NexoraPlayerState(
    val isPlaying: Boolean = false,
    val positionSeconds: Double = 0.0,
    val durationSeconds: Double = 0.0,
    val isBuffering: Boolean = false,
    val hasError: Boolean = false,
    val errorMessage: String? = null
)

@OptIn(UnstableApi::class)
class NexoraPlayer(
    private val context: Context,
    private val baseUrl: String = "https://nexorawatch-beta.vercel.app"
) {
    val exoPlayer: ExoPlayer = ExoPlayer.Builder(context).build()
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var progressJob: Job? = null

    private val _state = MutableStateFlow(NexoraPlayerState())
    val state: StateFlow<NexoraPlayerState> = _state.asStateFlow()

    private var currentLoadedUrl: String? = null

    var onUserSeek: ((Double) -> Unit)? = null
    var onUserPlayPause: ((Boolean) -> Unit)? = null

    init {
        exoPlayer.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updateState()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                updateState()
            }

            override fun onPlayerError(error: PlaybackException) {
                _state.value = _state.value.copy(
                    hasError = true,
                    errorMessage = error.localizedMessage ?: "Oynatma hatası"
                )
            }
        })

        startProgressLoop()
    }

    private fun startProgressLoop() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive) {
                updateState()
                delay(250)
            }
        }
    }

    private fun updateState() {
        val pos = (exoPlayer.currentPosition.coerceAtLeast(0L) / 1000.0)
        val dur = (exoPlayer.duration.coerceAtLeast(0L) / 1000.0)
        _state.value = _state.value.copy(
            isPlaying = exoPlayer.isPlaying,
            positionSeconds = pos,
            durationSeconds = dur,
            isBuffering = exoPlayer.playbackState == Player.STATE_BUFFERING,
            hasError = false
        )
    }

    fun resolveAbsoluteUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://") || trimmed.startsWith("content://") || trimmed.startsWith("file://")) {
            return trimmed
        }
        val cleanBase = baseUrl.trimEnd('/')
        val cleanPath = if (trimmed.startsWith('/')) trimmed else "/$trimmed"
        return "$cleanBase$cleanPath"
    }

    fun setSource(rawUrl: String) {
        val absoluteUrl = resolveAbsoluteUrl(rawUrl)
        if (currentLoadedUrl == absoluteUrl) return
        currentLoadedUrl = absoluteUrl

        val isHls = absoluteUrl.contains(".m3u8", ignoreCase = true)

        val mediaItemBuilder = MediaItem.Builder().setUri(absoluteUrl)
        if (isHls) {
            mediaItemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
        }

        val mediaItem = mediaItemBuilder.build()
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
    }

    fun play() {
        exoPlayer.play()
        updateState()
    }

    fun pause() {
        exoPlayer.pause()
        updateState()
    }

    fun seekTo(seconds: Double) {
        val ms = (seconds.coerceAtLeast(0.0) * 1000).toLong()
        exoPlayer.seekTo(ms)
        updateState()
    }

    val currentPositionSeconds: Double
        get() = exoPlayer.currentPosition.coerceAtLeast(0L) / 1000.0

    val isPlaying: Boolean
        get() = exoPlayer.isPlaying

    fun release() {
        progressJob?.cancel()
        exoPlayer.release()
    }
}
