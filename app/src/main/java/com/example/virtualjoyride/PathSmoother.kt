package com.example.virtualjoyride

import kotlin.math.ceil

/**
 * 把用户点出来的折线拐角磨圆。
 *
 * 做法是「切角 + 二次贝塞尔」：
 * 对每个中间顶点 B，沿着 BA 和 BC 各退一段距离 r 得到 P1、P2，
 * 然后用 B 作为控制点，把 P1→P2 画成一段二次贝塞尔曲线。
 *
 * 为什么不用 Catmull-Rom：那类插值会让曲线「冲」出拐角外侧，
 * 在直角路口会明显画出弧线外扩；切角法永远不会越界，视觉上就是正常的转弯。
 */
object PathSmoother {

    /** 拐角半径，单位米。小区里的路口大概就是这个尺度。 */
    private const val CORNER_RADIUS_M = 12.0

    /** 圆弧上采样点的间距，米。太小没意义，太大曲线会看出折线。 */
    private const val ARC_STEP_M = 1.5

    /** 小于这个距离的点直接丢掉，避免零长度线段算出 NaN。 */
    private const val MIN_POINT_GAP_M = 0.05

    fun smooth(waypoints: List<RoutePoint>): List<RoutePoint> {
        if (waypoints.size < 2) return waypoints

        val frame = LocalFrame(waypoints[0].lat, waypoints[0].lon)
        val pts = waypoints.map { frame.toMeters(it) }

        if (pts.size == 2) return waypoints

        val out = mutableListOf<Vec2>()
        out.add(pts.first())

        for (i in 1 until pts.size - 1) {
            val a = pts[i - 1]
            val b = pts[i]
            val c = pts[i + 1]

            val uIn = (b - a).normalizedOrNull()
            val uOut = (c - b).normalizedOrNull()
            if (uIn == null || uOut == null) {
                out.add(b)
                continue
            }

            val lenIn = (b - a).length()
            val lenOut = (c - b).length()

            // 半径不能超过两侧线段长度的一半，否则相邻两个圆角会打架
            val r = minOf(CORNER_RADIUS_M, lenIn * 0.45, lenOut * 0.45)

            val p1 = b - uIn * r
            val p2 = b + uOut * r

            val steps = ceil(2 * r / ARC_STEP_M).toInt().coerceIn(2, 48)
            for (s in 0..steps) {
                val t = s.toDouble() / steps
                val u = 1 - t
                val q = p1 * (u * u) + b * (2 * u * t) + p2 * (t * t)
                out.add(q)
            }
        }

        out.add(pts.last())

        // 去掉重复点，避免后续算方向时除零
        val cleaned = mutableListOf<Vec2>()
        for (p in out) {
            if (cleaned.isEmpty() || (p - cleaned.last()).length() > MIN_POINT_GAP_M) {
                cleaned.add(p)
            }
        }
        if (cleaned.size < 2) return waypoints

        return cleaned.map { frame.toGeo(it) }
    }
}
