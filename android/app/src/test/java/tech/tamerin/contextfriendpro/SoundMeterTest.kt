package tech.tamerin.contextfriendpro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class SoundMeterTest {
    /** 100 ms of a 200 Hz tone at [amplitude] (0..1 of full scale), plus a little hiss so silence is not digital zero. */
    private fun block(amplitude: Double, seed: Int = 0): ShortArray {
        val random = java.util.Random(seed.toLong())
        return ShortArray(1600) { i -> (32767 * (amplitude * sin(2 * PI * 200 * i / 16000) + 0.0003 * random.nextGaussian())).toInt().toShort() }
    }

    @Test fun quietRoomIsNotSound() {
        val meter = SoundMeter()
        repeat(30) { assertFalse(meter.add(block(0.0, it))!!.sound) }
        assertEquals(0L, meter.heardMs)
    }

    @Test fun aDistantVoiceCountsAsSoundAndMovesTheBars() {
        val meter = SoundMeter()
        repeat(10) { meter.add(block(0.0, it)) }
        val voice = meter.add(block(0.01))!!   // peaks at 1 % of full scale: a speakerphone across the desk
        assertTrue(voice.sound)
        assertTrue("level ${voice.level}", voice.level > 0.15f)
        assertTrue(voice.level < LOUD_LEVEL)   // the screen asks to move the phone closer
        assertEquals(100L, meter.heardMs)
    }

    @Test fun aNormalVoiceIsLoud() {
        val block = SoundMeter().add(block(0.1))!!
        assertTrue(block.sound)
        assertTrue(block.level > LOUD_LEVEL)
    }

    @Test fun blocksFinishEveryHundredMilliseconds() {
        val meter = SoundMeter()
        val part = ShortArray(320)
        repeat(4) { assertNull(meter.add(part)) }
        assertTrue(meter.add(part) != null)
    }
}
