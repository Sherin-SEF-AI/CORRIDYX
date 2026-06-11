package ai.deepmost.corridyx.segment

import kotlin.math.max
import kotlin.math.min

/** Single-pass running mean/variance (Welford) with min/max. Single-writer (actor) — no locks. */
class Welford {
    var n: Long = 0L; private set
    private var mean = 0.0
    private var m2 = 0.0
    var min = Float.POSITIVE_INFINITY; private set
    var max = Float.NEGATIVE_INFINITY; private set

    fun add(x: Float) {
        n++
        val d = x - mean
        mean += d / n
        m2 += d * (x - mean)
        if (x < min) min = x
        if (x > max) max = x
    }

    fun mean(): Float = if (n == 0L) 0f else mean.toFloat()
    fun variance(): Float = if (n < 2) 0f else (m2 / (n - 1)).toFloat()
    fun minOrZero(): Float = if (n == 0L) 0f else min
    fun maxOrZero(): Float = if (n == 0L) 0f else max

    /** Serialise to a compact string for incremental persistence (process-death safety). */
    fun encode(): String = "$n|$mean|$m2|$min|$max"

    companion object {
        fun decode(s: String): Welford {
            val p = s.split('|')
            val w = Welford()
            w.n = p[0].toLong(); w.mean = p[1].toDouble(); w.m2 = p[2].toDouble()
            w.min = p[3].toFloat(); w.max = p[4].toFloat()
            return w
        }
    }
}

/** Running mean only (for raw submetric means in the packet). */
class RunningMean {
    private var n = 0L
    private var sum = 0.0
    fun add(x: Float) { n++; sum += x }
    fun mean(): Float = if (n == 0L) 0f else (sum / n).toFloat()
    val count get() = n

    fun encode(): String = "$n|$sum"
    companion object {
        fun decode(s: String): RunningMean {
            val p = s.split('|')
            val rm = RunningMean()
            rm.n = p[0].toLong(); rm.sum = p[1].toDouble()
            return rm
        }
    }
}
