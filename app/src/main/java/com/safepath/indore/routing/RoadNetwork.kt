package com.safepath.indore.routing

import com.google.android.gms.maps.model.LatLng
import com.safepath.indore.utils.GeoUtils

/**
 * Hardcoded "main-road corridor" approximation for Indore.
 *
 * We do NOT have an OSM road graph in the demo, so we approximate the
 * road network by listing the spine corridors (AB Road, Ring Road,
 * Mhow Road, etc.) as polylines. A point's [RoadType] is then derived
 * from how close it is to one of those corridors:
 *
 *   ≤  60 m of MOTORWAY corridor  -> MOTORWAY
 *   ≤ 120 m of PRIMARY corridor   -> PRIMARY
 *   ≤ 200 m of SECONDARY corridor -> SECONDARY
 *   ≤ 350 m of any corridor       -> TERTIARY
 *   else                          -> RESIDENTIAL  (SERVICE for very small isolated points)
 *
 * Coordinates are realistic for Indore (Madhya Pradesh) but not GPS-precise.
 */
object RoadNetwork {

    private data class Corridor(val type: RoadType, val polyline: List<LatLng>)

    private val corridors: List<Corridor> = listOf(
        // AB Road — Indore's main north-south spine (Rajwada → Vijay Nagar → Bypass)
        Corridor(RoadType.MOTORWAY, listOf(
            LatLng(22.7196, 75.8577),  // Rajwada
            LatLng(22.7250, 75.8650),
            LatLng(22.7330, 75.8740),  // Geeta Bhavan
            LatLng(22.7430, 75.8850),  // Palasia
            LatLng(22.7530, 75.8930),  // Vijay Nagar
            LatLng(22.7660, 75.8980)   // Bypass crossing
        )),
        // Ring Road (eastern arc)
        Corridor(RoadType.MOTORWAY, listOf(
            LatLng(22.7660, 75.8980),
            LatLng(22.7700, 75.9100),
            LatLng(22.7600, 75.9220),
            LatLng(22.7400, 75.9270),
            LatLng(22.7150, 75.9200),
            LatLng(22.6970, 75.9050)
        )),
        // MR-10 (north-west)
        Corridor(RoadType.PRIMARY, listOf(
            LatLng(22.7530, 75.8930),
            LatLng(22.7720, 75.8780),
            LatLng(22.7860, 75.8600)
        )),
        // Khandwa Road (south)
        Corridor(RoadType.PRIMARY, listOf(
            LatLng(22.7196, 75.8577),
            LatLng(22.7050, 75.8500),
            LatLng(22.6850, 75.8420),
            LatLng(22.6650, 75.8300)
        )),
        // Mhow / Agra-Bombay road south-west
        Corridor(RoadType.PRIMARY, listOf(
            LatLng(22.7196, 75.8577),
            LatLng(22.7100, 75.8400),
            LatLng(22.6950, 75.8200)
        )),
        // East-west cross corridor (Bhawarkuan → Palasia)
        Corridor(RoadType.SECONDARY, listOf(
            LatLng(22.7050, 75.8700),
            LatLng(22.7200, 75.8780),
            LatLng(22.7330, 75.8740),
            LatLng(22.7430, 75.8850)
        )),
        // Sapna Sangeeta / Geeta Bhavan inner ring
        Corridor(RoadType.SECONDARY, listOf(
            LatLng(22.7280, 75.8700),
            LatLng(22.7350, 75.8800),
            LatLng(22.7400, 75.8900),
            LatLng(22.7460, 75.8970)
        ))
    )

    /** Classify a single coordinate by distance to known corridors. */
    fun classify(point: LatLng): RoadType {
        var bestType: RoadType? = null
        var bestDistance = Double.MAX_VALUE

        for (c in corridors) {
            val d = distanceToPolylineMeters(point, c.polyline)
            // Match the corridor's class only if we're close enough for *that* class.
            val tolerated = when (c.type) {
                RoadType.MOTORWAY -> 60.0
                RoadType.PRIMARY -> 120.0
                RoadType.SECONDARY -> 200.0
                else -> 350.0
            }
            if (d <= tolerated && d < bestDistance) {
                bestDistance = d
                bestType = c.type
            } else if (d <= 350.0 && d < bestDistance && bestType == null) {
                bestDistance = d
                bestType = RoadType.TERTIARY
            }
        }
        if (bestType != null) return bestType
        // Far from any corridor: residential by default.
        return RoadType.RESIDENTIAL
    }

    /**
     * Closest point on any main corridor to [point], used by the safest-route
     * generator to bias the path toward the spine network.
     */
    fun nearestMainRoadPoint(point: LatLng): LatLng {
        var best = point
        var bestD = Double.MAX_VALUE
        for (c in corridors) {
            if (c.type == RoadType.MOTORWAY || c.type == RoadType.PRIMARY) {
                val (closest, d) = nearestOnPolyline(point, c.polyline)
                if (d < bestD) { bestD = d; best = closest }
            }
        }
        return best
    }

    private fun distanceToPolylineMeters(p: LatLng, line: List<LatLng>): Double {
        var min = Double.MAX_VALUE
        for (i in 1 until line.size) {
            val (_, d) = nearestOnSegment(p, line[i - 1], line[i])
            if (d < min) min = d
        }
        return min
    }

    private fun nearestOnPolyline(p: LatLng, line: List<LatLng>): Pair<LatLng, Double> {
        var best = line.first()
        var bestD = Double.MAX_VALUE
        for (i in 1 until line.size) {
            val (q, d) = nearestOnSegment(p, line[i - 1], line[i])
            if (d < bestD) { bestD = d; best = q }
        }
        return best to bestD
    }

    /** Closest point on segment AB to P, plus the distance in metres. */
    private fun nearestOnSegment(p: LatLng, a: LatLng, b: LatLng): Pair<LatLng, Double> {
        // Project in lat/lng space (good enough for short segments).
        val abLat = b.latitude - a.latitude
        val abLng = b.longitude - a.longitude
        val apLat = p.latitude - a.latitude
        val apLng = p.longitude - a.longitude
        val ab2 = abLat * abLat + abLng * abLng
        val t = if (ab2 == 0.0) 0.0
                else ((apLat * abLat + apLng * abLng) / ab2).coerceIn(0.0, 1.0)
        val q = LatLng(a.latitude + t * abLat, a.longitude + t * abLng)
        return q to GeoUtils.distanceMeters(p, q)
    }
}
