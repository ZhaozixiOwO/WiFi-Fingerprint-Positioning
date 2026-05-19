package com.example.vesselloc


import ApNameMapping
import FingerprintDatabase
import ScanResultSample
import WifiFingerprintLocalizer
import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.example.vesselloc.localization.ApRoomMapping
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.util.Locale
import kotlin.math.max
import android.net.wifi.WifiInfo
import android.text.format.Formatter


class HomeActivity : BaseActivity() {

    private val SCAN_TRIGGER_MS = 5_000L
    private val MANUAL_SCAN_COOLDOWN_MS = 12_000L
    private val STALE_RESULTS_MAX_AGE_MS = 12_000L
    private val POLL_INTERVAL_MS = 1_000L
    private val AGE_TICK_MS = 1_000L

    private val handler = Handler(Looper.getMainLooper())
    private var isAutoRefreshing = false

    private val scanTriggerRunnable = object : Runnable {
        override fun run() {
            triggerScanOnce()
            if (isAutoRefreshing) handler.postDelayed(this, SCAN_TRIGGER_MS)
        }
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            val results = runCatching { wifiManager.scanResults }.getOrNull() ?: emptyList()
            val maxTs = results.maxOfOrNull { it.timestamp } ?: 0L
            if (maxTs > 0L && maxTs > lastProcessedScanTimestampUs) {
                showAndLocateFromScanResults()
            }
            if (isAutoRefreshing) handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    private var lastStartScanOk: Boolean? = null
    private var lastStartScanUptimeMs: Long = 0L
    private var startScanFailCount: Int = 0
    private var lastManualRefreshMs: Long = 0L
    private var lastProcessedScanTimestampUs: Long = 0L
    private var lastRefreshUptimeMs: Long = 0L

    private val ageTickRunnable = object : Runnable {
        override fun run() {
            if (lastRefreshUptimeMs > 0L) {
                val ageSec = (SystemClock.elapsedRealtime() - lastRefreshUptimeMs) / 1000
                tvSyncAge.text = "updated ${ageSec}s ago"
            }
            if (isAutoRefreshing) handler.postDelayed(this, AGE_TICK_MS)
        }
    }

    // ====== UI ======
    private lateinit var tvCurrentRoom: TextView
    private lateinit var tvCurrentFloor: TextView
    private lateinit var tvCurrentBuilding: TextView
    private lateinit var tvCoordinates: TextView
    private lateinit var tvFpCount: TextView
    private lateinit var tvWifiInfo: TextView
    private lateinit var tvSyncAge: TextView
    private lateinit var scrollRoot: android.widget.ScrollView

    // ✅ 两个 SVG WebView
    private lateinit var webPositionCard: WebView

    // ====== Wi-Fi / 定位 ======
    private lateinit var wifiManager: WifiManager
    private val localizer = WifiFingerprintLocalizer()
    private var currentRoomId: String = "Unknown"

    // ====== 稳定/防抖 ======
    private var lastStablePos: ParsedPos? = null
    private var lastStableScore: Double = 0.0

    private val wifiScanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            showAndLocateFromScanResults()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        FingerprintDatabase.init(this)

        val root = findViewById<View>(R.id.root_container)
        applySystemBarInsets(root)

        wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager

        initViews()
        tryScan()

        findViewById<FloatingActionButton>(R.id.fab_refresh).setOnClickListener {
            refreshPage()
        }

        findViewById<View>(R.id.btn_info).setOnClickListener {
            startActivity(Intent(this, InfoActivity::class.java))
        }

        findViewById<View>(R.id.btn_calibrate).setOnClickListener {
            startActivity(Intent(this, CalibrationActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        runCatching {
            ContextCompat.registerReceiver(
                this,
                wifiScanReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }.onFailure {
            runCatching { registerReceiver(wifiScanReceiver, filter) }
        }
        startAutoRefresh()
    }

    override fun onPause() {
        super.onPause()
        stopAutoRefresh()
        runCatching { unregisterReceiver(wifiScanReceiver) }
    }

    private fun startAutoRefresh() {
        if (isAutoRefreshing) return
        isAutoRefreshing = true
        handler.post(scanTriggerRunnable)
        handler.post(pollRunnable)
        handler.post(ageTickRunnable)
    }

    private fun stopAutoRefresh() {
        isAutoRefreshing = false
        handler.removeCallbacks(scanTriggerRunnable)
        handler.removeCallbacks(pollRunnable)
        handler.removeCallbacks(ageTickRunnable)
    }

    private fun initViews() {
        tvCurrentRoom = findViewById(R.id.tv_current_room)
        tvCurrentFloor = findViewById(R.id.tv_current_floor)
        tvCurrentBuilding = findViewById(R.id.tv_current_building)
        tvCoordinates = findViewById(R.id.tv_coordinates)
        tvFpCount = findViewById(R.id.tv_fingerprint_count)
        tvWifiInfo = findViewById(R.id.tv_wifi_info)
        tvSyncAge = findViewById(R.id.tv_sync_age)
        scrollRoot = findViewById(R.id.scroll_root)

        webPositionCard = findViewById(R.id.web_position_card)

        tvCurrentRoom.text = "Room: Locating."
        tvCurrentFloor.text = "Floor: Locating."
        tvCurrentBuilding.text = "Building: Locating."
        tvWifiInfo.text = "Wi-Fi: waiting."

        configureSvgWebView(webPositionCard)

        // ✅ 内联加载 assets 里的 SVG：初始大小/居中/比例更可控
        loadInlineSvgFromAssets(
            webView = webPositionCard,
            assetFileName = "floorplan.svg",
            backgroundHex = "#000000" // 你的图是黑底白线，背景明确为黑
        )
    }

    /**
     * WebView：启用缩放 + 完整手势（防 ScrollView 抢事件）
     */
    private fun configureSvgWebView(webView: WebView) {
        webView.setBackgroundColor(Color.TRANSPARENT)

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                // 不允许 svg 里点击链接跳走
                return true
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                tvWifiInfo.text = "SVG load error: ${error.errorCode} ${error.description}"
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                // 初始缩放交给 overview/wideViewport + CSS
                webView.setInitialScale(100)
            }
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true

            // ✅ 缩放体验（双指缩放像图片）
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false

            // ✅ 初始自适应
            loadWithOverviewMode = true
            useWideViewPort = true

            // ✅ 允许读取 assets
            allowFileAccess = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                allowFileAccessFromFileURLs = true
                allowUniversalAccessFromFileURLs = true
            }

            cacheMode = WebSettings.LOAD_NO_CACHE
        }

        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false
        webView.overScrollMode = View.OVER_SCROLL_NEVER

        // ✅ 关键：WebView 在 ScrollView 里，必须防止父容器拦截触摸（否则 pinch/drag 不顺）
        webView.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    scrollRoot.requestDisallowInterceptTouchEvent(true)
                }

                MotionEvent.ACTION_POINTER_DOWN -> {
                    // 多指开始：强制父容器不要抢
                    scrollRoot.requestDisallowInterceptTouchEvent(true)
                }

                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount >= 2) {
                        scrollRoot.requestDisallowInterceptTouchEvent(true)
                    }
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    scrollRoot.requestDisallowInterceptTouchEvent(false)
                }
            }
            v.onTouchEvent(event)
            true
        }
    }

    /**
     * ✅ 内联 SVG：强制居中、等比、铺满容器（看起来“大小合适”）
     */
    private fun loadInlineSvgFromAssets(
        webView: WebView,
        assetFileName: String,
        backgroundHex: String
    ) {
        val rawSvg = assets.open(assetFileName).use { input ->
            input.readBytes().toString(Charsets.UTF_8)
        }

        // 保留你的视口修正（防缩在角落）
        val svg = patchSvgForViewport(rawSvg)

        val html = """
        <!DOCTYPE html>
        <html>
          <head>
            <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=8.0, user-scalable=yes">
            <style>
              html, body {
                margin:0; padding:0;
                width:100%; height:100%;
                background: $backgroundHex;
                overflow:hidden;
              }

              .stage {
                width:100%;
                height:100%;
                display:flex;
                align-items:center;
                justify-content:center;
                background: $backgroundHex;
              }

              /* ✅ 让 SVG 像图片一样完整显示（等比居中） */
              svg {
                width:100% !important;
                height:100% !important;
                background: transparent !important;
              }

              /* ✅ 99% 情况：导出 SVG 的背景白底就是第一个 rect */
              svg > rect:first-of-type {
                fill: transparent !important;
              }

              /* ✅ 再兜底：把常见白底 rect 覆盖成透明（通常只影响背景层） */
              rect[fill="white"], rect[fill="WHITE"],
              rect[fill="#fff"], rect[fill="#FFF"],
              rect[fill="#ffffff"], rect[fill="#FFFFFF"] {
                fill: transparent !important;
              }
            </style>
          </head>
          <body>
            <div class="stage">
              $svg
            </div>
          </body>
        </html>
    """.trimIndent()

        webView.loadDataWithBaseURL(
            "file:///android_asset/",
            html,
            "text/html",
            "utf-8",
            null
        )
    }


    /**
     * 给 <svg ...> 补 preserveAspectRatio="xMidYMid meet"（若已有则不动）
     */
    private fun patchSvgForViewport(svg: String): String {
        val start = svg.indexOf("<svg")
        if (start < 0) return svg
        val end = svg.indexOf(">", startIndex = start)
        if (end < 0) return svg

        val head = svg.substring(start, end + 1)
        if (head.contains("preserveAspectRatio", ignoreCase = true)) return svg

        val newHead = head.dropLast(1) + """ preserveAspectRatio="xMidYMid meet">"""
        return svg.replace(head, newHead)
    }

    /**
     * ✅ 修正 SVG 自带白底（最常见：一个铺满画布的 rect fill="#fff/#ffffff/white"）
     * - forceBackground: 传 "#000000" 表示强制黑底；传 null 表示强制透明
     *
     * 覆盖的典型形态：
     *   <rect width="100%" height="100%" fill="#FFFFFF"/>
     *   <rect x="0" y="0" width="100%" height="100%" style="fill: white"/>
     *   <rect width="100%" height="100%" fill="white" />
     */
    private fun patchSvgBackground(svg: String, forceBackground: String?): String {
        // 将白色写法统一匹配（#fff / #ffffff / white / rgb(255,255,255)）
        val whiteFillRegex =
            """(fill\s*=\s*["']\s*(#fff|#ffffff|white)\s*["'])|(fill\s*:\s*(#fff|#ffffff|white)\b)|(fill\s*:\s*rgb\s*\(\s*255\s*,\s*255\s*,\s*255\s*\))""".toRegex(
                RegexOption.IGNORE_CASE
            )

        // 匹配“看起来像背景”的 rect：包含 width=100% & height=100%（顺序不固定）
        val rectTagRegex = """<rect\b[^>]*>""".toRegex(RegexOption.IGNORE_CASE)
        val rectSelfCloseRegex = """<rect\b[^>]*/\s*>""".toRegex(RegexOption.IGNORE_CASE)

        fun isFullCanvasRect(tag: String): Boolean {
            val w = """width\s*=\s*["']\s*100%\s*["']""".toRegex(RegexOption.IGNORE_CASE)
                .containsMatchIn(tag)
            val h = """height\s*=\s*["']\s*100%\s*["']""".toRegex(RegexOption.IGNORE_CASE)
                .containsMatchIn(tag)
            return w && h
        }

        fun rewriteFill(tag: String): String {
            val target = forceBackground ?: "none"
            var t = tag

            // 先处理 fill="..."
            t = t.replace("""fill\s*=\s*["'][^"']*["']""".toRegex(RegexOption.IGNORE_CASE)) { m ->
                val old = m.value
                if (whiteFillRegex.containsMatchIn(old)) """fill="$target"""" else old
            }

            // 再处理 style="...fill:...;"
            t = t.replace("""style\s*=\s*["'][^"']*["']""".toRegex(RegexOption.IGNORE_CASE)) { m ->
                var style = m.value
                if (whiteFillRegex.containsMatchIn(style)) {
                    // 把 fill 改成 target
                    style = style.replace(whiteFillRegex) { _ ->
                        if (m.value.contains(
                                "fill=",
                                ignoreCase = true
                            )
                        ) """fill="$target"""" else "fill:$target"
                    }
                    // 如果是 style 形式，简单一点：直接替换 fill:xxx
                    style = style.replace(
                        """fill\s*:\s*[^;"]+""".toRegex(RegexOption.IGNORE_CASE),
                        "fill:$target"
                    )
                }
                style
            }

            // 如果 rect 没有显式 fill/style，但你仍想强制底色（少见），可以选择加 fill
            if (!t.contains("fill=", ignoreCase = true) && !t.contains(
                    "fill:",
                    ignoreCase = true
                )
            ) {
                // 不强加，避免误伤；如果你想强加可以取消注释：
                // t = t.dropLast(1) + """ fill="$target">"""
            }

            return t
        }

        // 先处理自闭合 <rect ... />
        var out = svgSelfCloseReplace(svg, rectSelfCloseRegex) { tag ->
            if (isFullCanvasRect(tag) && whiteFillRegex.containsMatchIn(tag)) rewriteFill(tag) else tag
        }

        // 再处理非自闭合 <rect ...>
        out = svgSelfCloseReplace(out, rectTagRegex) { tag ->
            if (isFullCanvasRect(tag) && whiteFillRegex.containsMatchIn(tag)) rewriteFill(tag) else tag
        }

        // 额外：如果 SVG 里有 <style> 把背景写死成白，也尝试替换
        out = out.replace(
            """svg\s*\{\s*background\s*:\s*(#fff|#ffffff|white)[^}]*\}""".toRegex(RegexOption.IGNORE_CASE)
        ) {
            val bg = forceBackground ?: "transparent"
            "svg{background:$bg;}"
        }

        return out
    }

    /**
     * 小工具：对 regex 匹配到的 tag 做替换（避免写很多重复逻辑）
     */
    private fun svgSelfCloseReplace(
        src: String,
        regex: Regex,
        transform: (String) -> String
    ): String {
        return regex.replace(src) { mr ->
            transform(mr.value)
        }
    }


    private var permissionRequestedOnce = false

    private fun tryScan() {
        if (!wifiManager.isWifiEnabled) {
            tvWifiInfo.text = "Wi-Fi is OFF. Please enable Wi-Fi."
            setUnknownLocation()
            return
        }
        if (!isLocationEnabled()) {
            tvWifiInfo.text = "Location service is OFF. Please enable Location."
            setUnknownLocation()
            return
        }
        if (!hasAllScanPermissions()) {
            if (!permissionRequestedOnce) {
                permissionRequestedOnce = true
                requestAllScanPermissions()
            } else {
                tvWifiInfo.text = "Waiting for permissions..."
            }
            return
        }

        triggerScanOnce()
    }

    private fun triggerScanOnce() {
        if (!wifiManager.isWifiEnabled) {
            lastStartScanOk = null
            return
        }
        if (!isLocationEnabled()) {
            lastStartScanOk = null
            return
        }
        if (!hasAllScanPermissions()) {
            lastStartScanOk = null
            return
        }

        // Android throttling: skip if cooldown hasn't expired (auto loop)
        val now = SystemClock.elapsedRealtime()
        if (now - lastStartScanUptimeMs < SCAN_TRIGGER_MS) return

        val ok = wifiManager.startScan()
        lastStartScanOk = ok
        lastStartScanUptimeMs = now
        if (!ok) startScanFailCount++ else startScanFailCount = 0
    }

    private fun setUnknownLocation() {
        currentRoomId = "Unknown"
        tvCurrentRoom.text = "Room: Unknown"
        tvCurrentFloor.text = "Floor: Unknown"
        tvCurrentBuilding.text = "Building: Unknown"
    }

    private fun currentWifiInfo(): WifiInfo? {
        return runCatching { wifiManager.connectionInfo }.getOrNull()
    }

    private fun intToIp(ip: Int): String {
        return runCatching { Formatter.formatIpAddress(ip) }.getOrElse { "Unknown" }
    }

    private fun freqToChannel(freqMHz: Int): Int? {
        return when (freqMHz) {
            in 2412..2484 -> ((freqMHz - 2412) / 5) + 1
            in 5170..5895 -> (freqMHz - 5000) / 5
            in 5955..7115 -> (freqMHz - 5950) / 5
            else -> null
        }
    }

    private fun wifiBandLabel(freqMHz: Int): String {
        return when (freqMHz) {
            in 2400..2500 -> "2.4 GHz"
            in 4900..5900 -> "5 GHz"
            in 5925..7125 -> "6 GHz"
            else -> "Unknown"
        }
    }

    private fun wifiStandardLabel(standard: Int): String {
        return when (standard) {
            1 -> "Legacy"
            4 -> "11n"
            5 -> "11ac"
            6 -> "11ax"
            7 -> "11ad"
            8 -> "11be"
            else -> "Unknown"
        }
    }

    private fun cleanSsid(raw: String?): String {
        if (raw.isNullOrBlank()) return "Unknown"
        return raw.removePrefix("\"").removeSuffix("\"")
    }

    private fun appendCurrentConnectionDebug(sb: StringBuilder) {
        val info = currentWifiInfo()
        if (info == null) {
            sb.append("Current Connection: unavailable\n\n")
            return
        }

        val ssid = cleanSsid(info.ssid)
        val bssid = info.bssid ?: "Unknown"
        val rssi = info.rssi
        val freq = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) info.frequency else -1
        val channel = if (freq > 0) freqToChannel(freq) else null
        val linkSpeed = info.linkSpeed
        val ipAddr = intToIp(info.ipAddress)

        sb.append("===== CURRENT CONNECTION =====\n")
        sb.append("SSID: $ssid\n")
        sb.append("BSSID: $bssid\n")
        sb.append("IP: $ipAddr\n")

        runCatching {
            val dhcp = wifiManager.dhcpInfo
            if (dhcp != null) {
                sb.append("Gateway: ${intToIp(dhcp.gateway)}\n")
                sb.append("Netmask: ${intToIp(dhcp.netmask)}\n")
                sb.append("DNS1: ${intToIp(dhcp.dns1)}\n")
                sb.append("DNS2: ${intToIp(dhcp.dns2)}\n")
            }
        }

        sb.append("RSSI: $rssi dBm\n")

        if (freq > 0) {
            sb.append("Frequency: $freq MHz\n")
            sb.append("Band: ${wifiBandLabel(freq)}\n")
        }
        if (channel != null) {
            sb.append("Channel: $channel\n")
        }

        if (linkSpeed > 0) {
            sb.append("LinkSpeed: $linkSpeed Mbps\n")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (info.rxLinkSpeedMbps > 0) {
                sb.append("RxLinkSpeed: ${info.rxLinkSpeedMbps} Mbps\n")
            }
            if (info.txLinkSpeedMbps > 0) {
                sb.append("TxLinkSpeed: ${info.txLinkSpeedMbps} Mbps\n")
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            sb.append("WiFiStandard: ${wifiStandardLabel(info.wifiStandard)}\n")
        }

        sb.append("InterfaceName: N/A on Android public API\n")
        sb.append("Security: not reliably available from current connection API\n")
        sb.append("Noise: N/A on Android public API\n")
        sb.append("MCS: N/A on Android public API\n")
        sb.append("NSS: N/A on Android public API\n")
        sb.append("CountryCode: N/A on Android public API\n")
        sb.append("\n")
    }

    private fun showAndLocateFromScanResults() {
        try {
            val results = wifiManager.scanResults ?: emptyList()
            val allCount = results.size

            val newestTimestampUs = results.maxOfOrNull { it.timestamp } ?: 0L
            val newestAgeMs = if (newestTimestampUs > 0) {
                val nowUs = SystemClock.elapsedRealtimeNanos() / 1000L
                ((nowUs - newestTimestampUs).coerceAtLeast(0L)) / 1000L
            } else -1L

            val resultsStale = newestAgeMs > STALE_RESULTS_MAX_AGE_MS

            val wifiInfoBuilder = StringBuilder()
            wifiInfoBuilder.append("WiFiEnabled: ${wifiManager.isWifiEnabled}\n")
            wifiInfoBuilder.append("LocationEnabled: ${isLocationEnabled()}\n")
            wifiInfoBuilder.append("PermOK: ${hasAllScanPermissions()}\n")
            wifiInfoBuilder.append("startScanOk(last): $lastStartScanOk\n")
            wifiInfoBuilder.append("startScanFailCount: $startScanFailCount\n")
            if (lastStartScanUptimeMs > 0) {
                val sinceScan = SystemClock.elapsedRealtime() - lastStartScanUptimeMs
                wifiInfoBuilder.append("msSinceLastStartScan: $sinceScan\n")
            }
            wifiInfoBuilder.append("NewestResultAge(ms): $newestAgeMs")
            if (resultsStale) wifiInfoBuilder.append("  ⚠ STALE")
            wifiInfoBuilder.append("\n\n")

            appendCurrentConnectionDebug(wifiInfoBuilder)

            wifiInfoBuilder.append("===== SCAN SUMMARY =====\n")
            wifiInfoBuilder.append("ALL APs: $allCount\n")


            val knownResults = results
                .asSequence()
                .filter { ApRoomMapping.isKnownAp(it.BSSID.uppercase(Locale.US)) }
                .sortedByDescending { it.level }
                .toList()

            wifiInfoBuilder.append("KNOWN APs: ${knownResults.size}\n\n")

            knownResults.forEach {
                val mac = it.BSSID.uppercase(Locale.US)
                val apName = ApNameMapping.getName(mac)
                wifiInfoBuilder.append("Name: $apName\nMAC : $mac\nRSSI: ${it.level} dBm\n\n")
            }

            if (!resultsStale) {
                // ------- 房间推断（TopK + 加权投票 + 防抖） -------
                val inferred = inferPositionFromRoomAps(knownResults)
                if (inferred != null) {
                    tvCurrentRoom.text = "Room: ${inferred.room}"
                    tvCurrentFloor.text = "Floor: ${inferred.floor}"
                    tvCurrentBuilding.text = "Building: ${inferred.building}"
                    currentRoomId = "${inferred.building}-${inferred.floor}-${inferred.room}"
                } else {
                    setUnknownLocation()
                }
            }

            // 更新 debug 文本（保持滚动位置）
            val savedY = scrollRoot.scrollY
            tvWifiInfo.text = wifiInfoBuilder.toString()
            scrollRoot.post {
                val child = scrollRoot.getChildAt(0)
                val maxY = (child.height - scrollRoot.height).coerceAtLeast(0)
                scrollRoot.scrollTo(0, savedY.coerceIn(0, maxY))
            }

            if (!resultsStale) {
                // ------- Coordinate-level fingerprint positioning -------
                predictAndShowPosition(results)
                updateFingerprintCount()
            }

            if (newestTimestampUs > 0L) {
                lastProcessedScanTimestampUs = newestTimestampUs
            }
            lastRefreshUptimeMs = SystemClock.elapsedRealtime()
            tvSyncAge.text = "updated 0s ago"

        } catch (e: SecurityException) {
            tvWifiInfo.text = "Wi-Fi scan failed: permission denied (${e.message})"
            setUnknownLocation()
        } catch (e: Exception) {
            tvWifiInfo.text = "Wi-Fi scan error: ${e.message}"
            setUnknownLocation()
        }
    }

    // ===================== 房间 AP 的解析与推断 =====================

    private data class ParsedPos(val building: String, val floor: String, val room: String)

    private fun normalizeFloor(raw: String): String {
        val s = raw.trim().uppercase(Locale.US)
        // "1F" -> "F1"
        return if (Regex("""^\d+F$""").matches(s)) "F" + s.dropLast(1) else s
    }

    /**
     * 只接受“房间 AP”命名：
     *   103AP03 / 405AP / 302AP1 / 302R8AP ...
     */
    private fun parseRoomApName(apName: String): ParsedPos? {
        val parts = apName.trim().split("/")
        if (parts.size < 3) return null

        val building = parts[0].trim().uppercase(Locale.US)
        val floor = normalizeFloor(parts[1])
        val tag = parts[2].trim()

        val roomApRegex = Regex("""^(\d{3,4})(R\d+)?AP(\d+)?$""", RegexOption.IGNORE_CASE)
        val m = roomApRegex.matchEntire(tag) ?: return null

        val room = (m.groupValues.getOrNull(1) ?: "").trim()
        val roomSuffix = (m.groupValues.getOrNull(2) ?: "").trim().uppercase(Locale.US)
        val roomId = (room + roomSuffix).ifEmpty { return null }

        return ParsedPos(building = building, floor = floor, room = roomId)
    }

    private fun rssiToWeight(rssiDbm: Int): Double {
        val w = (rssiDbm + 100).coerceIn(5, 80)
        return w.toDouble()
    }

    private fun inferPositionFromRoomAps(knownResults: List<android.net.wifi.ScanResult>): ParsedPos? {
        if (knownResults.isEmpty()) return null

        val topK = knownResults.take(8)
        val scoreMap = HashMap<String, Double>()
        val posMap = HashMap<String, ParsedPos>()

        topK.forEach { sr ->
            val mac = sr.BSSID.uppercase(Locale.US)
            val apName = ApNameMapping.getName(mac)
            val parsed = parseRoomApName(apName) ?: return@forEach

            val key = "${parsed.building}|${parsed.floor}|${parsed.room}"
            posMap[key] = parsed
            scoreMap[key] = (scoreMap[key] ?: 0.0) + rssiToWeight(sr.level)
        }

        if (scoreMap.isEmpty()) return null

        var bestKey: String? = null
        var bestScore = -1.0
        for ((k, v) in scoreMap) {
            if (v > bestScore) {
                bestScore = v
                bestKey = k
            }
        }
        val bestPos = bestKey?.let { posMap[it] } ?: return null

        val prev = lastStablePos
        if (prev != null) {
            val same =
                prev.building == bestPos.building && prev.floor == bestPos.floor && prev.room == bestPos.room
            if (!same) {
                if (bestScore < lastStableScore * 1.10) {
                    return prev
                }
            }
        }

        lastStablePos = bestPos
        lastStableScore = max(bestScore, 1.0)
        return bestPos
    }

    // ===================== Coordinate-level positioning =====================

    private fun predictAndShowPosition(allScanResults: List<android.net.wifi.ScanResult>) {
        val allRssiMap: Map<String, Int> = allScanResults.associate {
            it.BSSID.uppercase(Locale.US) to it.level
        }

        if (allRssiMap.isNotEmpty() && FingerprintDatabase.fingerprints.isNotEmpty()) {
            val sample = ScanResultSample(allRssiMap)
            val pos = localizer.predictPosition(sample, FingerprintDatabase.fingerprints)
            if (pos != null) {
                injectPositionDot(pos.x, pos.y)
                tvCoordinates.text = String.format(
                    Locale.US, "Position: (%.0f, %.0f)", pos.x, pos.y
                )
                return
            }
        }
        clearPositionDot()
        tvCoordinates.text = "Position: --, --"
    }

    private fun injectPositionDot(x: Float, y: Float) {
        val cx = x.coerceIn(0f, 855f)
        val cy = y.coerceIn(0f, 815f)
        webPositionCard.evaluateJavascript(
            """
            (function(){
                var s=document.querySelector('svg');if(!s)return;
                var o=document.getElementById('pos-dot');if(o)o.remove();
                var n='http://www.w3.org/2000/svg';
                var c=document.createElementNS(n,'circle');
                c.setAttribute('id','pos-dot');
                c.setAttribute('cx','$cx');c.setAttribute('cy','$cy');
                c.setAttribute('r','7');
                c.setAttribute('fill','#00FF88');
                c.setAttribute('stroke','#000000');
                c.setAttribute('stroke-width','2');
                c.setAttribute('opacity','0.9');
                s.appendChild(c);
            })();
            """.trimIndent(), null
        )
    }

    private fun clearPositionDot() {
        webPositionCard.evaluateJavascript(
            """(function(){var o=document.getElementById('pos-dot');if(o)o.remove();})();""",
            null
        )
    }

    private fun updateFingerprintCount() {
        tvFpCount.text = "DB: ${FingerprintDatabase.size()} fingerprints"
    }

    // ===================== 权限/开关检查 =====================

    private fun isLocationEnabled(): Boolean {
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        return runCatching {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(
                LocationManager.NETWORK_PROVIDER
            )
        }.getOrDefault(false)
    }

    private fun hasAllScanPermissions(): Boolean {
        val fineOk = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarseOk = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            fineOk
        } else {
            coarseOk || fineOk
        }
    }

    private fun requestAllScanPermissions() {
        val req = mutableListOf<String>()
        req += Manifest.permission.ACCESS_FINE_LOCATION
        req += Manifest.permission.ACCESS_COARSE_LOCATION
        requestPermissions(req.distinct().toTypedArray(), 1001)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001) {
            tryScan()
        }
    }

    // ===================== 刷新按钮 =====================

    private fun refreshPage() {
        lastStablePos = null
        lastStableScore = 0.0

        if (!wifiManager.isWifiEnabled) {
            tvWifiInfo.text = "Wi-Fi is OFF. Please enable Wi-Fi."
            setUnknownLocation()
            return
        }
        if (!isLocationEnabled()) {
            tvWifiInfo.text = "Location service is OFF. Please enable Location."
            setUnknownLocation()
            return
        }
        if (!hasAllScanPermissions()) {
            if (!permissionRequestedOnce) {
                permissionRequestedOnce = true
                requestAllScanPermissions()
            } else {
                tvWifiInfo.text = "Waiting for permissions..."
            }
            return
        }

        val now = SystemClock.elapsedRealtime()
        val sinceLast = now - lastManualRefreshMs
        if (sinceLast < MANUAL_SCAN_COOLDOWN_MS) {
            val waitSec = ((MANUAL_SCAN_COOLDOWN_MS - sinceLast) / 1000).toInt() + 1
            Toast.makeText(this, "Scan throttled, wait ${waitSec}s", Toast.LENGTH_SHORT).show()
            showAndLocateFromScanResults()
            return
        }
        lastManualRefreshMs = now

        val ok = wifiManager.startScan()
        lastStartScanOk = ok
        lastStartScanUptimeMs = now
        if (!ok) {
            showAndLocateFromScanResults()
        }
        Toast.makeText(this, if (ok) "Scanning…" else "Scan request failed", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        runCatching {
            webPositionCard.loadUrl("about:blank")
            webPositionCard.stopLoading()
            webPositionCard.removeAllViews()
            webPositionCard.destroy()
        }
        super.onDestroy()
    }
}
