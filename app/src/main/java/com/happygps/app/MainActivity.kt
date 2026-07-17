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
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.floatingactionbutton.FloatingActionButton
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var mapView: MapView
    private lateinit var textStatus: TextView
    private lateinit var textWaypointsCount: TextView
    private lateinit var textSpeed: TextView
    private lateinit var seekBarSpeed: SeekBar
    private lateinit var btnStartStop: Button
    private lateinit var btnClear: Button
    private lateinit var fabLocate: FloatingActionButton
    private lateinit var editSearch: EditText
    private lateinit var btnSearch: ImageButton
    private lateinit var fabFavorite: FloatingActionButton

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
        editSearch = findViewById(R.id.editSearch)
        btnSearch = findViewById(R.id.btnSearch)
        fabFavorite = findViewById(R.id.fabFavorite)

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

        // Search action
        btnSearch.setOnClickListener { performSearch() }
        editSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                performSearch()
                true
            } else {
                false
            }
        }

        // Favorites action
        fabFavorite.setOnClickListener {
            showFavoritesDialog()
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

    private fun performSearch() {
        val query = editSearch.text.toString().trim()
        if (query.isEmpty()) return

        // 1. Check if coordinate (lat, lon)
        val coordRegex = Regex("""^(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)$""")
        val match = coordRegex.matchEntire(query)
        if (match != null) {
            val lat = match.groupValues[1].toDoubleOrNull()
            val lon = match.groupValues[2].toDoubleOrNull()
            if (lat != null && lon != null) {
                val point = GeoPoint(lat, lon)
                mapView.controller.animateTo(point)
                
                // If not simulating, prompt to add waypoint
                if (!isSimulating) {
                    AlertDialog.Builder(this)
                        .setMessage("已定位至座標 ($lat, $lon)\n是否新增為路徑點？")
                        .setPositiveButton("新增") { _, _ ->
                            waypoints.add(point)
                            updateWaypointsCountText()
                            redrawMapOverlays()
                            mockService?.updateWaypoints(waypoints)
                        }
                        .setNegativeButton("僅移動", null)
                        .show()
                }
                return
            }
        }

        // 2. Text Search using Nominatim API in background thread
        thread {
            try {
                val url = URL("https://nominatim.openstreetmap.org/search?q=" + URLEncoder.encode(query, "UTF-8") + "&format=json&limit=1")
                val connection = url.openConnection() as HttpURLConnection
                connection.setRequestProperty("User-Agent", "HappyGPS/1.0")
                connection.connectTimeout = 8000
                connection.readTimeout = 8000
                
                if (connection.responseCode == 200) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val jsonArray = JSONArray(response)
                    if (jsonArray.length() > 0) {
                        val first = jsonArray.getJSONObject(0)
                        val lat = first.getDouble("lat")
                        val lon = first.getDouble("lon")
                        val displayName = first.getString("display_name")
                        
                        runOnUiThread {
                            val point = GeoPoint(lat, lon)
                            mapView.controller.animateTo(point)
                            
                            if (!isSimulating) {
                                AlertDialog.Builder(this@MainActivity)
                                    .setTitle("搜尋結果")
                                    .setMessage("找到地點：$displayName\n\n是否新增為路徑點？")
                                    .setPositiveButton("新增") { _, _ ->
                                        waypoints.add(point)
                                        updateWaypointsCountText()
                                        redrawMapOverlays()
                                        mockService?.updateWaypoints(waypoints)
                                    }
                                    .setNegativeButton("僅移動", null)
                                    .show()
                            } else {
                                Toast.makeText(this@MainActivity, "找到地點：$displayName", Toast.LENGTH_LONG).show()
                            }
                        }
                    } else {
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "找不到該地點", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "搜尋失敗，伺服器錯誤: ${connection.responseCode}", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "搜尋出錯: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun getFavorites(): ArrayList<Pair<String, ArrayList<GeoPoint>>> {
        val list = ArrayList<Pair<String, ArrayList<GeoPoint>>>()
        val prefs = getSharedPreferences("happy_gps_prefs", Context.MODE_PRIVATE)
        val jsonStr = prefs.getString("favorites", null) ?: return list
        try {
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val name = obj.getString("name")
                val pointsArray = obj.getJSONArray("points")
                val points = ArrayList<GeoPoint>()
                for (j in 0 until pointsArray.length()) {
                    val pObj = pointsArray.getJSONObject(j)
                    points.add(GeoPoint(pObj.getDouble("lat"), pObj.getDouble("lon")))
                }
                list.add(Pair(name, points))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    private fun saveFavorites(list: ArrayList<Pair<String, ArrayList<GeoPoint>>>) {
        val prefs = getSharedPreferences("happy_gps_prefs", Context.MODE_PRIVATE)
        try {
            val array = JSONArray()
            for (item in list) {
                val obj = JSONObject()
                obj.put("name", item.first)
                val pointsArray = JSONArray()
                for (gp in item.second) {
                    val pObj = JSONObject()
                    pObj.put("lat", gp.latitude)
                    pObj.put("lon", gp.longitude)
                    pointsArray.put(pObj)
                }
                obj.put("points", pointsArray)
                array.put(obj)
            }
            prefs.edit().putString("favorites", array.toString()).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun showFavoritesDialog() {
        val favoritesList = getFavorites()
        val builder = AlertDialog.Builder(this)
        builder.setTitle("我的最愛路徑")

        val context = this
        val dpScale = resources.displayMetrics.density
        fun Int.dp() = (this * dpScale).toInt()

        val scrollView = ScrollView(context)
        val rootLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp(), 16.dp(), 16.dp(), 16.dp())
        }
        scrollView.addView(rootLayout)

        var dialog: AlertDialog? = null

        // 1. Save current route if available
        if (waypoints.isNotEmpty()) {
            val titleSave = TextView(context).apply {
                text = "儲存目前設定好的路徑"
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                setPadding(0, 0, 0, 8.dp())
            }
            rootLayout.addView(titleSave)

            val inputName = EditText(context).apply {
                hint = "請輸入路徑名稱"
                setHintTextColor(Color.parseColor("#888888"))
                setSingleLine(true)
                textSize = 14f
                setTextColor(Color.WHITE)
            }
            rootLayout.addView(inputName)

            val btnSave = Button(context).apply {
                text = "儲存目前路徑"
                setBackgroundColor(ContextCompat.getColor(context, R.color.accentBlue))
                setTextColor(Color.WHITE)
                textSize = 14f
                setOnClickListener {
                    val name = inputName.text.toString().trim()
                    if (name.isEmpty()) {
                        Toast.makeText(context, "請輸入名稱", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    if (favoritesList.any { it.first == name }) {
                        Toast.makeText(context, "已有相同名稱的路徑", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    val newPoints = ArrayList(waypoints)
                    favoritesList.add(Pair(name, newPoints))
                    saveFavorites(favoritesList)
                    Toast.makeText(context, "儲存成功", Toast.LENGTH_SHORT).show()
                    dialog?.dismiss()
                }
            }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 8.dp(), 0, 16.dp())
            }
            rootLayout.addView(btnSave, lp)

            val divider = View(context).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1.dp()).apply {
                    setMargins(0, 0, 0, 16.dp())
                }
                setBackgroundColor(Color.parseColor("#555555"))
            }
            rootLayout.addView(divider)
        }

        // 2. Saved routes list
        val titleList = TextView(context).apply {
            text = "已儲存的最愛路徑 (${favoritesList.size})"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 8.dp())
        }
        rootLayout.addView(titleList)

        if (favoritesList.isEmpty()) {
            val emptyText = TextView(context).apply {
                text = "尚無最愛路徑"
                textSize = 14f
                setTextColor(Color.parseColor("#CCCCCC"))
                gravity = Gravity.CENTER
                setPadding(0, 16.dp(), 0, 16.dp())
            }
            rootLayout.addView(emptyText)
        } else {
            for (fav in favoritesList) {
                val rowLayout = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, 4.dp(), 0, 4.dp())
                }

                val pathText = TextView(context).apply {
                    text = fav.first
                    textSize = 15f
                    setTextColor(Color.parseColor("#FFEB3B")) // Bright Yellow
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    setOnClickListener {
                        if (isSimulating) {
                            Toast.makeText(context, "模擬進行中，請先停止再載入路徑", Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        waypoints.clear()
                        waypoints.addAll(fav.second)
                        updateWaypointsCountText()
                        redrawMapOverlays()
                        mockService?.updateWaypoints(waypoints)
                        Toast.makeText(context, "已載入最愛路徑「${fav.first}」", Toast.LENGTH_SHORT).show()
                        if (waypoints.isNotEmpty()) {
                            mapView.controller.animateTo(waypoints[0])
                        }
                        dialog?.dismiss()
                    }
                }
                rowLayout.addView(pathText)

                val btnDelete = ImageButton(context).apply {
                    layoutParams = LinearLayout.LayoutParams(40.dp(), 40.dp())
                    val attrs = intArrayOf(android.R.attr.selectableItemBackgroundBorderless)
                    val typedArray = context.obtainStyledAttributes(attrs)
                    val backgroundResource = typedArray.getResourceId(0, 0)
                    setBackgroundResource(backgroundResource)
                    typedArray.recycle()
                    
                    setImageResource(android.R.drawable.ic_menu_delete)
                    setColorFilter(ContextCompat.getColor(context, R.color.accentRed))
                    contentDescription = "刪除"
                    setOnClickListener {
                        AlertDialog.Builder(context)
                            .setMessage("確定要刪除「${fav.first}」嗎？")
                            .setPositiveButton("確定") { _, _ ->
                                favoritesList.remove(fav)
                                saveFavorites(favoritesList)
                                Toast.makeText(context, "已刪除", Toast.LENGTH_SHORT).show()
                                dialog?.dismiss()
                                showFavoritesDialog()
                            }
                            .setNegativeButton("取消", null)
                            .show()
                    }
                }
                rowLayout.addView(btnDelete)

                rootLayout.addView(rowLayout)
            }
        }

        builder.setView(scrollView)
        builder.setNegativeButton("關閉", null)
        dialog = builder.show()
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
