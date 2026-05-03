package com.safepath.indore.routing

/**
 * Coarse OSM-style road classification used for the road-priority penalty.
 * Penalty values come from the spec.
 */
enum class RoadType(val penalty: Double) {
    MOTORWAY(0.0),
    PRIMARY(1.0),
    SECONDARY(2.0),
    TERTIARY(4.0),
    RESIDENTIAL(6.0),
    SERVICE(8.0);
}
