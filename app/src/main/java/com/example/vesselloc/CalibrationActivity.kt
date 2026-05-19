package com.example.vesselloc

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.JavascriptInterface
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import Fingerprint
import FingerprintDatabase
import com.example.vesselloc.localization.ApRoomMapping
import java.util.Locale

class CalibrationActivity : BaseActivity() {

    private lateinit var wifiManager: WifiManager
    private lateinit var webView: WebView
    private lateinit var tvFpCount: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvLiveApSignals: TextView
    private lateinit var fpListContainer: LinearLayout
    private lateinit var scrollRoot: android.widget.ScrollView

    private var lastClickTimeMs = 0L
    private var latestScanResults: List<android.net.wifi.ScanResult> = emptyList()
    private var lastScanRequestMs = 0L
    private val SCAN_COOLDOWN_MS = 12_000L

    private val handler = Handler(Looper.getMainLooper())
    private var isActive = false
    private val AUTO_SCAN_TRIGGER_MS = 5_000L
    private val POLL_INTERVAL_MS = 1_000L
    private val STALE_RESULTS_MAX_AGE_MS = 12_000L
    private var lastAutoScanUptimeMs = 0L
    private var lastProcessedScanTimestampUs = 0L
    private var lastRefreshUptimeMs = 0L

    private val autoScanRunnable = object : Runnable {
        override fun run() {
            autoTriggerScan()
            if (isActive) handler.postDelayed(this, AUTO_SCAN_TRIGGER_MS)
        }
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            val results = runCatching { wifiManager.scanResults }.getOrNull() ?: emptyList()
            val maxTs = results.maxOfOrNull { it.timestamp } ?: 0L
            if (maxTs > 0L && maxTs > lastProcessedScanTimestampUs) {
                refreshScanResults()
            }
            if (isActive) handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            refreshScanResults()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_calibration)
        applySystemBarInsets(findViewById(R.id.root_container))

        FingerprintDatabase.init(this)

        wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager

        initViews()
        loadSvg()
        triggerScan()
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        runCatching {
            ContextCompat.registerReceiver(
                this, scanReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }.onFailure {
            runCatching { registerReceiver(scanReceiver, filter) }
        }
        if (isActive) return
        isActive = true
        handler.post(autoScanRunnable)
        handler.post(pollRunnable)
    }

    override fun onPause() {
        super.onPause()
        isActive = false
        handler.removeCallbacks(autoScanRunnable)
        handler.removeCallbacks(pollRunnable)
        runCatching { unregisterReceiver(scanReceiver) }
    }

    private fun initViews() {
        webView = findViewById(R.id.web_calibration_map)
        tvFpCount = findViewById(R.id.tv_fp_count)
        tvStatus = findViewById(R.id.tv_status)
        tvLiveApSignals = findViewById(R.id.tv_live_ap_signals)
        fpListContainer = findViewById(R.id.fingerprint_list_container)
        scrollRoot = findViewById(R.id.scroll_root)

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<View>(R.id.btn_clear_all).setOnClickListener {
            FingerprintDatabase.clear()
            webView.evaluateJavascript("clearAllMarkers();", null)
            reloadFingerprintList()
            updateStats()
            Toast.makeText(this, "All fingerprints cleared", Toast.LENGTH_SHORT).show()
        }

        findViewById<View>(R.id.btn_refresh_ap).setOnClickListener {
            triggerScan()
            Toast.makeText(this, "Scanning…", Toast.LENGTH_SHORT).show()
        }

        configureWebView()
        reloadFingerprintList()
        updateStats()
    }

    private fun configureWebView() {
        webView.setBackgroundColor(Color.TRANSPARENT)

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                injectExistingMarkers()
            }
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            loadWithOverviewMode = true
            useWideViewPort = true
            allowFileAccess = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                allowFileAccessFromFileURLs = true
                allowUniversalAccessFromFileURLs = true
            }
            cacheMode = WebSettings.LOAD_NO_CACHE
        }

        webView.addJavascriptInterface(SvgClickBridge(), "Android")

        webView.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_POINTER_DOWN -> {
                    scrollRoot.requestDisallowInterceptTouchEvent(true)
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

    inner class SvgClickBridge {
        @JavascriptInterface
        fun onSvgClick(x: Float, y: Float) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastClickTimeMs < 500) return
            lastClickTimeMs = now

            runOnUiThread { captureFingerprintAt(x, y) }
        }
    }

    private fun loadSvg() {
        val svg = assets.open("floorplan.svg").use { input ->
            input.readBytes().toString(Charsets.UTF_8)
        }
        val patched = patchSvgForViewport(svg)

        val html = """
        <!DOCTYPE html>
        <html>
          <head>
            <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=8.0, user-scalable=yes">
            <style>
              html, body {
                margin:0; padding:0;
                width:100%; height:100%;
                background: #000000;
                overflow:hidden;
              }
              .stage {
                width:100%;
                height:100%;
                display:flex;
                align-items:center;
                justify-content:center;
                background: #000000;
              }
              svg {
                width:100% !important;
                height:100% !important;
                background: transparent !important;
              }
              svg > rect:first-of-type {
                fill: transparent !important;
              }
              rect[fill="white"], rect[fill="WHITE"],
              rect[fill="#fff"], rect[fill="#FFF"],
              rect[fill="#ffffff"], rect[fill="#FFFFFF"] {
                fill: transparent !important;
              }
            </style>
          </head>
          <body>
            <div class="stage">
              $patched
            </div>
            <script>
              var markerCount = 0;
              var markers = {};

              function addMarker(id, cx, cy, color) {
                var svg = document.querySelector('svg');
                if (!svg) return;
                var existing = document.getElementById(id);
                if (existing) existing.remove();
                var circle = document.createElementNS('http://www.w3.org/2000/svg', 'circle');
                circle.setAttribute('id', id);
                circle.setAttribute('cx', cx);
                circle.setAttribute('cy', cy);
                circle.setAttribute('r', '6');
                circle.setAttribute('fill', color);
                circle.setAttribute('stroke', '#000000');
                circle.setAttribute('stroke-width', '1.5');
                circle.setAttribute('opacity', '0.85');
                svg.appendChild(circle);
                markers[id] = true;
                markerCount++;
              }

              function removeMarker(id) {
                var el = document.getElementById(id);
                if (el) { el.remove(); delete markers[id]; markerCount--; }
              }

              function clearAllMarkers() {
                Object.keys(markers).forEach(function(id) {
                  var el = document.getElementById(id);
                  if (el) el.remove();
                });
                markers = {};
                markerCount = 0;
              }

              (function() {
                document.addEventListener('click', function(e) {
                  var svg = document.querySelector('svg');
                  if (!svg) return;
                  var rect = svg.getBoundingClientRect();
                  var vb = svg.viewBox.baseVal;
                  var scaleX = vb.width > 0 ? rect.width / vb.width : 1;
                  var scaleY = vb.height > 0 ? rect.height / vb.height : 1;
                  var scale = Math.min(scaleX, scaleY);
                  var displayW = vb.width * scale;
                  var displayH = vb.height * scale;
                  var offsetX = (rect.width - displayW) / 2;
                  var offsetY = (rect.height - displayH) / 2;
                  var svgX = (e.clientX - rect.left - offsetX) / scale;
                  var svgY = (e.clientY - rect.top - offsetY) / scale;
                  Android.onSvgClick(svgX, svgY);
                });
              })();
            </script>
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

    // ——— Live scan / refresh ———

    private fun autoTriggerScan() {
        if (!wifiManager.isWifiEnabled) return
        if (!hasAllScanPermissions()) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastAutoScanUptimeMs < AUTO_SCAN_TRIGGER_MS) return
        lastAutoScanUptimeMs = now
        wifiManager.startScan()
    }

    private fun refreshScanResults() {
        val results = runCatching { wifiManager.scanResults }.getOrNull() ?: emptyList()
        latestScanResults = results
        val maxTs = results.maxOfOrNull { it.timestamp } ?: 0L
        if (maxTs > 0L) lastProcessedScanTimestampUs = maxTs
        lastRefreshUptimeMs = SystemClock.elapsedRealtime()
        updateLiveApSignals()
    }

    private fun triggerScan() {
        if (!wifiManager.isWifiEnabled) {
            Toast.makeText(this, "Wi-Fi is OFF", Toast.LENGTH_SHORT).show()
            return
        }
        if (!hasAllScanPermissions()) {
            Toast.makeText(this, "Need location permissions", Toast.LENGTH_SHORT).show()
            return
        }

        val now = SystemClock.elapsedRealtime()
        val sinceLast = now - lastScanRequestMs
        if (sinceLast < SCAN_COOLDOWN_MS) {
            val waitSec = ((SCAN_COOLDOWN_MS - sinceLast) / 1000).toInt() + 1
            Toast.makeText(this, "Scan throttled, wait ${waitSec}s", Toast.LENGTH_SHORT).show()
            return
        }
        lastScanRequestMs = now
        wifiManager.startScan()
        tvLiveApSignals.text = "Scanning… (waiting for results)"
    }

    private fun updateLiveApSignals() {
        if (latestScanResults.isEmpty()) {
            tvLiveApSignals.text = "No scan results yet. Tap refresh."
            return
        }

        val ageSec = if (lastRefreshUptimeMs > 0) {
            (SystemClock.elapsedRealtime() - lastRefreshUptimeMs) / 1000
        } else -1L
        val stale = ageSec > STALE_RESULTS_MAX_AGE_MS / 1000
        val ageStr = if (ageSec >= 0) {
            "updated ${ageSec}s ago" + if (stale) " ⚠ STALE" else ""
        } else ""

        val knownOnly = latestScanResults
            .filter { ApRoomMapping.isKnownAp(it.BSSID.uppercase(Locale.US)) }
            .sortedByDescending { it.level }

        if (knownOnly.isEmpty()) {
            tvLiveApSignals.text = "$ageStr\nNo known APs in range.\nAll APs: ${latestScanResults.size}"
            return
        }

        val sb = StringBuilder()
        sb.append("$ageStr\n")
        for (sr in knownOnly) {
            val mac = sr.BSSID.uppercase(Locale.US)
            val apName = ApNameMapping.getName(mac)
            val rssiBar = rssiBar(sr.level)
            sb.append("$apName  │  ${sr.level} dBm  $rssiBar\n")
        }
        tvLiveApSignals.text = sb.toString().trimEnd()
    }

    private fun rssiBar(dBm: Int): String {
        val bar = (dBm + 100).coerceIn(0, 60) / 6
        return "▌".repeat(bar.coerceAtLeast(1))
    }

    // ——— Fingerprint capture ———

    private fun captureFingerprintAt(x: Float, y: Float) {
        if (!ensureScanReady()) return

        // 直接使用 UI 当前显示的 scan 数据，确保标定点与 UI 对应同一时间节点
        val results = latestScanResults
        if (results.isEmpty()) {
            Toast.makeText(this, "No scan results yet. Wait for refresh.", Toast.LENGTH_SHORT).show()
            return
        }

        val rssiMap = results.associate {
            it.BSSID.uppercase(Locale.US) to it.level
        }

        val fp = Fingerprint(x, y, rssiMap)
        FingerprintDatabase.add(fp)

        val idx = FingerprintDatabase.size() - 1
        webView.post {
            webView.evaluateJavascript(
                "addMarker('fp-$idx', $x, $y, '#D4AF37');", null
            )
        }

        val knownCount = rssiMap.keys.count { ApNameMapping.getName(it) != "Unknown-AP" }
        tvStatus.text = "Captured at (${x.toInt()}, ${y.toInt()}) — ${rssiMap.size} APs, $knownCount known"
        reloadFingerprintList()
        updateStats()
    }

    private fun ensureScanReady(): Boolean {
        if (!wifiManager.isWifiEnabled) {
            Toast.makeText(this, "Wi-Fi is OFF", Toast.LENGTH_SHORT).show()
            return false
        }
        if (!hasAllScanPermissions()) {
            requestAllScanPermissions()
            Toast.makeText(this, "Need location permissions", Toast.LENGTH_SHORT).show()
            return false
        }
        return true
    }

    private fun injectExistingMarkers() {
        FingerprintDatabase.fingerprints.forEachIndexed { idx, fp ->
            webView.evaluateJavascript(
                "addMarker('fp-$idx', ${fp.x}, ${fp.y}, '#D4AF37');", null
            )
        }
    }

    private fun reloadFingerprintList() {
        fpListContainer.removeAllViews()
        FingerprintDatabase.fingerprints.forEachIndexed { index, fp ->
            val itemView = layoutInflater.inflate(R.layout.item_fingerprint, fpListContainer, false)

            val tvIndex = itemView.findViewById<TextView>(R.id.tv_fp_index)
            val tvSummary = itemView.findViewById<TextView>(R.id.tv_fp_summary)
            val apContainer = itemView.findViewById<LinearLayout>(R.id.ap_detail_container)
            val btnDelete = itemView.findViewById<TextView>(R.id.btn_delete)

            val knownCount = fp.rssiMap.keys.count { ApNameMapping.getName(it) != "Unknown-AP" }
            tvIndex.text = "#${index + 1}  (${fp.x.toInt()}, ${fp.y.toInt()})"
            tvSummary.text = "${fp.rssiMap.size} APs scanned, $knownCount known"

            // Populate top 6 APs sorted by RSSI (strongest first)
            fp.rssiMap.entries
                .sortedByDescending { it.value }
                .take(6)
                .forEach { (bssid, rssi) ->
                    val apName = ApNameMapping.getName(bssid)
                    val row = TextView(this).apply {
                        text = if (apName != "Unknown-AP") {
                            "$apName  │  $rssi dBm"
                        } else {
                            "$bssid  │  $rssi dBm"
                        }
                        textSize = 11f
                        setTextColor(if (apName != "Unknown-AP")
                            resources.getColor(R.color.text_gold, theme)
                        else
                            resources.getColor(R.color.text_secondary, theme))
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { bottomMargin = 2 }
                    }
                    apContainer.addView(row)
                }

            btnDelete.setOnClickListener {
                FingerprintDatabase.removeAt(index)
                webView.evaluateJavascript("removeMarker('fp-$index');", null)
                // Re-index remaining markers
                for (i in index until FingerprintDatabase.size()) {
                    val f = FingerprintDatabase.fingerprints[i]
                    webView.evaluateJavascript(
                        "addMarker('fp-$i', ${f.x}, ${f.y}, '#D4AF37');", null
                    )
                }
                // Remove the last marker ID (was shifted down)
                val oldLastIdx = FingerprintDatabase.size()
                webView.evaluateJavascript("removeMarker('fp-$oldLastIdx');", null)
                reloadFingerprintList()
                updateStats()
            }

            fpListContainer.addView(itemView)
        }
    }

    private fun updateStats() {
        val count = FingerprintDatabase.size()
        tvFpCount.text = "Fingerprints: $count"
        if (count == 0) {
            tvStatus.text = "Tap the map to add a fingerprint"
        }
    }

    // ——— Permissions (same pattern as HomeActivity) ———

    private var permissionRequestedOnce = false

    private fun hasAllScanPermissions(): Boolean {
        val fineOk = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarseOk = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) fineOk else coarseOk || fineOk
    }

    private fun requestAllScanPermissions() {
        if (permissionRequestedOnce) return
        permissionRequestedOnce = true
        val req = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        requestPermissions(req.distinct().toTypedArray(), 2001)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 2001) {
            tvStatus.text = "Permissions updated. Tap map to record."
        }
    }

    override fun onDestroy() {
        runCatching {
            webView.loadUrl("about:blank")
            webView.stopLoading()
            webView.removeAllViews()
            webView.destroy()
        }
        super.onDestroy()
    }
}
