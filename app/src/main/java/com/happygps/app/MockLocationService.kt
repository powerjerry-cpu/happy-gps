package com.happygps.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import org.osmdroid.util.GeoPoint
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MockLocationService : Service() {

    private val binder = LocalBinder()
    private lateinit var locationManager: LocationManager
    
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    
    @Volatile
    private var isSimulating = false
    
    private val waypoints = ArrayList<GeoPoint>()
    private var speedKmH = 10
    
    private var currentWaypointIndex = 0
    private var currentLat = 0.0
    private var currentLng = 0.0
    private var isInitialized = false

    private val CHANNEL_ID = "happy_gps_channel"
    private val NOTIFICATION_ID = 1001

    interface LocationUpdateListener {
        fun onLocationUpdated(lat: Double, lng: Double, nextWaypointIndex: Int)
        fun onError(message: String)
    }

    private var listener: LocationUpdateListener? = null

    inner class LocalBinder : Binder() {
        fun getService(): MockLocationService = this@MockLocationService
    }

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification("服務已啟動，等待設定路徑點...")
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, 
                notification, 
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    fun setLocationUpdateListener(listener: LocationUpdateListener) {
        this.listener = listener
    }

    fun updateWaypoints(newWaypoints: List<GeoPoint>) {
        synchronized(waypoints) {
            waypoints.clear()
            waypoints.addAll(newWaypoints)
            if (waypoints.isNotEmpty() && !isInitialized) {
                currentLat = waypoints[0].latitude
                currentLng = waypoints[0].longitude
                currentWaypointIndex = if (waypoints.size > 1) 1 else 0
                isInitialized = true
            }
        }
    }

    fun updateSpeed(speed: Int) {
        this.speedKmH = speed
        updateNotification("模擬速度：$speedKmH km/h")
    }

    fun startSimulation(): Boolean {
        if (waypoints.isEmpty()) {
            listener?.onError("請先設定至少一個路徑點")
            return false
        }
        
        if (isSimulating) return true
        
        try {
            setupMockProviders()
        } catch (e: SecurityException) {
            Log.e("MockLocationService", "Permission error setting up providers", e)
            listener?.onError("請先至開發人員選項將此 App 設為模擬位置應用程式")
            return false
        } catch (e: Exception) {
            Log.e("MockLocationService", "Error setting up providers", e)
            listener?.onError("初始化模擬定位失敗：" + e.message)
            return false
        }

        isSimulating = true
        executor.execute {
            simulationLoop()
        }
        
        updateNotification("正在模擬路徑行走... 速度：$speedKmH km/h")
        return true
    }

    fun stopSimulation() {
        isSimulating = false
        isInitialized = false
        removeMockProviders()
        updateNotification("模擬已停止")
        stopSelf()
    }

    private fun setupMockProviders() {
        // Setup GPS Provider
        try {
            locationManager.removeTestProvider(LocationManager.GPS_PROVIDER)
        } catch (e: Exception) {}
        locationManager.addTestProvider(
            LocationManager.GPS_PROVIDER,
            false, true, false, false, true, true, true,
            Criteria.POWER_LOW, Criteria.ACCURACY_FINE
        )
        locationManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)

        // Setup Network Provider
        try {
            locationManager.removeTestProvider(LocationManager.NETWORK_PROVIDER)
        } catch (e: Exception) {}
        locationManager.addTestProvider(
            LocationManager.NETWORK_PROVIDER,
            true, false, true, false, false, false, false,
            Criteria.POWER_LOW, Criteria.ACCURACY_COARSE
        )
        locationManager.setTestProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
    }

    private fun removeMockProviders() {
        try {
            locationManager.removeTestProvider(LocationManager.GPS_PROVIDER)
        } catch (e: Exception) {}
        try {
            locationManager.removeTestProvider(LocationManager.NETWORK_PROVIDER)
        } catch (e: Exception) {}
    }

    private fun simulationLoop() {
        while (isSimulating) {
            val startTime = SystemClock.elapsedRealtime()
            
            var nextPoint: GeoPoint
            var pointsCount: Int
            
            synchronized(waypoints) {
                pointsCount = waypoints.size
                if (pointsCount == 0) {
                    isSimulating = false
                    return
                }
                if (currentWaypointIndex >= pointsCount) {
                    currentWaypointIndex = 0
                }
                nextPoint = waypoints[currentWaypointIndex]
            }

            if (pointsCount == 1) {
                // Stay at the single waypoint
                currentLat = nextPoint.latitude
                currentLng = nextPoint.longitude
            } else {
                // Move towards the target waypoint
                val speedMps = speedKmH / 3.6 // convert km/h to meters/second
                // We update every 1 second, so distance to move is speedMps * 1.0
                val moveDistance = speedMps * 1.0 
                
                val result = calculateNewPosition(currentLat, currentLng, nextPoint.latitude, nextPoint.longitude, moveDistance)
                currentLat = result.first
                currentLng = result.second
                
                // If we reached the target waypoint, move to the next one
                if (currentLat == nextPoint.latitude && currentLng == nextPoint.longitude) {
                    synchronized(waypoints) {
                        currentWaypointIndex = (currentWaypointIndex + 1) % waypoints.size
                    }
                }
            }

            // Post mock locations to system
            try {
                publishMockLocation(currentLat, currentLng)
                
                // Notify UI on Main Thread
                mainHandler.post {
                    listener?.onLocationUpdated(currentLat, currentLng, currentWaypointIndex)
                }
            } catch (e: SecurityException) {
                mainHandler.post {
                    listener?.onError("模擬位置權限已被取消，請重新設定")
                }
                isSimulating = false
            } catch (e: Exception) {
                Log.e("MockLocationService", "Error publishing mock location", e)
            }

            // Sleep to make loop run roughly every 1000ms
            val elapsedTime = SystemClock.elapsedRealtime() - startTime
            val sleepTime = 1000 - elapsedTime
            if (sleepTime > 0) {
                try {
                    Thread.sleep(sleepTime)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }
    }

    private fun publishMockLocation(lat: Double, lng: Double) {
        val currentTime = System.currentTimeMillis()
        val elapsedNanos = SystemClock.elapsedRealtimeNanos()

        // GPS Provider Mock Location
        val gpsLoc = Location(LocationManager.GPS_PROVIDER).apply {
            latitude = lat
            longitude = lng
            altitude = 10.0
            accuracy = 3.0f
            time = currentTime
            elapsedRealtimeNanos = elapsedNanos
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                speed = (speedKmH / 3.6).toFloat()
                bearing = 0.0f
            }
        }
        locationManager.setTestProviderLocation(LocationManager.GPS_PROVIDER, gpsLoc)

        // Network Provider Mock Location
        val netLoc = Location(LocationManager.NETWORK_PROVIDER).apply {
            latitude = lat
            longitude = lng
            altitude = 10.0
            accuracy = 25.0f
            time = currentTime
            elapsedRealtimeNanos = elapsedNanos
        }
        locationManager.setTestProviderLocation(LocationManager.NETWORK_PROVIDER, netLoc)
    }

    // Haversine / Linear Interpolation
    private fun calculateNewPosition(
        startLat: Double, startLng: Double,
        endLat: Double, endLng: Double,
        distanceToMoveMeters: Double
    ): Pair<Double, Double> {
        val R = 6371000.0 // Earth radius in meters
        val lat1 = Math.toRadians(startLat)
        val lon1 = Math.toRadians(startLng)
        val lat2 = Math.toRadians(endLat)
        val lon2 = Math.toRadians(endLng)

        val dLat = lat2 - lat1
        val dLon = lon2 - lon1

        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(lat1) * Math.cos(lat2) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        val totalDistanceMeters = R * c

        if (totalDistanceMeters <= distanceToMoveMeters) {
            return Pair(endLat, endLng)
        }

        val fraction = distanceToMoveMeters / totalDistanceMeters
        val interpolatedLat = startLat + fraction * (endLat - startLat)
        val interpolatedLng = startLng + fraction * (endLng - startLng)
        
        return Pair(interpolatedLat, interpolatedLng)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Happy GPS Mocking Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(contentText: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .setColor(resources.getColor(R.color.primaryColor, null))
            .build()
    }

    private fun updateNotification(text: String) {
        val notification = createNotification(text)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        stopSimulation()
        super.onDestroy()
    }
}
