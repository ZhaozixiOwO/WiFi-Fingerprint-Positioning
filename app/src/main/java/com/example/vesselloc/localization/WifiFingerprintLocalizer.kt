import android.graphics.PointF
import kotlin.math.pow

class WifiFingerprintLocalizer(
    private val k: Int = 3
) {

    private val positionHistory = ArrayDeque<PointF>()
    private val positionHistorySize = 5

    fun predictPosition(
        current: ScanResultSample,
        fingerprints: List<Fingerprint>
    ): PointF? {
        if (fingerprints.isEmpty()) return null

        val distances = fingerprints.map { fp ->
            val dist = calculateDistance(current.rssiMap, fp.rssiMap)
            fp to dist
        }

        val valid = distances.filter { it.second < Double.MAX_VALUE }
        val knn = valid.sortedBy { it.second }.take(k)
        if (knn.isEmpty()) return null

        var totalWeight = 0.0
        var weightedX = 0.0
        var weightedY = 0.0
        for ((fp, dist) in knn) {
            val weight = 1.0 / (dist + 1e-6)
            weightedX += weight * fp.x
            weightedY += weight * fp.y
            totalWeight += weight
        }

        val raw = PointF(
            (weightedX / totalWeight).toFloat(),
            (weightedY / totalWeight).toFloat()
        )

        return smoothPosition(raw)
    }

    private fun calculateDistance(
        a: Map<String, Int>,
        b: Map<String, Int>
    ): Double {
        val commonBssids = a.keys.intersect(b.keys)
        if (commonBssids.isEmpty()) return Double.MAX_VALUE

        return commonBssids.sumOf {
            (a[it]!! - b[it]!!).toDouble().pow(2)
        }
    }

    private fun smoothPosition(raw: PointF): PointF {
        if (positionHistory.size == positionHistorySize) positionHistory.removeFirst()
        positionHistory.addLast(raw)

        if (positionHistory.size == 1) return raw

        val avgX = positionHistory.sumOf { it.x.toDouble() } / positionHistory.size
        val avgY = positionHistory.sumOf { it.y.toDouble() } / positionHistory.size
        return PointF(avgX.toFloat(), avgY.toFloat())
    }
}
