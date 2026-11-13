package com.example.virtualjoyride

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.AppOpsManager
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline
import java.io.File
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQ_PERMS = 1001
        private const val DEFAULT_LAT = 39.9042
        private const val DEFAULT_LON = 116.4074
        private const val ROUTE_FILE = "route.json"
    }

    private lateinit var mapView: MapView
    private lateinit var tvStatus: TextView
    private lateinit var etSpeed: EditText
    private lateinit var cbLoop: CheckBox
    private lateinit var btnStart: Button
    private lateinit var eventsOverlay: MapEventsOverlay

    private val points = mutableListOf<RoutePoint>()
    private var running = false
    private var satellite = false
    private var centeredOnce = false

    /** 卫星影像底图：同样是 WGS-84，OSM 拉不动的时候用它。 */
        private val satelliteSource = XYTileSource(
        "ArcGISSatellite",
        1,
        19,
        256,
        ".png",
        arrayOf(
            "https://server.arcgisonline.com/ArcGIS/rest/services/" +
                "World_Imagery/MapServer/tile/{z}/{y}/{x}"
        )
    )

    @Suppress("UNCHECKED_CAST")
    private val overlays: MutableList<Overlay>
        get() = mapView.overlays as MutableList<Overlay>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Configuration.getInstance().apply {
            userAgentValue = packageName
            osmdroidBasePath = File(cacheDir, "osmdroid")
            osmdroidTileCache = File(cacheDir, "osmdroid/tiles")
        }

        setContentView(R.layout.activity_main)

        mapView = findViewById(R.id.mapView)
        tvStatus = findViewById(R.id.tvStatus)
        etSpeed = findViewById(R.id.etSpeed)
        cbLoop = findViewById(R.id.cbLoop)
        btnStart = findViewById(R.id.btnStart)

        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(17.0)
        mapView.controller.setCenter(GeoPoint(DEFAULT_LAT, DEFAULT_LON))

        eventsOverlay = MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                if (p == null) return false
                addPoint(p)
                return true
            }

            override fun longPressHelper(p: GeoPoint?): Boolean = false
        })

        findViewById<Button>(R.id.btnUndo).setOnClickListener { undo() }
        findViewById<Button>(R.id.btnClear).setOnClickListener { clearAll() }
        findViewById<Button>(R.id.btnTile).setOnClickListener { toggleTile() }
        btnStart.setOnClickListener { if (running) stopMock() else startMock() }
        etSpeed.setOnEditorActionListener { _, _, _ ->
            refresh()
            false
        }

        loadRoute()
        render()
        requestPermissionsIfNeeded()
        refresh()
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
        if (!centeredOnce && hasLocationPermission()) {
            centerOnMe()
            centeredOnce = true
        }
        refresh()
    }

    override fun onPause() {
        mapView.onPause()
        super.onPause()
    }

    // ------------------------------------------------------------------- route

    private fun addPoint(p: GeoPoint) {
        points.add(RoutePoint(p.latitude, p.longitude))
        saveRoute()
        render()
        refresh()
    }

    private fun undo() {
        if (points.isNotEmpty()) {
            points.removeAt(points.size - 1)
            saveRoute()
            render()
            refresh()
        }
    }

    private fun clearAll() {
        points.clear()
        saveRoute()
        render()
        refresh()
    }

    /**
     * 坐标系说明：OSM 底图是 WGS-84，系统定位 API 要的也是 WGS-84，
     * 高德/百度这些 App 会自己在内部把 WGS-84 转成 GCJ-02 再画到它们的地图上。
     * 所以这里取点、写点全程用 WGS-84，不要把坐标预先转成 GCJ-02。
     */
    private fun render() {
        overlays.removeAll(overlays.filter { it is Marker || it is Polyline })
        overlays.remove(eventsOverlay)

        points.forEachIndexed { index, point ->
            val marker = Marker(mapView).apply {
                position = GeoPoint(point.lat, point.lon)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                title = "${index + 1}"
            }
            overlays.add(marker)
        }

        if (points.size >= 2) {
            val geoPoints = points.map { GeoPoint(it.lat, it.lon) }
            val line = Polyline()
            line.setPoints(geoPoints)
            line.outlinePaint.color = 0xFF1E88E5.toInt()
            line.outlinePaint.strokeWidth = 9f
            overlays.add(line)
        }

        // 事件层放最后：无论 osmdroid 是正序还是倒序遍历，点空白处都能加上点
        overlays.add(eventsOverlay)
        mapView.invalidate()
    }

    private fun toggleTile() {
        satellite = !satellite
        mapView.setTileSource(if (satellite) satelliteSource else TileSourceFactory.MAPNIK)
        toast(if (satellite) "卫星影像" else "OSM 街道图")
    }

    private fun saveRoute() {
        runCatching {
            val array = JSONArray()
            points.forEach { point ->
                array.put(JSONObject().put("lat", point.lat).put("lon", point.lon))
            }
            File(filesDir, ROUTE_FILE).writeText(array.toString())
        }
    }

    private fun loadRoute() {
        val file = File(filesDir, ROUTE_FILE)
        if (!file.exists()) return
        runCatching {
            val array = JSONArray(file.readText())
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                points.add(RoutePoint(obj.getDouble("lat"), obj.getDouble("lon")))
            }
        }
        if (points.isNotEmpty()) {
            mapView.controller.setCenter(GeoPoint(points[0].lat, points[0].lon))
        }
    }

    private fun routeJson(): String {
        val array = JSONArray()
        points.forEach { point ->
            array.put(JSONObject().put("lat", point.lat).put("lon", point.lon))
        }
        return array.toString()
    }

    // ------------------------------------------------------------------- 状态栏

    private fun refresh() {
        val speedKmh = etSpeed.text.toString().toDoubleOrNull() ?: 5.0
        val totalMeters = if (points.size >= 2) {
            (1 until points.size).sumOf { haversineMeters(points[it - 1], points[it]) }
        } else {
            0.0
        }
        val minutes = if (speedKmh > 0) totalMeters / (speedKmh / 3.6) / 60.0 else 0.0

        val status = buildString {
            append("点数 ${points.size}")
            if (totalMeters > 0) {
                append(" · 全程 ")
                append(String.format(Locale.US, "%.0f", totalMeters))
                append(" 米 · 约 ")
                append(String.format(Locale.US, "%.1f", minutes))
                append(" 分钟")
            }
            append(" · ")
            append(if (isMockAppSelected()) "模拟位置已授权" else "未选中模拟位置应用")
        }
        tvStatus.text = status
        btnStart.text = if (running) "停止" else "开始"
    }

    // ------------------------------------------------------------------- 启停

    private fun startMock() {
        if (points.size < 2) {
            toast("先在地图上点两个以上的点")
            return
        }
        if (!hasLocationPermission()) {
            requestPermissionsIfNeeded()
            return
        }
        if (!isMockAppSelected()) {
            showMockSetupDialog()
            return
        }

        val speedKmh = (etSpeed.text.toString().toDoubleOrNull() ?: 5.0).coerceIn(0.5, 60.0)
        val intent = Intent(this, MockLocationService::class.java).apply {
            action = MockLocationService.ACTION_START
            putExtra(MockLocationService.EXTRA_ROUTE, routeJson())
            putExtra(MockLocationService.EXTRA_SPEED_KMH, speedKmh)
            putExtra(MockLocationService.EXTRA_LOOP, cbLoop.isChecked)
        }
        ContextCompat.startForegroundService(this, intent)

        running = true
        refresh()
        toast("开始遛弯，打开地图 App 看蓝点")
    }

    private fun stopMock() {
        val intent = Intent(this, MockLocationService::class.java).apply {
            action = MockLocationService.ACTION_STOP
        }
        runCatching { startService(intent) }
        running = false
        refresh()
    }

    // ------------------------------------------------------------- 权限与自检

    private fun isMockAppSelected(): Boolean {
        val appOps = getSystemService(AppOpsManager::class.java)
        if (appOps != null) {
            val mode = appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_MOCK_LOCATION,
                Process.myUid(),
                packageName
            )
            if (mode == AppOpsManager.MODE_ALLOWED) return true
        }
        // 兜底：部分 ROM 的 appops 读数不准，直接看系统设置里选中的包名
        val selected = Settings.Secure.getString(contentResolver, "mock_location")
        return selected == packageName
    }

    private fun showMockSetupDialog() {
        AlertDialog.Builder(this)
            .setTitle("还差一步")
            .setMessage(
                "需要在「开发者选项 → 选择模拟位置信息应用」里选中「小区遛弯」，" +
                    "系统才会允许本应用写入坐标。\n\n" +
                    "如果开发者选项还没开：设置 → 关于手机 → 连续点击「版本号」7 次。"
            )
            .setPositiveButton("去开发者选项") { _, _ ->
                runCatching {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                }.onFailure { toast("这台手机没有开发者选项页面") }
            }
            .setNegativeButton("知道了", null)
            .show()
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    private fun requestPermissionsIfNeeded() {
        val missing = mutableListOf<String>()
        if (!hasLocationPermission()) {
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION)
            missing.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQ_PERMS)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMS && hasLocationPermission() && !centeredOnce) {
            centerOnMe()
            centeredOnce = true
        }
        refresh()
    }

    @SuppressLint("MissingPermission")
    private fun centerOnMe() {
        val manager = getSystemService(LOCATION_SERVICE) as LocationManager
        val last = runCatching {
            manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        }.getOrNull() ?: runCatching {
            manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        }.getOrNull()

        if (last != null && points.isEmpty()) {
            mapView.controller.setZoom(17.0)
            mapView.controller.setCenter(GeoPoint(last.latitude, last.longitude))
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
