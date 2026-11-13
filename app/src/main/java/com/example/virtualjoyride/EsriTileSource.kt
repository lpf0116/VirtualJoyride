package com.example.virtualjoyride

import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.MapTileIndex

/**
 * Esri ArcGIS 的瓦片服务路径是 z/y/x（行在前、列在后），
 * 而 osmdroid 自带的 XYTileSource 固定拼 z/x/y，直接套用会请求到不存在的地址。
 *
 * 这个类只做一件事：把拼 URL 的那一步换成 Esri 的顺序。
 * 另外 Esri 的瓦片地址不需要 .png 后缀，浏览器实测 /z/y/x 就能直接拿到图。
 */
class EsriTileSource(
    name: String,
    private val service: String,
    minZoom: Int = 1,
    maxZoom: Int = 19
) : XYTileSource(
    name,
    minZoom,
    maxZoom,
    256,
    "",
    arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/$service")
) {

    override fun getTileURLString(pMapTileIndex: Long): String {
        val z = MapTileIndex.getZoom(pMapTileIndex)
        val y = MapTileIndex.getY(pMapTileIndex)
        val x = MapTileIndex.getX(pMapTileIndex)
        return "https://server.arcgisonline.com/ArcGIS/rest/services/$service/$z/$y/$x"
    }
}
