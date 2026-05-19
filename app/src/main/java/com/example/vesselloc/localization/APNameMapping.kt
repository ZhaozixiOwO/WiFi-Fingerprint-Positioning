object ApNameMapping {

    private val apNameMap = mapOf(
        // Existing
        "BC:31:E2:38:60:60" to "T3/1F/103AP03",
        "BC:31:E2:38:63:A0" to "T3/1F/103AP05",
        "BC:31:E2:38:58:80" to "T3/1F/103AP06",
        "BC:31:E2:3B:5D:C0" to "T3/1F/103AP04",
        "BC:31:E2:3B:61:E0" to "T3/1F/103AP01",
        "BC:31:E2:38:A8:20" to "T3/1F/103AP02",

        // New
//        "BC:31:E2:3B:6E:20" to "T8/4F/DianTiTing",
//        "BC:31:E2:3B:6C:E0" to "T8/4F/JiaoliuPingTai",

        "B8:D4:F7:F2:53:90" to "T7/4F/405AP",
        "B8:D4:F7:F2:2D:B0" to "T7/4F/407AP",

        "B8:D4:F7:F2:5A:20" to "T7/5F/505AP",
        "B8:D4:F7:F2:20:60" to "T7/5F/503AP",
//        "BC:31:E2:38:BB:A0" to "T7/5F/DianTiTingAP",

        "B8:D4:F7:F2:31:70" to "T6/5F/502AP",
        "B8:D4:F7:F2:26:30" to "T6/4F/401AP",

        "BC:31:E2:38:17:40" to "T6/3F/303AP",
        "BC:31:E2:3B:65:40" to "T6/3F/302R8AP",
        "BC:31:E2:3B:66:00" to "T6/3F/302R2AP",

//        "BC:31:E2:3B:6D:80" to "T7/3F/DianTiTing",
        "B8:D4:F7:F2:2C:00" to "T7/3F/302AP1",
        "B8:D4:F7:F2:29:60" to "T7/3F/303AP",
        "B8:D4:F7:F2:1E:B0" to "T7/3F/301AP1",

//        "BC:31:E2:38:22:40" to "T8/3F/HouTiTingAP01",
        "BC:31:E2:3B:58:40" to "T8/3F/302WaiAP01",

        "B8:D4:F7:F2:5F:F0" to "T7/4F/401AP",
        "B8:D4:F7:F2:25:10" to "T7/4F/403AP",
        "B8:D4:F7:F2:1F:40" to "T7/4F/406AP",
//        "BC:31:E2:38:18:40" to "T7/4F/DianTiTingAP"
        "BC:31:E2:38:6B:60" to "T7/5F/504AP"
    )

    fun getName(bssid: String): String {
        return apNameMap[bssid.uppercase()] ?: "Unknown-AP"
    }
}
