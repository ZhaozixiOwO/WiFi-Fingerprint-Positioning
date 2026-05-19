package com.example.teachbuildingnav.localization

import kotlin.math.pow

/**
 * 简易 Wi-Fi 测距估计（RSSI -> distance）+ 最近AP挑选
 *
 * 注意：这是“粗略距离”，室内误差会很大（多径/遮挡/人体），但用来判断“谁最近”通常够用。
 */
object WifiRangingEstimator {

    /**
     * path-loss 模型：
     *   d = 10 ^ ((TxPowerAt1m - RSSI) / (10 * n))
     *
     * - txPowerAt1m：1米处RSSI（常取 -59 dBm 或你实测标定值）
     * - n：路径损耗指数（室内通常 2.0~4.0，越大表示衰减越快）
     */
    fun estimateDistanceMeters(
        rssiDbm: Int,
        txPowerAt1m: Int = -59,
        pathLossN: Double = 2.3
    ): Double {
        // 避免极端值（比如 0 或 -127）
        if (rssiDbm >= -10) return 0.1
        if (rssiDbm <= -110) return 50.0

        val exp = (txPowerAt1m - rssiDbm).toDouble() / (10.0 * pathLossN)
        return 10.0.pow(exp)
    }

    data class ApRangingResult(
        val bssid: String,
        val rssiDbm: Int,
        val distanceMeters: Double
    )

    /**
     * 输入 known AP 的 rssiMap，输出按“距离升序”的 TopK（最近的K个）
     */
    fun nearestAps(
        rssiMap: Map<String, Int>,
        topK: Int = 5,
        txPowerAt1m: Int = -59,
        pathLossN: Double = 2.3
    ): List<ApRangingResult> {
        if (rssiMap.isEmpty()) return emptyList()

        return rssiMap.entries
            .map { (bssid, rssi) ->
                ApRangingResult(
                    bssid = bssid,
                    rssiDbm = rssi,
                    distanceMeters = estimateDistanceMeters(rssi, txPowerAt1m, pathLossN)
                )
            }
            .sortedBy { it.distanceMeters }
            .take(topK)
    }
}
