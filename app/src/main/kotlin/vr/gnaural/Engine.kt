/*
 * Engine.kt, a Kotlin translation of the ISOPULSE voice of Gnaural's
 * BinauralBeatSoundEngine.java (kept verbatim in docs/upstream/).
 * Copyright (C) 2008 Bret Logan (Gnaural); translation and changes Copyright (C) 2026 Elisabetta Nova.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation; either version 2 of the
 * License, or (at your option) any later version. See LICENSE.
 *
 * Removed from the original: the BINAURALBEAT, PINKNOISE, PCM, WATERDROPS and RAIN voices,
 * multi-entry schedules and their interpolation, loops, mono, stereo swap.
 * Changed: the pulse is on for 30% of each period instead of 50%, the same window as the flash; the
 * period is a whole number of samples and a new beat takes effect at the next period boundary, so the
 * flash can follow the tone exactly (see [Rhythm]). ISOPULSE_ALT is kept as Gnaural has it, a gapless
 * hand-over from ear to ear, but one ear per period rather than per half period, so the beat rate stays
 * the pulse rate. With one voice the mix peaks at 0x3FFF, so it needs no clamp (Gnaural's TODO).
 */
package vr.gnaural

import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The pulse timing from [startFrame] on. The flash looks up the rhythm of the frame being heard, which
 * the audio buffer puts behind the frame being written; [previous] covers that gap after a change.
 * When [alternate] is set the tone fills every period ([onSamples] == [periodSamples]) and changes ear
 * each period, starting with the left ear if [leftFirst].
 */
class Rhythm(
    val startFrame: Long,
    val periodSamples: Int,
    val onSamples: Int,
    val alternate: Boolean,
    val leftFirst: Boolean,
    val previous: Rhythm?,
) {
    /** The rhythm in force at [frame], or null before the session's first frame. */
    fun at(frame: Long): Rhythm? = if (frame >= startFrame) this else previous?.at(frame)

    fun isOn(frame: Long) = (frame - startFrame) % periodSamples < onSamples

    fun leftEar(frame: Long) = leftFirst != ((frame - startFrame) / periodSamples % 2 == 1L)
}

/** An isochronic pulse as interleaved 16-bit stereo. One instance per session. */
class Engine(private val sampleRate: Int, @Volatile var baseHz: Double, @Volatile var beatHz: Double) {
    /** Hands the tone from ear to ear each period with no gap, from the next period on. */
    @Volatile var alternate = false

    /**
     * Plays Gnaural's binaural beat instead of the pulse, from the next period on: a steady tone in each
     * ear, base + beat/2 left and base - beat/2 right. [alternate] has no meaning then and is ignored.
     */
    @Volatile var binaural = false

    @Volatile var rhythm = rhythmFor(0, leftFirst = true, previous = null)
        private set

    private var frame = 0L
    private var leftTurn = false
    private var crossfade = false
    private var binauralNow = false
    private var sinPos = 0.0
    private var sinPosR = 0.0
    private var envelope = 0.0

    /** Fills [buf] with interleaved left/right frames. Called from the audio thread only. */
    fun fill(buf: ShortArray) {
        val inc = baseHz * TWO_PI / sampleRate
        var r = rhythm
        for (i in buf.indices step 2) {
            sinPos += inc
            if (sinPos >= TWO_PI) sinPos -= TWO_PI

            val inPeriod = ((frame - r.startFrame) % r.periodSamples).toInt()
            if (inPeriod == 0) {
                // ponytail: switching sound mid-session clicks once at this boundary; a crossfade if it matters.
                if (binaural && !binauralNow) sinPosR = sinPos - inc // this frame's step is added below
                binauralNow = binaural
                leftTurn = !leftTurn
                crossfade = r.alternate // the ear just finishing fades out rather than stopping dead
                val next = rhythmFor(frame, leftTurn, r)
                if (next.periodSamples != r.periodSamples || next.alternate != r.alternate) {
                    r = next
                    rhythm = next
                }
            }
            // Gnaural crossfades each edge over 100 samples "to reduce harmonics" (a click per pulse otherwise).
            // ponytail: switching alternation off mid-session still clicks once, on that period's first sample.
            if (inPeriod == 0 || inPeriod == r.onSamples) envelope = 0.0
            val gainL: Double
            val gainR: Double
            if (r.alternate) {
                // ISOPULSE_ALT: one ear fades in as the other fades out, so there is never a gap.
                val fadeOut = if (crossfade) 1 - envelope else 0.0
                val left = r.leftEar(frame)
                gainL = if (left) envelope else fadeOut
                gainR = if (left) fadeOut else envelope
            } else {
                gainL = if (inPeriod < r.onSamples) envelope else 1 - envelope
                gainR = gainL
            }
            envelope = minOf(1.0, envelope + 0.01)
            frame++

            if (binauralNow) {
                // The beat is rate / period, not the slider value, so the two tones come back into phase
                // exactly on each period boundary, where the flash starts.
                val beat = sampleRate.toDouble() / r.periodSamples
                sinPos += (beat / 2) * TWO_PI / sampleRate
                sinPosR += inc - (beat / 2) * TWO_PI / sampleRate
                if (sinPosR >= TWO_PI) sinPosR -= TWO_PI
                buf[i] = (sin(sinPos) * SIN_SCALER).toInt().toShort()
                buf[i + 1] = (sin(sinPosR) * SIN_SCALER).toInt().toShort()
                continue
            }
            val tone = sin(sinPos) * SIN_SCALER
            buf[i] = (tone * gainL).toInt().toShort()
            buf[i + 1] = (tone * gainR).toInt().toShort()
        }
    }

    private fun rhythmFor(startFrame: Long, leftFirst: Boolean, previous: Rhythm?): Rhythm {
        val period = (sampleRate / beatHz).roundToInt()
        val alt = alternate && !binaural
        // ponytail: one step of history covers one change per audio buffer, the thumbstick's pace.
        val history = previous?.let { Rhythm(it.startFrame, it.periodSamples, it.onSamples, it.alternate, it.leftFirst, null) }
        return Rhythm(startFrame, period, if (alt) period else (period * DUTY).roundToInt(), alt, leftFirst, history)
    }

    private companion object {
        const val TWO_PI = PI * 2
        const val SIN_SCALER = 0x3FFF
        // 30% on: the duty cycle of the flicker study this app follows (Amaya et al. 2023, PLOS ONE).
        const val DUTY = 0.3
    }
}
