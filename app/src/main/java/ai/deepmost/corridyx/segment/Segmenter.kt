package ai.deepmost.corridyx.segment

import ai.deepmost.corridyx.capture.LocationSnapshot

/**
 * A corridor segment: the spatial unit of all scoring. SegmentKey = geohash7 + headingBucket so
 * opposite carriageways (same tile, opposite heading) score separately.
 */
data class SegmentKey(
    val geohash7: String,
    val headingBucket: Int,   // 0..7, compass octant of travel
) {
    fun encode(): String = "$geohash7:$headingBucket"
    companion object {
        fun decode(s: String): SegmentKey {
            val i = s.lastIndexOf(':')
            return SegmentKey(s.substring(0, i), s.substring(i + 1).toInt())
        }
    }
}

/**
 * Maps a location fix to a [SegmentKey]. Behind an interface so a future map-matched (OSM way-id)
 * segmenter is a drop-in — the rest of the pipeline depends only on this contract.
 */
interface Segmenter {
    /** @return the segment for this fix, or null if the fix lacks the data to assign one. */
    fun segmentFor(loc: LocationSnapshot): SegmentKey?

    /** Stable centroid lat/lon for a key (for map rendering + packet centroid). */
    fun centroid(key: SegmentKey): DoubleArray
}

/**
 * Geohash-7 + 8-way-heading segmenter. Heading comes from movement bearing; when the vehicle is
 * effectively stationary (no bearing), heading bucket falls back to the last known bucket carried
 * by the caller — here we require a bearing and return bucket from it, leaving stationary handling
 * to the accumulator (which holds the active key during brief stops).
 */
class GeohashHeadingSegmenter : Segmenter {
    override fun segmentFor(loc: LocationSnapshot): SegmentKey? {
        if (!loc.hasBearing) return null
        val gh = Geohash.encode(loc.lat, loc.lon, 7)
        return SegmentKey(gh, headingBucket(loc.bearingDeg))
    }

    override fun centroid(key: SegmentKey): DoubleArray = Geohash.decodeCenter(key.geohash7)

    companion object {
        /** 0=N,1=NE,2=E,3=SE,4=S,5=SW,6=W,7=NW. */
        fun headingBucket(bearingDeg: Float): Int {
            val b = ((bearingDeg % 360f) + 360f) % 360f
            return (((b + 22.5f) / 45f).toInt()) % 8
        }
    }
}
