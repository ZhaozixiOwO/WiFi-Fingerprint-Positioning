package com.example.vesselloc.localization

object ApRoomMapping {

    private val knownAps = setOf(
        // Existing
        "BC:31:E2:38:60:60",
        "BC:31:E2:38:63:A0",
        "BC:31:E2:38:58:80",
        "BC:31:E2:3B:5D:C0",
        "BC:31:E2:3B:61:E0",
        "BC:31:E2:38:A8:20",

        // New
//        "BC:31:E2:3B:6E:20",
//        "BC:31:E2:3B:6C:E0",

        "B8:D4:F7:F2:53:90",
        "B8:D4:F7:F2:2D:B0",

        "B8:D4:F7:F2:5A:20",
        "B8:D4:F7:F2:20:60",
//        "BC:31:E2:38:BB:A0",

        "B8:D4:F7:F2:31:70",
        "B8:D4:F7:F2:26:30",

        "BC:31:E2:38:17:40",
        "BC:31:E2:3B:65:40",
        "BC:31:E2:3B:66:00",

//        "BC:31:E2:3B:6D:80",
        "B8:D4:F7:F2:2C:00",
//        "BC:31:E2:38:22:40",
        "BC:31:E2:3B:58:40",
        "B8:D4:F7:F2:29:60",
        "B8:D4:F7:F2:1E:B0",

        "B8:D4:F7:F2:5F:F0",
        "B8:D4:F7:F2:25:10",
        "B8:D4:F7:F2:1F:40",
//        "BC:31:E2:38:18:40"
        "BC:31:E2:38:6B:60"
    )

    fun isKnownAp(bssid: String): Boolean {
        return knownAps.contains(bssid.uppercase())
    }
}
