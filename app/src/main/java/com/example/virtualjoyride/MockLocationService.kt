package com.example.virtualjoyride

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray

/**
 * 前台服务：持续把 RoutePlayer 算出来的坐标写进系统定位服务。
 *
 * 关键点：App 必须先被用户在「开发者选项 → 选择模拟位置信息应用」里选中，
 * 否则 addTestProvider / setTestProviderLocation 会抛 SecurityException。
 */
class MockLocationService : Service() {

    companion object {
        const val ACTION_START = "com.example.virtualjoyride.action.START"
        const val ACTION_STOP = "com.example.virtualjoyride.action.STOP"
        const val EXTRA_ROUTE = "extra_route"
        const val EXTRA_SPEED_KMH = "extra_speed_kmh"
        const val EXTRA_LOOP = "extra_loop"

        private const val CHANNEL_ID = "mock_location"
        private const val NOTIFICATION_ID = 4711
        private const val TICK_MS = 500L

        private val PROVIDERS = arrayOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER
        )
    }

    private lateinit var locationManager: LocationManager
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var job: Job? = null
    private var providersReady = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown()
                return START_NOT_STICKY
            }

            ACTION_START -> {
                startInForeground()
                val route = intent?.getStringExtra(EXTRA_ROUTE).orEmpty()
                val speedKmh = intent?.getDoubleExtra(EXTRA_SPEED_KMH, 5.0) ?: 5.0
                val loop = intent?.getBooleanExtra(EXTRA_LOOP, true) ?: true
                startPlayback(route, speedKmh, loop)
            }

            else -> stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        job?.cancel()
        job = null
        teardownProviders()
        scope.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- playback

    private fun startPlayback(routeJson: String, speedKmh: Double, loop: Boolean) {
        job?.cancel()

        val points = parseRoute(routeJson)
        if (points.size < 2) {
            shutdown()
            return
        }
        if (!enableMockProviders()) {
            shutdown()
            return
        }

        // 先把拐角磨圆，再交给播放器。用户点的折线只用来定义「路过哪里」。
        val smoothed = PathSmoother.smooth(points)
        if (smoothed.size < 2) {
            shutdown()
            return
        }

        val speedMps = speedKmh.coerceIn(0.5, 60.0) / 3.6
        val player = RoutePlayer(smoothed, speedMps, loop)

        job = scope.launch {
            var last = SystemClock.elapsedRealtime()
            while (isActive) {
                delay(TICK_MS)
                val now = SystemClock.elapsedRealtime()
                val dt = (now - last) / 1000.0
                last = now

                val fix = player.advance(dt) ?: break
                pushFix(fix)
                if (player.isFinished()) break
            }
            shutdown()
        }
    }

    private fun pushFix(fix: Fix) {
        for (provider in PROVIDERS) {
            val location = Location(provider).apply {
                latitude = fix.lat
                longitude = fix.lon
                accuracy = 4f
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                speed = fix.speedMps.toFloat()
                bearing = fix.bearing
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    speedAccuracyMetersPerSecond = 0.4f
                    bearingAccuracyDegrees = 3f
                    verticalAccuracyMeters = 1.5f
                }
            }
            runCatching { locationManager.setTestProviderLocation(provider, location) }
        }
    }

    // ---------------------------------------------------------------- providers

    private fun enableMockProviders(): Boolean {
        var any = false
        for (provider in PROVIDERS) {
            val ok = runCatching {
                locationManager.addTestProvider(
                    provider,
                    false,  // requiresNetwork
                    false,  // requiresSatellite
                    false,  // requiresCell
                    false,  // hasMonetaryCost
                    true,   // supportsAltitude
                    true,   // supportsSpeed
                    true,   // supportsBearing
                    Criteria.POWER_LOW,
                    Criteria.ACCURACY_FINE
                )
                locationManager.setTestProviderEnabled(provider, true)
                true
            }.getOrDefault(false)
            any = any || ok
        }
        providersReady = any
        return any
    }

    private fun teardownProviders() {
        if (!providersReady) return
        for (provider in PROVIDERS) {
            runCatching { locationManager.setTestProviderEnabled(provider, false) }
            runCatching { locationManager.removeTestProvider(provider) }
        }
        providersReady = false
    }

    // ---------------------------------------------------------------- lifecycle

    private fun shutdown() {
        job?.cancel()
        job = null
        teardownProviders()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun parseRoute(json: String): List<RoutePoint> {
        if (json.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { index ->
                val obj = array.optJSONObject(index) ?: return@mapNotNull null
                RoutePoint(obj.getDouble("lat"), obj.getDouble("lon"))
            }
        }.getOrDefault(emptyList())
    }

    // ------------------------------------------------------------- notification

    private fun startInForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "模拟定位",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "保持模拟定位在后台运行"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val pending = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("正在遛弯")
            .setContentText("模拟定位运行中，点开可停止")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setContentIntent(pending)
            .build()
    }
}
