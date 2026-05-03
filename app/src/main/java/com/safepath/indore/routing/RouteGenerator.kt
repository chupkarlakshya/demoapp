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
    private val balancedOffsets = listOf(-300.0, -150.0, 0.0, 150.0, 300.0)
    private val safestOffsets   = listOf(-500.0, -300.0, -150.0, 0.0, 150.0, 300.0, 500.0)

    fun generate(origin: LatLng, destination: LatLng, stickToMainRoads: Boolean): List<Route> {
        val baseline = straightLine(origin, destination, numWaypoints)

        val fastest  = scoreRoute(RouteType.FASTEST, baseline, stickToMainRoads, origin, destination)
        val balanced = optimise(RouteType.BALANCED, origin, destination, balancedOffsets, stickToMainRoads)
        val safest   = optimise(RouteType.SAFEST, origin, destination, safestOffsets, stickToMainRoads)

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
        // For the SAFEST route, also blend in the nearest main-corridor point.
        val biasToMainRoad = (type == RouteType.SAFEST)

        val pts = ArrayList<LatLng>(numWaypoints)
        pts += a
        for (i in 1 until numWaypoints - 1) {
            val t = i.toDouble() / (numWaypoints - 1)
            val baseline = GeoUtils.interpolate(a, b, t)

            // Candidate set: lateral perpendicular offsets...
            val candidates = ArrayList<LatLng>(offsets.size + 1)
            for (off in offsets) {
                candidates += if (off == 0.0) baseline
                              else GeoUtils.perpendicularOffset(a, b, t, off)
            }
            // ...plus, for SAFEST, the projection onto the nearest main corridor.
            if (biasToMainRoad) candidates += RoadNetwork.nearestMainRoadPoint(baseline)

            val prev = pts.last()
            val best = candidates.minBy { c ->
                segmentCost(type, prev, c, t, stickToMainRoads)
            }
            pts += best
        }
        pts += b
        return scoreRoute(type, pts, stickToMainRoads, a, b)
    }

    /** Cost of the *next segment* (prev -> candidate) for a given route type. */
    private fun segmentCost(
        type: RouteType,
        prev: LatLng,
        candidate: LatLng,
        progressT: Double,
        stickToMainRoads: Boolean
    ): Double {
        val dist = GeoUtils.distanceMeters(prev, candidate)
        val risk = riskCalc.riskAt(candidate)
        val penalty = roadPenaltyAt(candidate, progressT, stickToMainRoads)
        return when (type) {
            RouteType.FASTEST  -> dist
            RouteType.BALANCED -> dist + 3.0 * risk * 50 + 2.0 * penalty * 50
            RouteType.SAFEST   -> dist + 6.0 * risk * 50 + 5.0 * penalty * 50
        }
        // Note: risk / penalty are scaled up so their costs are commensurate
        // with metre-based distance. (50 ≈ avg sample spacing.)
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
