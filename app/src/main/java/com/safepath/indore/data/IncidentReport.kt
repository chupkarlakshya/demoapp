package com.safepath.indore.data

import com.google.android.gms.maps.model.LatLng

/**
 * Types of incidents that can be reported by the community.
 */
enum class IncidentType(val label: String) {
    HARASSMENT("Harassment"),
    UNSAFE_SPOT("Unsafe Spot"),
    POOR_LIGHTING("Poor Lighting"),
    SUSPICIOUS_ACTIVITY("Suspicious Activity"),
    OTHER("Other")
}

/**
 * Data model for a crowd-sourced incident report.
 * 
 * BACKEND NOTE:
 * The backend should store these reports with a timestamp. 
 * Reports should ideally expire after a certain period (e.g., 24-48 hours 
 * for suspicious activity, or longer for poor lighting) to keep the map relevant.
 */
data class IncidentReport(
    val id: String = "",
    val type: IncidentType,
    val location: LatLng,
    val description: String,
    val severity: Int = 3,
    val status: String = "pending",
    val timestamp: Long = System.currentTimeMillis(),
    val reportedBy: String = "anonymous" // Could be a user ID in the future
)
