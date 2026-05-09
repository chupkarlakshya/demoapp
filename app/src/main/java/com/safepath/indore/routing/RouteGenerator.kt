package com.safepath.indore.routing

import com.google.android.gms.maps.model.LatLng
import com.safepath.indore.data.RiskCalculator
import com.safepath.indore.utils.GeoUtils

/**
 * Generates exactly three candidate routes between an origin and a
 * destination using the cost functions from the spec:
 *
 *   Fastest:  cost = distance
 *   Balanced: cost = distance + 3 * risk + 2 * road_penalty
 *   Safest:   cost = distance + 6 * risk + 5 * road_penalty
 *
 * We do not have a real road graph in the demo. Instead each route is
 * built as a polyline of ~12 waypoints starting from the straight line
 * between A and B; the BALANCED and SAFEST variants then perturb each
 * intermediate waypoint perpendicular to that line, picking whichever
 * lateral offset minimises that route's cost function. This keeps the
 * routes geographically distinct, lets the risk/road penalty meaningfully
 * shape the path, and stays well within the "no heavy ML" constraint.
 *
 * The "Stick to Main Roads" toggle doubles the road penalty for
 * tertiary/residential/service segments (except in a ~500 m buffer near
 * the start and end, as required).
 */
class RouteGenerator(private val riskCalc: RiskCalculator) {

    private val numWaypoints = 11           // total points incl. endpoints (matches the 10–15 cap for Maps URL)

    fun generate(origin: LatLng, destination: LatLng, stickToMainRoads: Boolean): List<Route> {
        val baseline = straightLine(origin, destination, numWaypoints)
        val directDist = GeoUtils.distanceMeters(origin, destination)

        // Scale offsets based on trip length (max offset is 20% of trip distance or 1km, whichever is smaller)
        val maxOff = (directDist * 0.2).coerceAtMost(1000.0).coerceAtLeast(100.0)
        val dynamicBalanced = listOf(-maxOff * 0.6, -maxOff * 0.3, 0.0, maxOff * 0.3, maxOff * 0.6)
        val dynamicSafest   = listOf(-maxOff, -maxOff * 0.6, -maxOff * 0.3, 0.0, maxOff * 0.3, maxOff * 0.6, maxOff)

        val fastest  = scoreRoute(RouteType.FASTEST, baseline, stickToMainRoads, origin, destination)
        val balanced = optimise(RouteType.BALANCED, origin, destination, dynamicBalanced, stickToMainRoads)
        val safest   = optimise(RouteType.SAFEST, origin, destination, dynamicSafest, stickToMainRoads)

        return listOf(fastest, balanced, safest)
    }

    // --- core ---------------------------------------------------------------

    private fun straightLine(a: LatLng, b: LatLng, n: Int): List<LatLng> {
        val out = ArrayList<LatLng>(n)
        for (i in 0 until n) {
            val t = i.toDouble() / (n - 1)
            out += GeoUtils.interpolate(a, b, t)
        }
        return out
    }

    /**
     * For each intermediate waypoint, pick the lateral offset that
     * minimises the candidate's cost function.
     */
    private fun optimise(
        type: RouteType,
        a: LatLng, b: LatLng,
        offsets: List<Double>,
        stickToMainRoads: Boolean
    ): Route {
        val pts = ArrayList<LatLng>(numWaypoints)
        pts += a
        
        val totalDirectDist = GeoUtils.distanceMeters(a, b)

        for (i in 1 until numWaypoints - 1) {
            val t = i.toDouble() / (numWaypoints - 1)
            val baseline = GeoUtils.interpolate(a, b, t)

            // Candidate set: lateral perpendicular offsets...
            val candidates = ArrayList<LatLng>()
            for (off in offsets) {
                candidates += if (off == 0.0) baseline
                              else GeoUtils.perpendicularOffset(a, b, t, off)
            }
            
            // ...plus, for SAFEST/BALANCED, the projection onto the nearest main corridor,
            // but ONLY if it's not a massive detour and it's making forward progress.
            if (type == RouteType.SAFEST || type == RouteType.BALANCED) {
                val mainRoad = RoadNetwork.nearestMainRoadPoint(baseline)
                val distToMain = GeoUtils.distanceMeters(baseline, mainRoad)
                
                val mainDistToDest = GeoUtils.distanceMeters(mainRoad, b)
                val baseDistToDest = GeoUtils.distanceMeters(baseline, b)

                // Only pull to main road if it's reasonably close AND doesn't take us backwards
                if (distToMain < totalDirectDist * 0.4 && mainDistToDest <= baseDistToDest + 100) {
                    candidates += mainRoad
                }
            }

            val prev = pts.last()
            val best = candidates.minBy { c ->
                val d = GeoUtils.distanceMeters(prev, c)
                val distFromBaseline = GeoUtils.distanceMeters(baseline, c)
                val risk = riskCalc.riskAt(c)
                val penalty = roadPenaltyAt(c, t, stickToMainRoads)

                // PROGRESS CHECK: Don't go backwards
                val distToDest = GeoUtils.distanceMeters(c, b)
                val prevDistToDest = GeoUtils.distanceMeters(prev, b)
                val movingAwayPenalty = if (distToDest > prevDistToDest) {
                    (distToDest - prevDistToDest) * 100.0 // Extreme penalty for moving backwards
                } else 0.0

                // COST FUNCTION
                // We increase the distance weight for SAFEST to penalize massive detours more.
                // We also add a small penalty for distance from baseline to keep the route "tight".
                val distWeight = if (type == RouteType.SAFEST) 3.0 else 2.0
                val baselineWeight = 1.0 // Increased to keep it tighter
                val riskWeight = if (type == RouteType.SAFEST) 12.0 else 6.0
                val penWeight  = if (type == RouteType.SAFEST) 10.0 else 5.0
                
                (d * distWeight) + (distFromBaseline * baselineWeight) + 
                (risk * riskWeight * 40) + (penalty * penWeight * 40) + movingAwayPenalty
            }
            pts += best
        }
        pts += b
        return scoreRoute(type, pts, stickToMainRoads, a, b)
    }

    /**
     * Apply the road penalty at a sampled point. The "stick to main roads"
     * multiplier (2×) only applies away from the start/end 500 m buffer.
     */
    private fun roadPenaltyAt(point: LatLng, progressT: Double, stickToMainRoads: Boolean): Double {
        val type = RoadNetwork.classify(point)
        var pen = type.penalty
        if (stickToMainRoads &&
            progressT > 0.05 && progressT < 0.95 &&     // skip start/end buffer
            (type == RoadType.TERTIARY || type == RoadType.RESIDENTIAL || type == RoadType.SERVICE)) {
            pen *= 2.0
        }
        return pen
    }

    /**
     * Compute aggregate distance / risk / road-penalty / cost for a finalised
     * polyline. This is the canonical scoring used in the bottom panel.
     */
    private fun scoreRoute(
        type: RouteType,
        points: List<LatLng>,
        stickToMainRoads: Boolean,
        origin: LatLng, destination: LatLng
    ): Route {
        val dist = GeoUtils.polylineLengthMeters(points)

        // Risk along the route (sample every 100 m).
        val risk = riskCalc.routeRisk(points, sampleEveryMeters = 100.0)

        // Average road penalty per sample, also at 100 m.
        val samples = GeoUtils.samplePolyline(points, 100.0)
        var penaltySum = 0.0
        val totalLen = GeoUtils.distanceMeters(origin, destination).coerceAtLeast(1.0)
        for ((idx, s) in samples.withIndex()) {
            val t = idx.toDouble() / (samples.size - 1).coerceAtLeast(1)
            penaltySum += roadPenaltyAt(s, t, stickToMainRoads)
        }
        val penaltyAvg = penaltySum / samples.size.coerceAtLeast(1)

        val cost = when (type) {
            RouteType.FASTEST  -> dist
            RouteType.BALANCED -> dist + 3.0 * risk + 2.0 * penaltyAvg * totalLen
            RouteType.SAFEST   -> dist + 6.0 * risk + 5.0 * penaltyAvg * totalLen
        }
        return Route(type, points, dist, risk, penaltyAvg, cost)
    }
}
