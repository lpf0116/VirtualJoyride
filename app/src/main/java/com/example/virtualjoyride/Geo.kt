package com.example.virtualjoyride

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** 本地平面坐标：x 向东，y 向北，单位米。 */
data class Vec2(val x: Double, val y: Double)

operator fun Vec2.plus(other: Vec2) = Vec2(x + other.x, y + other.y)

operator fun Vec2.minus(other: Vec2) = Vec2(x - other.x, y - other.y)

operator fun Vec2.times(k: Double) = Vec2(x * k, y * k)

fun Vec2.length(): Double = hypot(x, y)

fun Vec2.normalizedOrNull(): Vec2? {
    val len = length()
    return if (len < 1e-9) null else Vec2(x / len, y / len)
}

/**
 * 以某个基准点为中心的等距投影。
 *
 * 小区尺度（几公里以内）用这个足够精确，误差远小于 1 米，
 * 但换来的是所有计算都变成简单的平面几何：算距离、算法线、加抖动都不用再碰经纬度。
 */
class LocalFrame(val lat0: Double, val lon0: Double) {

    private val metersPerDegLat = 110540.0
    private val metersPerDegLon = 111320.0 * cos(Math.toRadians(lat0))

    fun toMeters(point: RoutePoint): Vec2 = Vec2(
        (point.lon - lon0) * metersPerDegLon,
        (point.lat - lat0) * metersPerDegLat
    )

    fun toGeo(vec: Vec2): RoutePoint = RoutePoint(
        lat0 + vec.y / metersPerDegLat,
        lon0 + vec.x / metersPerDegLon
    )
}

/** 大圆距离，用于界面上的全程估算。 */
fun haversineMeters(a: RoutePoint, b: RoutePoint): Double {
    val dLat = Math.toRadians(b.lat - a.lat)
    val dLon = Math.toRadians(b.lon - a.lon)
    val h = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) *
        sin(dLon / 2) * sin(dLon / 2)
    return 2 * 6371000.0 * asin(sqrt(h))
}
