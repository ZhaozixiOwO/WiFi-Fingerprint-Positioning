data class Fingerprint(
    val x: Float,
    val y: Float,
    val rssiMap: Map<String, Int>           // BSSID -> RSSI (uppercase keys)
)
