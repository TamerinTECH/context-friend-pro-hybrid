package tech.tamerin.contextfriendpro

import kotlin.math.log10
import kotlin.math.sqrt

/** A voice at a normal distance: -42 dBFS RMS, 0.3 on the meter's scale. Below it the screen asks to move closer. */
const val LOUD_LEVEL = 0.3f

/**
 * The microphone level for the recording screen, from 16 kHz PCM. Plain Kotlin, see SoundMeterTest.
 *
 * Each 100 ms block becomes a loudness in dBFS. The recorder uses the unprocessed voice-recognition microphone (no
 * automatic gain), so a voice a few metres away peaks near 1 % of full scale: on a linear scale it looks like silence,
 * on a decibel scale it moves the bars. The noise floor follows the room down at once and up slowly (0.5 dB/s); a block
 * counts as sound when it rises clearly above that floor, or is loud on its own.
 */
class SoundMeter(private val sampleRate: Int = 16_000, private val blockMs: Int = 100) {
    data class Block(val level: Float, val db: Float, val sound: Boolean)

    private val blockSamples = sampleRate * blockMs / 1000
    private var sum = 0.0
    private var count = 0
    private var floor = Float.NaN

    /** Milliseconds of audio that counted as sound since the start. */
    var heardMs = 0L
        private set

    /** Adds [n] samples; returns the last block finished by them, or null while the block is still filling. */
    fun add(samples: ShortArray, n: Int = samples.size): Block? {
        var finished: Block? = null
        for (i in 0 until n) {
            val v = samples[i].toDouble(); sum += v * v; count++
            if (count == blockSamples) finished = finish()
        }
        return finished
    }

    private fun finish(): Block {
        val rms = sqrt(sum / count) / 32768.0
        sum = 0.0; count = 0
        val db = (20 * log10(rms.coerceAtLeast(1e-5))).toFloat()
        floor = if (floor.isNaN() || db < floor) db else floor + 0.05f * blockMs / 100f
        val sound = db > -42f || db > maxOf(floor + 10f, -60f)
        if (sound) heardMs += blockMs
        return Block(((db + 60f) / 60f).coerceIn(0f, 1f), db, sound)
    }
}
