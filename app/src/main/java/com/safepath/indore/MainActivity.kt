package com.safepath.indore

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.CountDownTimer
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import android.widget.EditText
import android.widget.LinearLayout as LayoutWidget
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import android.os.Vibrator
import android.os.VibrationEffect
import android.os.Build
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.Circle
import com.google.android.gms.maps.model.CircleOptions
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions
import com.google.maps.android.heatmaps.Gradient
import com.google.maps.android.heatmaps.HeatmapTileProvider
import com.google.maps.android.heatmaps.WeightedLatLng
import com.safepath.indore.data.CrimeDataLoader
import com.safepath.indore.data.IncidentReport
import com.safepath.indore.data.IncidentRepository
import com.safepath.indore.data.IncidentType
import com.safepath.indore.data.RiskApiRepository
import com.safepath.indore.data.RiskCalculator
import com.safepath.indore.data.RiskCell
import com.safepath.indore.databinding.ActivityMainBinding
import com.safepath.indore.routing.Route
import com.safepath.indore.routing.RouteGenerator
import com.safepath.indore.routing.RouteType
import com.safepath.indore.ui.FakeCallActivity
import com.safepath.indore.ui.LiveTrackActivity
import com.safepath.indore.ui.SosActivity
import com.safepath.indore.utils.Geocoder
import com.safepath.indore.utils.VoiceCoach
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var riskCalc: RiskCalculator
    private lateinit var routeGen: RouteGenerator
    private lateinit var fusedClient: FusedLocationProviderClient

    private var googleMap: GoogleMap? = null

    // Default origin → Rajwada (Indore old town). Updated to user GPS once available.
    private var origin: LatLng = LatLng(22.7196, 75.8577)
    private var destination: LatLng? = null

    private val routePolylines = mutableMapOf<RouteType, Polyline>()
    private val markers = mutableListOf<Marker>()
    private val incidentMarkers = mutableListOf<Marker>()
    private val policeMarkers = mutableListOf<Marker>()
    private val aiRiskCircles = mutableListOf<Circle>()
    private var heatmapOverlay: com.google.android.gms.maps.model.TileOverlay? = null
    private var unsafeCircle: Circle? = null

    private var routes: List<Route> = emptyList()
    private var selected: RouteType = RouteType.SAFEST
    private var heatmapVisible = false
    private var policeVisible = false
    private var aiRiskVisible = false
    private var safetyTimer: CountDownTimer? = null
    private lateinit var voice: VoiceCoach
    
    // For crowd-sourced reporting
    private var isPickingIncidentLocation = false

    private val policeStations = listOf(
        Pair("Vijay Nagar Police Station", LatLng(22.7533, 75.8937)),
        Pair("Palasia Police Station", LatLng(22.7244, 75.8839)),
        Pair("Sarafa Police Station (Rajwada)", LatLng(22.7196, 75.8577)),
        Pair("Bhawarkuan Police Station", LatLng(22.7001, 75.8701)),
        Pair("Annapurna Police Station", LatLng(22.6934, 75.8344)),
        Pair("Khajrana Police Station", LatLng(22.7441, 75.9012)),
        Pair("Tukoganj Police Station", LatLng(22.7231, 75.8744)),
        Pair("Aerodrome Police Station", LatLng(22.7248, 75.8075))
    )

    private val locationPermissionRequest = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.any { it }) enableMyLocationLayer()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        voice = VoiceCoach(this)

        // Heavy work off the main thread.
        lifecycleScope.launch {
            val crimes = withContext(Dispatchers.IO) { CrimeDataLoader.load(this@MainActivity) }
            riskCalc = RiskCalculator(crimes)
            routeGen = RouteGenerator(riskCalc)
            refreshCommunityIncidents()
        }

        setupMap()
        setupTopBar()
        setupBottomPanel()
        setupSosButton()
    }

    // ---------------------------------------------------------------- Map ---

    private fun setupMap() {
        val frag = supportFragmentManager.findFragmentById(R.id.mapFragment) as SupportMapFragment
        frag.getMapAsync { map ->
            googleMap = map
            map.uiSettings.isZoomControlsEnabled = false
            map.uiSettings.isMyLocationButtonEnabled = true
            map.uiSettings.isCompassEnabled = true

            map.setOnPolylineClickListener { line ->
                val type = line.tag as? RouteType ?: return@setOnPolylineClickListener
                selectRoute(type)
            }

            map.setOnMapClickListener { latLng ->
                if (isPickingIncidentLocation) {
                    isPickingIncidentLocation = false
                    startIncidentTypeSelection(latLng)
                }
            }

            map.setOnMapLongClickListener { latLng ->
                setDestinationAt(latLng, "Dropped Pin")
            }

            // Center on Indore.
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(origin, 13f))
            requestLocation()
            updateUnsafeWarning()
            
            refreshCommunityIncidents()
        }
    }

    private fun requestLocation() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fine != PackageManager.PERMISSION_GRANTED && coarse != PackageManager.PERMISSION_GRANTED) {
            locationPermissionRequest.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ))
        } else {
            enableMyLocationLayer()
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableMyLocationLayer() {
        val map = googleMap ?: return
        try {
            map.isMyLocationEnabled = true
            fusedClient.lastLocation.addOnSuccessListener { loc ->
                if (loc != null) {
                    origin = LatLng(loc.latitude, loc.longitude)
                    map.animateCamera(CameraUpdateFactory.newLatLngZoom(origin, 14f))
                    updateUnsafeWarning()
                }
            }
        } catch (_: SecurityException) { /* permission revoked mid-flight */ }
    }

    // ---------------------------------------------------------------- UI ----

    private fun setupTopBar() {
        binding.destinationInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                onDestinationEntered(binding.destinationInput.text.toString())
                true
            } else false
        }
    }

    private fun setupBottomPanel() {
        binding.btnSafeRoute.setOnClickListener {
            val q = binding.destinationInput.text.toString()
            if (q.isBlank()) {
                Toast.makeText(this, "Enter a destination first (e.g. Vijay Nagar).", Toast.LENGTH_SHORT).show()
                binding.destinationInput.requestFocus()
            } else onDestinationEntered(q)
        }

        binding.btnHeatmap.setOnClickListener { toggleHeatmap() }
        binding.btnPoliceStations.setOnClickListener { togglePoliceStations() }
        binding.btnAiRisk.setOnClickListener { toggleAiRisk() }
        binding.btnVoice.setOnClickListener { toggleVoice() }
        binding.btnFakeCall.setOnClickListener {
            startActivity(Intent(this, FakeCallActivity::class.java))
        }
        binding.btnLiveTrack.setOnClickListener {
            startActivity(Intent(this, LiveTrackActivity::class.java))
        }
        binding.btnContacts.setOnClickListener { showContactDialog() }
        binding.btnTimer.setOnClickListener { showTimerDialog() }
        binding.btnReportIncident.setOnClickListener { showReportIncidentDialog() }

        binding.routeChipFastest.setOnClickListener  { selectRoute(RouteType.FASTEST) }
        binding.routeChipBalanced.setOnClickListener { selectRoute(RouteType.BALANCED) }
        binding.routeChipSafest.setOnClickListener   { selectRoute(RouteType.SAFEST) }

        binding.mainRoadSwitch.setOnCheckedChangeListener { _, _ ->
            destination?.let { regenerateRoutes(it) }
        }

        binding.navigateButton.setOnClickListener { launchGoogleMapsNavigation() }
    }

    // ---------------------------------------------------------- SOS hold ----

    @SuppressLint("ClickableViewAccessibility")
    private fun setupSosButton() {
        var timer: CountDownTimer? = null
        binding.sosButton.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    binding.sosProgress.visibility = View.VISIBLE
                    binding.sosProgress.progress = 0
                    timer = object : CountDownTimer(2000, 50) {
                        override fun onTick(left: Long) {
                            binding.sosProgress.progress = (((2000 - left).toFloat() / 2000f) * 100).toInt()
                        }
                        override fun onFinish() {
                            binding.sosProgress.progress = 100
                            triggerSos()
                        }
                    }.also { it.start() }
                    v.performClick()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    timer?.cancel()
                    binding.sosProgress.visibility = View.INVISIBLE
                    true
                }
                else -> false
            }
        }
    }

    private fun triggerSos() {
        // Haptic feedback
        val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(500)
        }

        val intent = Intent(this, SosActivity::class.java)
            .putExtra(SosActivity.EXTRA_LAT, origin.latitude)
            .putExtra(SosActivity.EXTRA_LNG, origin.longitude)
        startActivity(intent)
    }

    // ----------------------------------------------------- Destination + routes

    private fun onDestinationEntered(rawQuery: String) {
        if (!::routeGen.isInitialized) {
            Toast.makeText(this, "Loading crime data… try again in a sec.", Toast.LENGTH_SHORT).show()
            return
        }
        val coord = Geocoder.geocode(this, rawQuery)
        if (coord == null) {
            Toast.makeText(
                this,
                "Unknown place. Try long-pressing on the map or type: Rajwada, Vijay Nagar, Palasia.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        setDestinationAt(coord, rawQuery)
    }

    private fun setDestinationAt(latLng: LatLng, label: String) {
        destination = latLng
        binding.destinationInput.setText(label)
        regenerateRoutes(latLng)
        Toast.makeText(this, "Destination set: $label", Toast.LENGTH_SHORT).show()
    }

    private fun regenerateRoutes(dest: LatLng) {
        val map = googleMap ?: return
        val stick = binding.mainRoadSwitch.isChecked
        routes = routeGen.generate(origin, dest, stick)

        clearRoutes()
        for (r in routes) {
            val color = when (r.type) {
                RouteType.FASTEST -> ContextCompat.getColor(this, R.color.route_fastest)
                RouteType.BALANCED -> ContextCompat.getColor(this, R.color.route_balanced)
                RouteType.SAFEST -> ContextCompat.getColor(this, R.color.route_safest)
            }
            val width = if (r.type == selected) 18f else 10f
            val poly = map.addPolyline(PolylineOptions()
                .addAll(r.points)
                .color(color)
                .width(width)
                .clickable(true))
            poly.tag = r.type
            routePolylines[r.type] = poly
        }

        // Markers
        markers += map.addMarker(MarkerOptions().position(origin).title("You")
            .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE)))!!
        markers += map.addMarker(MarkerOptions().position(dest).title("Destination")
            .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_VIOLET)))!!

        // Camera fit
        val bounds = LatLngBounds.Builder().include(origin).include(dest).build()
        map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 200))

        // Update bottom UI
        binding.routeFastestStats.text  = routes.first { it.type == RouteType.FASTEST }.shortLabel()
        binding.routeBalancedStats.text = routes.first { it.type == RouteType.BALANCED }.shortLabel()
        binding.routeSafestStats.text   = routes.first { it.type == RouteType.SAFEST }.shortLabel()
        binding.routeSummary.visibility = View.VISIBLE
        binding.mainRoadToggleRow.visibility = View.VISIBLE
        binding.navigateButton.visibility = View.VISIBLE

        selectRoute(selected)
        updateUnsafeWarning()

        val safest = routes.firstOrNull { it.type == RouteType.SAFEST }
        if (safest != null) {
            val km = "%.1f".format(safest.distanceMeters / 1000.0)
            val riskPerKm = if (safest.distanceMeters > 0)
                safest.risk / (safest.distanceMeters / 1000.0) else 0.0
            val riskLabel = when {
                riskPerKm < 10 -> "low risk"
                riskPerKm < 40 -> "moderate risk"
                else -> "elevated risk"
            }
            voice.speak("Three routes found. Safest is $km kilometers, $riskLabel. Stay on main roads.")
        }
    }

    private fun clearRoutes() {
        routePolylines.values.forEach { it.remove() }
        routePolylines.clear()
        markers.forEach { it.remove() }
        markers.clear()
    }

    private fun selectRoute(type: RouteType) {
        selected = type
        for ((t, line) in routePolylines) {
            line.width = if (t == type) 18f else 10f
            line.zIndex = if (t == type) 2f else 1f
        }
        binding.routeChipFastest.isSelected  = type == RouteType.FASTEST
        binding.routeChipBalanced.isSelected = type == RouteType.BALANCED
        binding.routeChipSafest.isSelected   = type == RouteType.SAFEST
    }

    // ------------------------------------------------- Heatmap toggle -------

    private fun toggleHeatmap() {
        val map = googleMap ?: return
        if (!::riskCalc.isInitialized) {
            Toast.makeText(this, "Loading crime data…", Toast.LENGTH_SHORT).show()
            return
        }
        if (heatmapVisible) {
            heatmapOverlay?.remove()
            heatmapOverlay = null
            heatmapVisible = false
            return
        }
        val weighted = riskCalc.allWeightedPoints().map { (latLng, w) ->
            WeightedLatLng(latLng, w)
        }
        if (weighted.isEmpty()) return

        val gradient = Gradient(
            intArrayOf(
                ContextCompat.getColor(this, R.color.risk_low),
                ContextCompat.getColor(this, R.color.risk_medium),
                ContextCompat.getColor(this, R.color.risk_high)
            ),
            floatArrayOf(0.2f, 0.6f, 1.0f)
        )
        val provider = HeatmapTileProvider.Builder()
            .weightedData(weighted)
            .radius(40)
            .gradient(gradient)
            .opacity(0.6)
            .build()
        heatmapOverlay = map.addTileOverlay(
            com.google.android.gms.maps.model.TileOverlayOptions().tileProvider(provider)
        )
        heatmapVisible = true
    }

    // ----------------------------------------------- AI risk grid overlay --

    private fun toggleAiRisk() {
        val map = googleMap ?: return
        if (aiRiskVisible) {
            aiRiskCircles.forEach { it.remove() }
            aiRiskCircles.clear()
            aiRiskVisible = false
            binding.btnAiRisk.isSelected = false
            voice.speak("AI risk overlay off")
            return
        }

        Toast.makeText(this, "Loading AI risk model…", Toast.LENGTH_SHORT).show()
        // Use the visible map bounds so we don't waste cells off-screen.
        val region = map.projection.visibleRegion.latLngBounds
        val sw = region.southwest
        val ne = region.northeast

        RiskApiRepository.fetchGrid(
            minLat = sw.latitude,
            maxLat = ne.latitude,
            minLng = sw.longitude,
            maxLng = ne.longitude,
            steps = 22
        ) { cells ->
            if (cells.isEmpty()) {
                Toast.makeText(
                    this,
                    "AI risk unavailable — is the backend running and the model trained?",
                    Toast.LENGTH_LONG
                ).show()
                return@fetchGrid
            }
            renderAiRiskCells(cells)
            aiRiskVisible = true
            binding.btnAiRisk.isSelected = true
            val hot = cells.count { it.score >= 30 }
            voice.speak(
                "A I risk overlay on. ${cells.size} cells analyzed, $hot high risk."
            )
        }
    }

    private fun renderAiRiskCells(cells: List<RiskCell>) {
        val map = googleMap ?: return
        aiRiskCircles.forEach { it.remove() }
        aiRiskCircles.clear()

        // Adaptive cell radius based on grid spacing — works for any zoom.
        val latitudes = cells.map { it.location.latitude }.distinct().sorted()
        val cellSpacingDeg =
            if (latitudes.size >= 2) latitudes[1] - latitudes[0] else 0.005
        val radiusMeters = (cellSpacingDeg * 111_000.0 * 0.55)

        for (cell in cells) {
            if (cell.score < 1.0) continue
            val color = riskColor(cell.score)
            val circle = map.addCircle(CircleOptions()
                .center(cell.location)
                .radius(radiusMeters)
                .strokeWidth(0f)
                .fillColor(color))
            aiRiskCircles += circle
        }
    }

    /** Maps 0–100 risk score to a translucent green→yellow→red ARGB color. */
    private fun riskColor(score: Double): Int {
        val s = score.coerceIn(0.0, 100.0) / 100.0
        val r: Int
        val g: Int
        if (s < 0.5) {
            r = (2 * s * 255).toInt().coerceIn(0, 255)
            g = 200
        } else {
            r = 230
            g = ((1 - 2 * (s - 0.5)) * 200).toInt().coerceIn(0, 200)
        }
        val alpha = (90 + 130 * s).toInt().coerceIn(0, 220)
        return (alpha shl 24) or (r shl 16) or (g shl 8)
    }

    // ------------------------------------------------------- Voice toggle --

    private fun toggleVoice() {
        voice.enabled = !voice.enabled
        binding.btnVoiceLabel.text = if (voice.enabled) "Voice On" else "Voice Off"
        binding.btnVoice.isSelected = voice.enabled
        if (voice.enabled) {
            voice.speak("Voice prompts enabled.")
        }
    }

    private fun togglePoliceStations() {
        val map = googleMap ?: return
        if (policeVisible) {
            policeMarkers.forEach { it.remove() }
            policeMarkers.clear()
            policeVisible = false
            binding.btnPoliceStations.isSelected = false
            return
        }

        for ((name, pos) in policeStations) {
            val marker = map.addMarker(MarkerOptions()
                .position(pos)
                .title(name)
                .snippet("Ready to Dispatch")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
            )
            marker?.let { policeMarkers.add(it) }
        }
        policeVisible = true
        binding.btnPoliceStations.isSelected = true
        Toast.makeText(this, "Police Stations Highlighted", Toast.LENGTH_SHORT).show()
    }

    // -------------------------------------------------- Unsafe warning ------

    private fun updateUnsafeWarning() {
        if (!::riskCalc.isInitialized) return
        val map = googleMap ?: return
        val wasHigh = unsafeCircle != null
        val high = riskCalc.isHighRisk(origin)
        binding.warningChip.visibility = if (high) View.VISIBLE else View.GONE
        unsafeCircle?.remove()
        unsafeCircle = null
        if (high) {
            unsafeCircle = map.addCircle(CircleOptions()
                .center(origin)
                .radius(250.0)
                .strokeColor(ContextCompat.getColor(this, R.color.risk_high))
                .strokeWidth(4f)
                .fillColor(0x33E53935))
            if (!wasHigh && ::voice.isInitialized) {
                voice.speak("Caution. You are entering an area with elevated risk. Stay alert.")
            }
        }
    }

    // -------------------------------------------- Google Maps deep link ----

    private fun launchGoogleMapsNavigation() {
        val route = routes.firstOrNull { it.type == selected } ?: return
        val pts = route.points
        if (pts.size < 2) return

        val km = "%.1f".format(route.distanceMeters / 1000.0)
        voice.speak("Starting navigation. ${route.type.name.lowercase()} route, $km kilometers.")

        val start = pts.first()
        val end = pts.last()

        // Increase waypoints for better accuracy while maintaining route logic.
        // We use 8 intermediate points to guide Google Maps more precisely.
        val sampled = mutableListOf<LatLng>()
        val mids = pts.drop(1).dropLast(1)
        if (mids.isNotEmpty()) {
            val count = 8.coerceAtMost(mids.size)
            for (i in 0 until count) {
                sampled.add(mids[(i * mids.size) / count])
            }
        }

        val waypointParam = sampled.joinToString("|") { "%.6f,%.6f".format(it.latitude, it.longitude) }
        val builder = Uri.parse("https://www.google.com/maps/dir/").buildUpon()
            .appendQueryParameter("api", "1")
            .appendQueryParameter("origin", "%.6f,%.6f".format(start.latitude, start.longitude))
            .appendQueryParameter("destination", "%.6f,%.6f".format(end.latitude, end.longitude))
            .appendQueryParameter("travelmode", "driving")

        if (waypointParam.isNotEmpty()) {
            builder.appendQueryParameter("waypoints", waypointParam)
        }

        val intent = Intent(Intent.ACTION_VIEW, builder.build())
        intent.setPackage("com.google.android.apps.maps")
        try {
            startActivity(intent)
        } catch (_: Exception) {
            // Fall back to a browser if the Maps app isn't installed.
            startActivity(Intent(Intent.ACTION_VIEW, builder.build()))
        }
    }

    private fun showContactDialog() {
        val prefs = getSharedPreferences("SafePath", MODE_PRIVATE)
        val current = prefs.getString("emergency_contact", "")
        val demoNums = arrayOf("+917000127676", "+918889800445")

        val input = EditText(this).apply {
            hint = "Enter phone number"
            setText(current)
            inputType = android.text.InputType.TYPE_CLASS_PHONE
            setPadding(60, 40, 60, 40)
        }

        AlertDialog.Builder(this)
            .setTitle("Set Emergency Contact")
            .setMessage("Type a number or tap a demo contact below:")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                prefs.edit().putString("emergency_contact", input.text.toString()).apply()
                Toast.makeText(this, "Contact saved!", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Demo Numbers") { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle("Select Demo Contact")
                    .setItems(demoNums) { _, i ->
                        prefs.edit().putString("emergency_contact", demoNums[i]).apply()
                        Toast.makeText(this, "Demo ${i+1} saved!", Toast.LENGTH_SHORT).show()
                    }
                    .show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showTimerDialog() {
        if (safetyTimer != null) {
            safetyTimer?.cancel()
            safetyTimer = null
            binding.statusChip.findViewById<android.widget.TextView>(android.R.id.text1)?.text = "🛡 Shield Secured"
            Toast.makeText(this, "Safety timer cancelled.", Toast.LENGTH_SHORT).show()
            return
        }

        val options = arrayOf("10 Minutes", "20 Minutes", "30 Minutes", "60 Minutes")
        val minutes = intArrayOf(10, 20, 30, 60)

        AlertDialog.Builder(this)
            .setTitle("Set Safety Arrival Timer")
            .setItems(options) { _, which ->
                startSafetyTimer(minutes[which])
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun startSafetyTimer(mins: Int) {
        val millis = mins * 60 * 1000L
        safetyTimer = object : CountDownTimer(millis, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val sec = (millisUntilFinished / 1000) % 60
                val min = (millisUntilFinished / (1000 * 60)) % 60
                // Update status chip or title to show timer
                binding.statusChip.findViewById<android.widget.TextView>(android.R.id.text1)?.text = 
                    "⏱ %02d:%02d".format(min, sec)
            }

            override fun onFinish() {
                triggerSos()
                safetyTimer = null
            }
        }.start()
        Toast.makeText(this, "Safety timer started for $mins mins.", Toast.LENGTH_SHORT).show()
    }

    private fun showReportIncidentDialog() {
        val options = arrayOf("My Current Location", "Select Location on Map")
        
        AlertDialog.Builder(this)
            .setTitle("Report Safety Incident")
            .setItems(options) { _, which ->
                if (which == 0) {
                    startIncidentTypeSelection(origin)
                } else {
                    isPickingIncidentLocation = true
                    Toast.makeText(this, "Tap on the map where the incident occurred", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startIncidentTypeSelection(location: LatLng) {
        val types = IncidentType.values().map { it.label }.toTypedArray()
        var selectedTypeIndex = 0

        AlertDialog.Builder(this)
            .setTitle("What happened?")
            .setSingleChoiceItems(types, 0) { _, which ->
                selectedTypeIndex = which
            }
            .setPositiveButton("Next") { _, _ ->
                showIncidentDetailsDialog(IncidentType.values()[selectedTypeIndex], location)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showIncidentDetailsDialog(type: IncidentType, location: LatLng) {
        val form = LayoutWidget(this).apply {
            orientation = LayoutWidget.VERTICAL
            setPadding(48, 24, 48, 8)
        }
        val input = EditText(this).apply {
            hint = "Describe what happened (optional)"
            minLines = 2
        }
        val severityLabel = TextView(this).apply {
            text = "Severity: 3 / 5"
            setPadding(0, 18, 0, 4)
        }
        val severitySeek = SeekBar(this).apply {
            max = 4
            progress = 2
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    severityLabel.text = "Severity: ${progress + 1} / 5"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        form.addView(input)
        form.addView(severityLabel)
        form.addView(severitySeek)

        AlertDialog.Builder(this)
            .setTitle("Report ${type.label}")
            .setMessage("Your report helps keep Indore safe.")
            .setView(form)
            .setPositiveButton("Submit") { _, _ ->
                val report = IncidentReport(
                    type = type,
                    location = location,
                    description = input.text.toString(),
                    severity = severitySeek.progress + 1
                )
                
                IncidentRepository.submitReport(report) { success ->
                    if (success) {
                        Toast.makeText(this, "Report sent for admin verification.", Toast.LENGTH_LONG).show()
                        addIncidentMarkerToMap(report)
                    } else {
                        val apiUrl = com.safepath.indore.BuildConfig.SAFEPATH_API_URL
                        Toast.makeText(this, "Could not reach $apiUrl. Check your Wi-Fi and Laptop Firewall.", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Back") { _, _ ->
                startIncidentTypeSelection(location)
            }
            .show()
    }

    private fun addIncidentMarkerToMap(report: IncidentReport) {
        val map = googleMap ?: return
        val marker = map.addMarker(MarkerOptions()
            .position(report.location)
            .title("${report.type.label} (${report.status})")
            .snippet(report.description.ifBlank { "Crowd-sourced safety report" })
            .icon(BitmapDescriptorFactory.defaultMarker(
                if (report.status == "verified") BitmapDescriptorFactory.HUE_RED else BitmapDescriptorFactory.HUE_ORANGE
            ))
        )
        marker?.let { incidentMarkers.add(it) }
    }

    override fun onDestroy() {
        if (::voice.isInitialized) voice.shutdown()
        super.onDestroy()
    }

    private fun refreshCommunityIncidents() {
        if (!::riskCalc.isInitialized || googleMap == null) return
        IncidentRepository.getActiveIncidents { reports ->
            riskCalc.setCrowdReports(reports)
            incidentMarkers.forEach { it.remove() }
            incidentMarkers.clear()
            reports.forEach { addIncidentMarkerToMap(it) }
            destination?.let { regenerateRoutes(it) }
            updateUnsafeWarning()
        }
    }
}
