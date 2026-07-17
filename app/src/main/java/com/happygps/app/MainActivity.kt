package com.happygps.app

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.floatingactionbutton.FloatingActionButton
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

class MainActivity : AppCompatActivity() {

    private lateinit var mapView: MapView
    private lateinit var textStatus: TextView
    private lateinit var textWaypointsCount: TextView
    private lateinit var textSpeed: TextView
    private lateinit var seekBarSpeed: SeekBar
    private lateinit var btnStartStop: Button
    private lateinit var btnClear: Button
    private lateinit var fabLocate: FloatingActionButton

    private val waypoints = ArrayList<GeoPoint>()
    private var speedKmH = 10
    private var isSimulating = false

    private var mockService: MockLocationService? = null
    private var isBound = false

    private lateinit var runnerMarker: Marker
    private lateinit var mapEventsOverlay: MapEventsOverlay

    // Permission launcher
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
        
        if (fineGranted || coarseGranted) {
            setupMap()
        } else {
            Toast.makeText(this, getString(R.string.permission_rationale), Toast.LENGTH_LONG).show()
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as MockLocationService.LocalBinder
            mockService = binder.getService()
            isBound = true

            // Send initial setup
            mockService?.updateWaypoints(waypoints)
            mockService?.updateSpeed(speedKmH)

            // Register listener
            mockService?.setLocationUpdateListener(object : MockLocationService.LocationUpdateListener {
                override fun onLocationUpdated(lat: Double, lng: Double, nextWaypointIndex: Int) {
                    runOnUiThread {
                        runnerMarker.position = GeoPoint(lat, lng)
                        if (!mapView.overlays.contains(runnerMarker)) {
                            mapView.overlays.add(runnerMarker)
                        }
                        mapView.invalidate()
                    }
                }

                override fun onError(message: String) {
                    runOnUiThread {
                        stopMockUi()
                        if (message.contains("開發人員選項") || message.contains("模擬位置應用程式")) {
                            showMockLocationSettingsDialog()
                        } else {
                            Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            })
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            mockService = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize osmdroid user agent before inflating layout
        Configuration.getInstance().userAgentValue = packageName
        
        setContentView(R.layout.activity_main)

        initViews()
        setupListeners()
        checkAndRequestPermissions()
    }

    private fun initViews() {
        mapView = findViewById(R.id.mapView)
        textStatus = findViewById(R.id.textStatus)
        textWaypointsCount = findViewById(R.id.textWaypointsCount)
        textSpeed = findViewById(R.id.textSpeed)
        seekBarSpeed = findViewById(R.id.seekBarSpeed)
        btnStartStop = findViewById(R.id.btnStartStop)
        btnClear = findViewById(R.id.btnClear)
        fabLocate = findViewById(R.id.fabLocate)

        // Setup speed text and progress
        seekBarSpeed.progress = speedKmH - 1
        textSpeed.text = getString(R.string.current_speed, speedKmH)

        // Initialize runner marker
        runnerMarker = Marker(mapView).apply {
            title = "當前模擬位置"
            icon = createRunnerDrawable()
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissions.isNotEmpty()) {
            requestPermissionLauncher.launch(permissions.toTypedArray())
        } else {
            setupMap()
        }
    }

    private fun setupMap() {
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setBuiltInZoomControls(false) // Hide default zoom buttons for cleaner UI
        mapView.setMultiTouchControls(true)  // Enable pinch-to-zoom

        val mapController = mapView.controller
        mapController.setZoom(16.0)
        
        // Default to Taipei 101 center
        val defaultPoint = GeoPoint(25.033611, 121.564444)
        mapController.setCenter(defaultPoint)

        // Click on map to add waypoints
        val mapEventsReceiver = object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                if (isSimulating) {
                    Toast.makeText(this@MainActivity, "模擬進行中，無法新增路徑點", Toast.LENGTH_SHORT).show()
                    return true
                }
                
                waypoints.add(p)
                updateWaypointsCountText()
                redrawMapOverlays()
                mockService?.updateWaypoints(waypoints)
                return true
            }

            override fun longPressHelper(p: GeoPoint): Boolean {
                return false
            }
        }

        mapEventsOverlay = MapEventsOverlay(mapEventsReceiver)
        mapView.overlays.add(mapEventsOverlay)
        mapView.invalidate()
    }

    private fun setupListeners() {
        // Speed slider
        seekBarSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                speedKmH = progress + 1
                textSpeed.text = getString(R.string.current_speed, speedKmH)
                mockService?.updateSpeed(speedKmH)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Clear button
        btnClear.setOnClickListener {
            if (isSimulating) {
                Toast.makeText(this, "模擬進行中，請先停止模擬再清除路徑", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            waypoints.clear()
            updateWaypointsCountText()
            redrawMapOverlays()
            mockService?.updateWaypoints(waypoints)
            Toast.makeText(this, "路徑已清除", Toast.LENGTH_SHORT).show()
        }

        // Start/Stop button
        btnStartStop.setOnClickListener {
            if (isSimulating) {
                stopMockLocation()
            } else {
                startMockLocation()
            }
        }

        // Center map FAB
        fabLocate.setOnClickListener {
            val centerPoint = if (isSimulating && runnerMarker.position != null) {
                runnerMarker.position
            } else if (waypoints.isNotEmpty()) {
                waypoints[0]
            } else {
                GeoPoint(25.033611, 121.564444)
            }
            mapView.controller.animateTo(centerPoint)
        }
    }

    private fun startMockLocation() {
        if (waypoints.isEmpty()) {
            Toast.makeText(this, getString(R.string.no_waypoints), Toast.LENGTH_SHORT).show()
            return
        }

        // Start Service
        val serviceIntent = Intent(this, MockLocationService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        // Bind Service
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        // Try launching simulation in service
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        handler.postDelayed({
            if (isBound && mockService != null) {
                val success = mockService!!.startSimulation()
                if (success) {
                    startMockUi()
                } else {
                    stopMockLocation()
                }
            }
        }, 300) // slight delay to ensure service binding completes
    }

    private fun stopMockLocation() {
        if (isBound) {
            mockService?.stopSimulation()
            unbindService(serviceConnection)
            isBound = false
        }
        
        // Stop service explicitly
        val serviceIntent = Intent(this, MockLocationService::class.java)
        stopService(serviceIntent)
        
        stopMockUi()
    }

    private fun startMockUi() {
        isSimulating = true
        textStatus.text = getString(R.string.status_running)
        textStatus.setTextColor(ContextCompat.getColor(this, R.color.accentGreen))
        btnStartStop.text = getString(R.string.stop_mock)
        btnStartStop.setBackgroundColor(ContextCompat.getColor(this, R.color.accentRed))
        
        // Center camera to runner
        if (waypoints.isNotEmpty()) {
            runnerMarker.position = waypoints[0]
            mapView.controller.animateTo(waypoints[0])
        }
        
        redrawMapOverlays()
    }

    private fun stopMockUi() {
        isSimulating = false
        textStatus.text = getString(R.string.status_stopped)
        textStatus.setTextColor(ContextCompat.getColor(this, R.color.textColorSecondary))
        btnStartStop.text = getString(R.string.start_mock)
        btnStartStop.setBackgroundColor(ContextCompat.getColor(this, R.color.accentGreen))
        
        // Remove runner marker
        if (mapView.overlays.contains(runnerMarker)) {
            mapView.overlays.remove(runnerMarker)
        }
        
        redrawMapOverlays()
    }

    private fun updateWaypointsCountText() {
        if (waypoints.isEmpty()) {
            textWaypointsCount.text = getString(R.string.no_waypoints)
        } else {
            textWaypointsCount.text = "設定了 ${waypoints.size} 個路徑點 (點擊標記可刪除)"
        }
    }

    private fun redrawMapOverlays() {
        mapView.overlays.clear()
        
        // Event overlay always at bottom
        mapView.overlays.add(mapEventsOverlay)

        // Draw waypoint markers
        waypoints.forEachIndexed { index, geoPoint ->
            val marker = Marker(mapView).apply {
                position = geoPoint
                title = "路徑點 ${index + 1}"
                icon = createNumberedMarkerDrawable(index + 1)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                
                setOnMarkerClickListener { m, _ ->
                    if (isSimulating) {
                        Toast.makeText(this@MainActivity, "模擬運行中，無法刪除路徑點", Toast.LENGTH_SHORT).show()
                    } else {
                        waypoints.remove(m.position)
                        updateWaypointsCountText()
                        redrawMapOverlays()
                        mockService?.updateWaypoints(waypoints)
                    }
                    true
                }
            }
            mapView.overlays.add(marker)
        }

        // Draw polyline loop connecting all points
        if (waypoints.size > 1) {
            val polyline = Polyline(mapView).apply {
                val points = ArrayList(waypoints)
                points.add(waypoints[0]) // Close the loop back to the first point
                setPoints(points)
                outlinePaint.color = ContextCompat.getColor(this@MainActivity, R.color.accentBlue)
                outlinePaint.strokeWidth = 6f
            }
            mapView.overlays.add(polyline)
        }

        // Keep runner marker on top if active
        if (isSimulating && runnerMarker.position != null) {
            mapView.overlays.add(runnerMarker)
        }

        mapView.invalidate()
    }

    // Dynamic bitmap marker generation
    private fun createNumberedMarkerDrawable(number: Int): Drawable {
        val size = 70
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ContextCompat.getColor(this@MainActivity, R.color.secondaryColor)
            style = Paint.Style.FILL
        }
        
        // Draw main cyan dot
        canvas.drawCircle(size / 2f, size / 2f, size / 2.2f, paint)
        
        // Draw white border
        paint.apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        canvas.drawCircle(size / 2f, size / 2f, size / 2.2f - 2, paint)
        
        // Draw number text
        paint.apply {
            color = Color.WHITE
            style = Paint.Style.FILL
            textSize = 28f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        val textHeight = paint.descent() - paint.ascent()
        val textOffset = textHeight / 2 - paint.descent()
        canvas.drawText(number.toString(), size / 2f, size / 2f + textOffset, paint)
        
        return BitmapDrawable(resources, bitmap)
    }

    // Dynamic runner dot generation
    private fun createRunnerDrawable(): Drawable {
        val size = 50
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        
        // Halo
        paint.color = Color.WHITE
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - 2f, paint)
        
        // Core indicator
        paint.color = ContextCompat.getColor(this, R.color.accentGreen)
        canvas.drawCircle(size / 2f, size / 2f, size / 3f, paint)
        
        return BitmapDrawable(resources, bitmap)
    }

    private fun showMockLocationSettingsDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_dialog_title)
            .setMessage(R.string.settings_dialog_message)
            .setCancelable(false)
            .setPositiveButton(R.string.go_to_settings) { _, _ ->
                try {
                    val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(this, "無法開啟開發人員選項，請手動前往系統設定", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
    }

    override fun onDestroy() {
        stopMockLocation()
        mapView.onPause()
        super.onDestroy()
    }
}
