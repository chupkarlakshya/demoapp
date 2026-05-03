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
import com.safepath.indore.data.RiskCalculator
import com.safepath.indore.databinding.ActivityMainBinding
import com.safepath.indore.routing.Route
import com.safepath.indore.routing.RouteGenerator
import com.safepath.indore.routing.RouteType
import com.safepath.indore.ui.FakeCallActivity
import com.safepath.indore.ui.LiveTrackActivity
import com.safepath.indore.ui.SosActivity
import com.safepath.indore.utils.Geocoder
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
    private var heatmapOverlay: com.google.android.gms.maps.model.TileOverlay? = null
    private var unsafeCircle: Circle? = null

    private var routes: List<Route> = emptyList()
    private var selected: RouteType = RouteType.SAFEST
    private var heatmapVisible = false
    private var safetyTimer: CountDownTimer? = null

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

        // Heavy work off the main thread.
        lifecycleScope.launch {
            val crimes = withContext(Dispatchers.IO) { CrimeDataLoader.load(this@MainActivity) }
            riskCalc = RiskCalculator(crimes)
            routeGen = RouteGenerator(riskCalc)
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

            // Center on Indore.
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(origin, 13f))
            requestLocation()
            updateUnsafeWarning()
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
        binding.btnFakeCall.setOnClickListener {
            startActivity(Intent(this, FakeCallActivity::class.java))
        }
        binding.btnLiveTrack.setOnClickListener {
            startActivity(Intent(this, LiveTrackActivity::class.java))
        }
        binding.btnContacts.setOnClickListener { showContactDialog() }
        binding.btnTimer.setOnClickListener { showTimerDialog() }

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
                "Unknown place. Try one of: Rajwada, Vijay Nagar, Palasia, Bhawarkuan, IIT Indore, Airport.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        destination = coord
        regenerateRoutes(coord)
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

    // -------------------------------------------------- Unsafe warning ------

    private fun updateUnsafeWarning() {
        if (!::riskCalc.isInitialized) return
        val map = googleMap ?: return
        val high = riskCalc.isHighRisk(origin)
        binding.warningChip.visibility = if (high) View.VISIBLE else View.GONE
        unsafeCircle?.remove()
        if (high) {
            unsafeCircle = map.addCircle(CircleOptions()
                .center(origin)
                .radius(250.0)
                .strokeColor(ContextCompat.getColor(this, R.color.risk_high))
                .strokeWidth(4f)
                .fillColor(0x33E53935))
        }
    }

    // -------------------------------------------- Google Maps deep link ----

    private fun launchGoogleMapsNavigation() {
        val route = routes.firstOrNull { it.type == selected } ?: return
        val pts = route.points
        if (pts.size < 2) return

        val start = pts.first()
        val end = pts.last()
        // Up to 8 intermediate waypoints (Maps URL supports up to ~9 reliably).
        val mids = pts.drop(1).dropLast(1)
        val sampled = if (mids.size <= 8) mids
                      else (1..8).map { mids[(it * mids.size) / 9] }

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
}
