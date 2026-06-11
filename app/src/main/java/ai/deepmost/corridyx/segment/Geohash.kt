package ai.deepmost.corridyx.segment

/**
 * Standard geohash (base-32) encoder. Geohash-7 cells are ~153m x ~153m near the equator
 * (Bangalore is ~13degN, so cells are ~152m E-W x ~153m N-S) — the spatial unit for segments.
 */
object Geohash {
    private const val BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz"

    fun encode(lat: Double, lon: Double, precision: Int = 7): String {
        var latMin = -90.0; var latMax = 90.0
        var lonMin = -180.0; var lonMax = 180.0
        val sb = StringBuilder()
        var bit = 0; var ch = 0; var even = true
        while (sb.length < precision) {
            if (even) {
                val mid = (lonMin + lonMax) / 2
                if (lon >= mid) { ch = ch or (1 shl (4 - bit)); lonMin = mid } else lonMax = mid
            } else {
                val mid = (latMin + latMax) / 2
                if (lat >= mid) { ch = ch or (1 shl (4 - bit)); latMin = mid } else latMax = mid
            }
            even = !even
            if (bit < 4) bit++ else { sb.append(BASE32[ch]); bit = 0; ch = 0 }
        }
        return sb.toString()
    }

    /** Returns [centerLat, centerLon] of a geohash cell. */
    fun decodeCenter(hash: String): DoubleArray {
        var latMin = -90.0; var latMax = 90.0
        var lonMin = -180.0; var lonMax = 180.0
        var even = true
        for (c in hash) {
            val cd = BASE32.indexOf(c)
            for (i in 0 until 5) {
                val bit = (cd shr (4 - i)) and 1
                if (even) {
                    val mid = (lonMin + lonMax) / 2
                    if (bit == 1) lonMin = mid else lonMax = mid
                } else {
                    val mid = (latMin + latMax) / 2
                    if (bit == 1) latMin = mid else latMax = mid
                }
                even = !even
            }
        }
        return doubleArrayOf((latMin + latMax) / 2, (lonMin + lonMax) / 2)
    }
}
