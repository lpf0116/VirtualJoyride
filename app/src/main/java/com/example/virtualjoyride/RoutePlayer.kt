package com.example.virtualjoyride

import kotlin.math.atan2
import kotlin.math.sin
import kotlin.random.Random

/**
 * 路线上的一个拐点。坐标使用 WGS-84（OSM / 系统定位原生坐标）。
 */
data class RoutePoint(val lat: Double, val lon: Double)

/** 一次定位输出。 */
data class Fix(
    val lat: Double,
    val lon: Double,
    val bearing: Float,
    val speedMps: Double
)

/**
 * 把「一串点 + 速度」变成「每一帧的坐标」。
 *
 * 内部全部在本地平面坐标（米）里算，最后再转回经纬度：
 * - 距离用勾股定理，不用每次算大圆距离
 * - 法线方向直接由线段方向旋转 90° 得到
 * - 抖动按米加，不会因为纬度不同而变形
 */
class RoutePlayer(
    private val waypoints: List<RoutePoint>,
    private val speedMps: Double,
    private val loop: Boolean,
    private val jitterMeters: Double = DEFAULT_JITTER_M
) {
    companion object {
        /** 默认抖动幅度，米。真人步行时 GPS 横向误差大概就是 2～4 米。 */
        const val DEFAULT_JITTER_M = 2.5

        private const val TWO_PI = Math.PI * 2
    }

    private val frame = LocalFrame(
        waypoints.firstOrNull()?.lat ?: 0.0,
        waypoints.firstOrNull()?.lon ?: 0.0
    )

    private val path: List<Vec2> = waypoints.map { frame.toMeters(it) }

    private val cumulative = DoubleArray(path.size)
    private val total: Double

    /** 沿路线走过的距离，循环时会取模（用于定位）。 */
    private var traveled = 0.0

    /** 累计真实走过的距离，不取模（用于抖动相位，避免循环接缝处突变）。 */
    private var rawTraveled = 0.0

    private var done = false

    // 三个不同频率的相位，让抖动看起来像随机游走而不是来回摆
    private val rnd = Random(20261113)
    private val phaseA = rnd.nextDouble() * TWO_PI
    private val phaseB = rnd.nextDouble() * TWO_PI
    private val phaseC = rnd.nextDouble() * TWO_PI

    init {
        for (i in 1 until path.size) {
            cumulative[i] = cumulative[i - 1] + (path[i] - path[i - 1]).length()
        }
        total = cumulative.lastOrNull() ?: 0.0
    }

    fun advance(dtSeconds: Double): Fix? {
        if (path.size < 2 || total <= 0.0) {
            val only = waypoints.firstOrNull() ?: return null
            return Fix(only.lat, only.lon, 0f, 0.0)
        }
        if (done) return null

        val step = speedMps * dtSeconds
        traveled += step
        rawTraveled += step

        if (traveled >= total) {
            if (loop) {
                traveled %= total
            } else {
                traveled = total
                done = true
            }
        }

        // 找到 traveled 落在的线段下标 i（对应 path[i-1] -> path[i]）
        var i = 1
        while (i < path.size - 1 && cumulative[i] < traveled) i++

        val segStart = path[i - 1]
        val segVec = path[i] - segStart
        val segLen = cumulative[i] - cumulative[i - 1]
        val t = if (segLen <= 0.0) 0.0 else ((traveled - cumulative[i - 1]) / segLen).coerceIn(0.0, 1.0)

        val base = segStart + segVec * t
        val dir = segVec.normalizedOrNull()

        // 朝向：以正北为 0°，顺时针为正
        val bearing = if (dir != null) {
            ((Math.toDegrees(atan2(dir.x, dir.y)) + 360.0) % 360.0).toFloat()
        } else {
            0f
        }

        var pos = base
        if (dir != null && jitterMeters > 0.0) {
            // 左法线（东,北 坐标系里把方向逆时针转 90°）
            val normal = Vec2(-dir.y, dir.x)
            pos = base + normal * lateralOffset(rawTraveled)
        }

        // 报给系统的速度也带一点波动，真实 GPS 的 speed 从来不是常数
        val reportedSpeed = speedMps * (1.0 + 0.08 * sin(rawTraveled * 0.05 + phaseC))

        val geo = frame.toGeo(pos)
        return Fix(
            lat = geo.lat,
            lon = geo.lon,
            bearing = bearing,
            speedMps = reportedSpeed.coerceAtLeast(0.0)
        )
    }

    fun isFinished(): Boolean = done

    /**
     * 平滑伪随机横向偏移：两个低频正弦叠加，看起来像人走路时定位的缓慢漂移，
     * 而不是每个 tick 都随机跳一下那种「电子噪声」。
     */
    private fun lateralOffset(alongMeters: Double): Double {
        val slow = sin(alongMeters * 0.085 + phaseA)
        val fast = sin(alongMeters * 0.29 + phaseB)
        return jitterMeters * (0.7 * slow + 0.3 * fast)
    }
}
