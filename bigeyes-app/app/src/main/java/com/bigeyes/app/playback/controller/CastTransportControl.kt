package com.bigeyes.app.playback.controller

/**
 * Transport commands forwarded by [PlaybackController] whenever BigEyesTV direct cast is
 * NOT the active target, i.e. while a local-proxy DLNA cast is driving the renderer.
 *
 * Without this sink the controller's pause/resume/seek/stop methods only mutated local
 * session state, so every transport button silently did nothing on a DLNA television.
 */
interface CastTransportControl {
    fun pause()

    fun resume()

    fun seekTo(positionMs: Long)

    fun stop()
}
