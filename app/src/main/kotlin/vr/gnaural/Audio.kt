// Copyright (C) 2026 Elisabetta Nova. GPL-2.0-or-later, see LICENSE.
package vr.gnaural

import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import kotlin.concurrent.thread

/** Streams an [Engine] to the headphones on its own thread, fading in and out over [FADE_NS] to avoid clicks. */
class Audio {
    @Volatile private var stopAtNs = Long.MAX_VALUE
    private var worker: Thread? = null

    /** The moment the session's first frame reached the headphones, from the track's own timestamps. */
    @Volatile var frame0Ns = Long.MAX_VALUE
        private set

    /** The engine frame playing at [nowNs], or -1 before the first one is heard. */
    fun heardFrame(nowNs: Long): Long =
        if (nowNs < frame0Ns) -1 else (nowNs - frame0Ns) * SAMPLE_RATE / 1_000_000_000L

    fun start(engine: Engine) {
        worker?.join() // a previous session may still be fading out; its frame0Ns must not leak into this one
        stopAtNs = Long.MAX_VALUE
        frame0Ns = Long.MAX_VALUE
        worker = thread(name = "gnaural-audio") {
            val track = AudioTrack.Builder()
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            val buf = ShortArray(SAMPLE_RATE / 50 * 2) // 20 ms of stereo; write() blocks, which paces the loop
            val ts = AudioTimestamp()
            val startNs = System.nanoTime()
            track.play()
            while (true) {
                val now = System.nanoTime()
                if (now >= stopAtNs) break
                val gain = minOf(now - startNs, stopAtNs - now, FADE_NS).toFloat() / FADE_NS
                track.setVolume(gain)
                engine.fill(buf)
                track.write(buf, 0, buf.size)
                // The flash follows what is heard, not what is written: the gap is the output latency.
                if (track.getTimestamp(ts)) frame0Ns = ts.nanoTime - ts.framePosition * 1_000_000_000L / SAMPLE_RATE
            }
            track.stop()
            track.release()
        }
    }

    fun stop() {
        stopAtNs = minOf(stopAtNs, System.nanoTime() + FADE_NS)
    }

    companion object {
        const val SAMPLE_RATE = 48_000
        private const val FADE_NS = 1_000_000_000L
    }
}
