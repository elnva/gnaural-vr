package vr.gnaural

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class EngineTest {
    private val rate = 48_000

    @Test
    fun pulseIsOnForTheFirstThirtyPercentOfEachPeriod() {
        val engine = Engine(rate, baseHz = 200.0, beatHz = 10.0)
        val buf = ShortArray(rate * 2) // one second of stereo
        engine.fill(buf)

        val left = channel(buf, LEFT)
        val rhythm = engine.rhythm
        assertEquals(4800, rhythm.periodSamples)
        assertEquals(1440, rhythm.onSamples)
        assertEquals(left, channel(buf, RIGHT)) // the same tone in both ears

        for (p in 0 until 10) {
            val start = p * 4800
            assertTrue(meanLevel(left.subList(start + 100, start + 1440)) > 5000) // after the 100-sample fade-in
            assertEquals(0.0, meanLevel(left.subList(start + 1540, start + 4800)), 0.0) // silent after the fade-out
            assertTrue(rhythm.isOn(start + 700L))
            assertTrue(!rhythm.isOn(start + 3000L))
        }
    }

    @Test
    fun newBeatStartsAtTheNextPeriodAndTheOldOneStillAnswersForEarlierFrames() {
        val engine = Engine(rate, baseHz = 200.0, beatHz = 10.0)
        engine.fill(ShortArray(rate * 2 + 200)) // one second and 100 frames into the next period
        engine.beatHz = 5.0
        engine.fill(ShortArray(rate * 2))

        val rhythm = engine.rhythm
        assertEquals(48_000L + 4800, rhythm.startFrame)
        assertEquals(9600, rhythm.periodSamples)
        val heard = 48_000L + 100 // still the 10 Hz pulse, 100 frames in
        assertEquals(4800, rhythm.at(heard)!!.periodSamples)
        assertTrue(rhythm.at(heard)!!.isOn(heard))
        assertTrue(rhythm.isOn(48_000L + 4800 + 2000)) // 5 Hz: on for the first 2880 frames
        assertTrue(!rhythm.isOn(48_000L + 4800 + 3000))
        assertEquals(null, rhythm.at(-1))
    }

    @Test
    fun alternatingHandsTheToneFromEarToEarWithNoGap() {
        val engine = Engine(rate, baseHz = 200.0, beatHz = 10.0).apply { alternate = true }
        val buf = ShortArray(9600 * 2) // two periods
        engine.fill(buf)
        val left = channel(buf, LEFT)
        val right = channel(buf, RIGHT)

        // First period: the whole of it in the left ear, nothing in the right (nothing to fade out yet).
        assertTrue(meanLevel(left.subList(100, 4800)) > 5000)
        assertEquals(0.0, meanLevel(right.subList(0, 4800)), 0.0)
        // Second period: the right ear takes over at once while the left fades out over 100 samples.
        assertTrue(meanLevel(right.subList(4900, 9600)) > 5000)
        assertTrue(meanLevel(left.subList(4800, 4850)) > 0)
        assertEquals(0.0, meanLevel(left.subList(4900, 9600)), 0.0)

        val rhythm = engine.rhythm
        assertTrue(rhythm.alternate && rhythm.isOn(4000) && rhythm.isOn(9000)) // the flash never goes dark
        assertTrue(rhythm.leftEar(100) && !rhythm.leftEar(4900))
    }

    @Test
    fun binauralPlaysBasePlusAndMinusHalfTheBeatInPhaseAtEachPeriod() {
        val engine = Engine(rate, baseHz = 200.0, beatHz = 10.0).apply { binaural = true }
        val buf = ShortArray(rate * 2) // one second
        engine.fill(buf)
        val left = channel(buf, LEFT)
        val right = channel(buf, RIGHT)

        assertEquals(205.0, risingZeroCrossings(left).toDouble(), 1.0)
        assertEquals(195.0, risingZeroCrossings(right).toDouble(), 1.0)
        // Back in phase where each period, and so each flash, starts: the two ears agree there.
        for (p in 1 until 10) assertEquals(left[p * 4800].toDouble(), right[p * 4800].toDouble(), 50.0)
        assertTrue(abs(left[2400] - right[2400]) > 10_000) // and opposite in phase half way
    }

    private fun risingZeroCrossings(s: List<Short>) = (1 until s.size).count { s[it - 1] < 0 && s[it] >= 0 }

    private fun channel(buf: ShortArray, c: Int) = buf.filterIndexed { i, _ -> i % 2 == c }

    private fun meanLevel(samples: List<Short>) = samples.sumOf { abs(it.toDouble()) } / samples.size

    private companion object {
        const val LEFT = 0
        const val RIGHT = 1
    }
}
