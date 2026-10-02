package com.lenstedreal.nexorawatch.player

import com.lenstedreal.nexorawatch.data.Playback
import kotlin.math.max

object PlaybackSync {
    const val DRIFT_TOLERANCE_SECONDS = 1.8
    const val HEARTBEAT_INTERVAL_MS = 1200L

    fun calculateExpectedPosition(playback: Playback, serverOffset: Long): Double {
        if (!playback.playing) return max(0.0, playback.position)
        val serverNow = System.currentTimeMillis() + serverOffset
        val elapsedSeconds = max(0.0, (serverNow - playback.updatedAt) / 1000.0)
        return max(0.0, playback.position + elapsedSeconds)
    }

    fun isDriftExceeded(currentSeconds: Double, expectedSeconds: Double): Boolean {
        return Math.abs(currentSeconds - expectedSeconds) > DRIFT_TOLERANCE_SECONDS
    }
}
