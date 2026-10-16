package com.pcontrol.loopbrowser

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.MotionEvent
import android.os.SystemClock
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.view.inputmethod.EditorInfo
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var browser: WebView
    private lateinit var address: EditText
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var selectorInput: EditText
    private lateinit var loopButton: Button
    private lateinit var desktopButton: Button
    private lateinit var automationToggle: Button
    private lateinit var automationPanel: LinearLayout
    private lateinit var displayRow: LinearLayout
    private lateinit var addressBar: LinearLayout
    private lateinit var actionsRow: LinearLayout
    private lateinit var fullscreenButton: Button
    private lateinit var stripToggleButton: Button
    private var stripHidden = false
    private var fullscreenMode = false
    private var desktopMode = false
    @Volatile private var browserZoomFactor = 1.0f
    private var mobileUserAgent: String = ""
    private val loopHandler = Handler(Looper.getMainLooper())
    @Volatile private var loopRunning = false
    private var observeAutoStarted = false
    @Volatile private var pageLoading = false
    @Volatile private var cachedNavigationStatus: String = "idle"
    @Volatile private var cachedNavigationError: String = ""
    @Volatile private var cachedNavigationStartedAt: Long = 0L
    @Volatile private var cachedNavigationFinishedAt: Long = 0L
    private val navigationTimeoutMs = 15000L
    @Volatile private var cachedBrowserUrl: String = ""
    @Volatile private var cachedBrowserTitle: String = ""
    @Volatile private var cachedBrowserWidth: Int = 0
    @Volatile private var cachedBrowserHeight: Int = 0
    @Volatile private var cachedCanGoBack: Boolean = false
    @Volatile private var cachedCanGoForward: Boolean = false
    @Volatile private var latestCaptureFileName: String = ""
    @Volatile private var latestCaptureOk: Boolean = false
    @Volatile private var latestCaptureTimestamp: Long = 0L
    private var bridgeStatus: TextView? = null
    private var browserBridge: BrowserBridge? = null
    private var loopStep = 0
    @Volatile private var lastAgentAction = "none"
    @Volatile private var lastAgentActionStatus = "idle"
    @Volatile private var lastAgentActionAt = 0L
    private val allowedAgentActions = setOf("observe", "navigate", "back", "forward", "reload", "capture", "desktop_on", "desktop_off", "stop")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        configureBrowser()
        startBrowserBridge()
        browser.loadUrl("https://example.com")
    }

    private fun buildUi() {
        window.statusBarColor = 0xFFF5F7FB.toInt()
        window.navigationBarColor = 0xFFF5F7FB.toInt()
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
            View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF5F7FB.toInt())
            setOnApplyWindowInsetsListener { view, insets ->
                view.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
                insets
            }
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(6.dp(), 1.dp(), 6.dp(), 1.dp())
        }
        address = EditText(this).apply {
            setSingleLine(true)
            hint = "Enter URL or search"
            setText("https://example.com")
            imeOptions = EditorInfo.IME_ACTION_GO
            textSize = 15f
            setPadding(8.dp(), 0, 6.dp(), 0)
            layoutParams = LinearLayout.LayoutParams(0, 40.dp(), 1f).apply {
                marginEnd = 4.dp()
            }
        }
        bar.addView(address)
        bar.addView(Button(this).apply {
            text = "Go"
            isAllCaps = false
            minWidth = 56.dp()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, 38.dp()
            )
            setOnClickListener { navigate(address.text.toString()) }
        })
        addressBar = bar
        root.addView(bar)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(6.dp(), 0, 6.dp(), 1.dp())
        }
        fun addAction(label: String, action: () -> Unit) {
            actions.addView(Button(this).apply {
                text = label
                isAllCaps = false
                minWidth = 0
                setPadding(4.dp(), 0, 4.dp(), 0)
                layoutParams = LinearLayout.LayoutParams(0, 34.dp(), 1f).apply {
                    marginEnd = 3.dp()
                }
                setOnClickListener { action() }
            })
        }
        addAction("Back") { if (browser.canGoBack()) browser.goBack() }
        addAction("Forward") { if (browser.canGoForward()) browser.goForward() }
        addAction("Reload") { browser.reload() }
        addAction("Capture") { captureViewport() }
        actionsRow = actions
        root.addView(actions)

        displayRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(6.dp(), 0, 6.dp(), 2.dp())
        }
        desktopButton = Button(this).apply {
            text = "Desktop: Off"
            isAllCaps = false
            textSize = 12f
            setPadding(2.dp(), 0, 2.dp(), 0)
            layoutParams = LinearLayout.LayoutParams(0, 38.dp(), 1f).apply {
                marginEnd = 4.dp()
            }
            setOnClickListener { toggleDesktopMode() }
        }
        displayRow.addView(desktopButton)
        automationToggle = Button(this).apply {
            text = "Tools"
            isAllCaps = false
            textSize = 12f
            setPadding(2.dp(), 0, 2.dp(), 0)
            layoutParams = LinearLayout.LayoutParams(0, 38.dp(), 1f)
            setOnClickListener {
                val showing = automationPanel.visibility == View.GONE
                automationPanel.visibility = if (showing) View.VISIBLE else View.GONE
                status.visibility = if (showing || loopRunning) View.VISIBLE else View.GONE
                text = if (showing) "Hide tools" else "Tools"
            }
        }
        displayRow.addView(automationToggle)
        fullscreenButton = Button(this).apply {
            text = "Full"
            isAllCaps = false
            textSize = 12f
            setPadding(2.dp(), 0, 2.dp(), 0)
            layoutParams = LinearLayout.LayoutParams(0, 38.dp(), 1f).apply {
                marginStart = 4.dp()
            }
            setOnClickListener { toggleFullscreen() }
        }
        displayRow.addView(fullscreenButton)
        root.addView(displayRow)

        automationPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(6.dp(), 0, 6.dp(), 1.dp())
            visibility = View.GONE
        }
        selectorInput = EditText(this).apply {
            setSingleLine(true)
            hint = "Optional CSS selector to click each cycle"
            textSize = 14f
            setPadding(8.dp(), 0, 8.dp(), 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 36.dp()
            )
        }
        automationPanel.addView(selectorInput)

        val zoomActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        fun addZoomAction(label: String, action: () -> Unit) {
            zoomActions.addView(Button(this).apply {
                text = label
                isAllCaps = false
                minHeight = 40.dp()
                layoutParams = LinearLayout.LayoutParams(0, 40.dp(), 1f).apply {
                    marginEnd = 4.dp()
                }
                setOnClickListener { action() }
            })
        }
        addZoomAction("Zoom −") { changeBrowserZoom("out") }
        addZoomAction("Zoom +") { changeBrowserZoom("in") }
        addZoomAction("Reset zoom") { changeBrowserZoom("reset") }
        automationPanel.addView(zoomActions)

        bridgeStatus = TextView(this).apply {
            textSize = 11f
            maxLines = 2
            text = "MCP bridge starting • 127.0.0.1:8789"
            setPadding(4.dp(), 2.dp(), 4.dp(), 3.dp())
        }
        automationPanel.addView(bridgeStatus)
        val loopActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        loopButton = Button(this).apply {
            text = "Start Observe Loop"
            isAllCaps = false
            minHeight = 44.dp()
            layoutParams = LinearLayout.LayoutParams(0, 44.dp(), 1f).apply {
                marginEnd = 4.dp()
            }
            setPadding(4.dp(), 0, 4.dp(), 0)
            setOnClickListener { toggleObserveLoop() }
        }
        loopActions.addView(loopButton)
        loopActions.addView(Button(this).apply {
            text = "Observe Now"
            isAllCaps = false
            minHeight = 44.dp()
            layoutParams = LinearLayout.LayoutParams(0, 44.dp(), 1f)
            setPadding(4.dp(), 0, 4.dp(), 0)
            setOnClickListener { observeAndAct(false) }
        })
        automationPanel.addView(loopActions)
        root.addView(automationPanel)

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
        }
        root.addView(progress, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 3.dp()
        ))

        status = TextView(this).apply {
            visibility = View.GONE
            text = "Loop Browser • local prototype"
            setPadding(8.dp(), 2.dp(), 8.dp(), 2.dp())
            textSize = 11f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        root.addView(status)

        val browserFrame = android.widget.FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        browser = WebView(this).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        browserFrame.addView(browser)
        stripToggleButton = Button(this).apply {
            text = "×"
            contentDescription = "Hide fullscreen controls"
            isAllCaps = false
            minWidth = 40.dp()
            minHeight = 40.dp()
            setPadding(0, 0, 0, 0)
            visibility = View.GONE
            alpha = 0.92f
            layoutParams = android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 34.dp(), android.view.Gravity.TOP
            ).apply {
                topMargin = 0
            }
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xE6E3E7EE.toInt())
                cornerRadius = 0f
            }
            setOnClickListener { setControlStripHidden(!stripHidden) }
        }
        browserFrame.addView(stripToggleButton)
        root.addView(browserFrame)
        setContentView(root)
        root.requestApplyInsets()
        root.isFocusableInTouchMode = true
        root.requestFocus()
        address.clearFocus()

        address.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                navigate(address.text.toString())
                true
            } else false
        }
    }

    private fun configureBrowser() {
        browser.settings.apply {
            javaScriptEnabled = true
            setSupportZoom(true)
            builtInZoomControls = false
            displayZoomControls = false
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
        }
        browser.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
                pageLoading = newProgress < 100
            }
        }
        mobileUserAgent = browser.settings.userAgentString
        browser.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?, request: WebResourceRequest?
            ): Boolean = false

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                cachedBrowserUrl = url.orEmpty()
                cachedBrowserTitle = view?.title.orEmpty()
                pageLoading = true
                cachedNavigationStatus = "started"
                cachedNavigationError = ""
                cachedNavigationStartedAt = System.currentTimeMillis()
                cachedNavigationFinishedAt = 0L
            }

            override fun onReceivedError(
                view: WebView?, request: WebResourceRequest?, error: android.webkit.WebResourceError?
            ) {
                if (request?.isForMainFrame == true) {
                    cachedNavigationStatus = "failed"
                    cachedNavigationError = "WebView error ${error?.errorCode ?: -1}: ${error?.description ?: "unknown"}"
                    cachedNavigationFinishedAt = System.currentTimeMillis()
                    pageLoading = false
                    android.util.Log.w("LoopBrowserBridge", "Main-frame navigation failed: $cachedNavigationError")
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                cachedNavigationStatus = if (cachedNavigationStatus == "failed") "failed" else "completed"
                cachedNavigationFinishedAt = System.currentTimeMillis()
                cachedBrowserUrl = url.orEmpty()
                cachedBrowserTitle = view?.title.orEmpty()
                cachedBrowserWidth = view?.width ?: 0
                cachedBrowserHeight = view?.height ?: 0
                cachedCanGoBack = view?.canGoBack() ?: false
                cachedCanGoForward = view?.canGoForward() ?: false
                address.setText(url.orEmpty())
                status.text = "Ready • " + url.orEmpty()
                // Start observing automatically on the first completed page; no manual tap needed.
                if (!observeAutoStarted) {
                    observeAutoStarted = true
                    startObserveLoop("page-finished")
                }
            }
        }
    }

    private fun startBrowserBridge() {
        browserBridge = BrowserBridge(
            actionHandler = { route, payload -> handleBrowserBridgeAction(route, payload) },
            screenshotHandler = { readLatestScreenshotForBridge() },
            statusHandler = { message ->
                runOnUiThread {
                    bridgeStatus?.text = message
                    if (message.startsWith("Bridge listener failed")) {
                        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        ).also { bridge ->
            bridgeStatus?.text = "Starting MCP listener…"
            bridge.start()
        }
    }

    private fun readLatestScreenshotForBridge(): ByteArray? {
        if (latestCaptureFileName.isBlank() || !latestCaptureOk) return null
        return try {
            val folder = File(getExternalFilesDir(null), "captures").canonicalFile
            val file = File(folder, latestCaptureFileName).canonicalFile
            if (file.parentFile != folder || !file.isFile || file.length() !in 1..4_194_304L) {
                null
            } else {
                file.readBytes()
            }
        } catch (e: Exception) {
            android.util.Log.e("LoopBrowserBridge", "Unable to read latest screenshot", e)
            null
        }
    }

    private fun handleBrowserBridgeAction(route: String, payload: JSONObject): JSONObject {
        // Validate navigation synchronously so invalid requests are rejected to the caller
        // instead of being acknowledged and leaving navigation telemetry stuck at queued.
        if (route == "POST /v1/browser/navigate") {
            val raw = payload.optString("url", "").trim()
            val uri = try { android.net.Uri.parse(raw) } catch (_: Exception) { null }
            val scheme = uri?.scheme?.lowercase()
            if (raw.isBlank() || raw.any { it.isWhitespace() } ||
                (scheme != "http" && scheme != "https") || uri?.host.isNullOrBlank()) {
                return JSONObject().put("ok", false)
                    .put("error", "Invalid URL: only absolute HTTP/HTTPS URLs with a host are supported")
            }
        }

        // The agent endpoint accepts exactly one allowlisted action per request.
        // It deliberately has no arbitrary JavaScript, CSS-click, shell, or batch-action mode.
        if (route == "POST /v1/browser/agent/step") {
            val action = payload.optString("action", "").trim().lowercase()
            if (action !in allowedAgentActions) {
                return JSONObject().put("ok", false)
                    .put("error", "Unsupported agent action; allowed: " + allowedAgentActions.sorted().joinToString(","))
            }
            if (action == "navigate") {
                val raw = payload.optString("url", "").trim()
                val uri = try { android.net.Uri.parse(raw) } catch (_: Exception) { null }
                val scheme = uri?.scheme?.lowercase()
                if (raw.isBlank() || raw.any { it.isWhitespace() } ||
                    (scheme != "http" && scheme != "https") || uri?.host.isNullOrBlank()) {
                    return JSONObject().put("ok", false)
                        .put("error", "navigate requires an absolute HTTP/HTTPS URL with a host")
                }
            }
            lastAgentAction = action
            lastAgentActionStatus = "queued"
            lastAgentActionAt = System.currentTimeMillis()
        }

        if (route == "POST /v1/browser/zoom") {
            val action = payload.optString("action", "").lowercase()
            val factor = payload.optDouble("factor", 1.1).toFloat()
            if (action !in setOf("in", "out", "reset", "set") ||
                (action == "set" && (!factor.isFinite() || factor !in 0.5f..3.0f))) {
                return JSONObject().put("ok", false)
                    .put("error", "zoom action must be in/out/reset or set with factor 0.5..3.0")
            }
        }

        if (route == "POST /v1/browser/type") {
            val text = payload.optString("text", "")
            if (!payload.has("text") || text.length > 4000) {
                return JSONObject().put("ok", false).put("error", "type requires text of at most 4000 characters")
            }
        }
        if (route == "POST /v1/browser/select") {
            if (!payload.has("x") || !payload.has("y") ||
                !validTouchPoint(payload.optInt("x", -1), payload.optInt("y", -1))) {
                return JSONObject().put("ok", false).put("error", "select requires x,y inside the WebView viewport")
            }
        }

        // Validate touch actions against the WebView viewport before queuing them.
        if (route == "POST /v1/browser/tap") {
            if (!payload.has("x") || !payload.has("y") ||
                !validTouchPoint(payload.optInt("x", -1), payload.optInt("y", -1))) {
                return JSONObject().put("ok", false).put("error", "tap requires x,y inside the WebView viewport")
            }
        }
        if (route == "POST /v1/browser/swipe") {
            val duration = payload.optLong("durationMs", 350L)
            if (!payload.has("x1") || !payload.has("y1") || !payload.has("x2") || !payload.has("y2") ||
                !validTouchPoint(payload.optInt("x1", -1), payload.optInt("y1", -1)) ||
                !validTouchPoint(payload.optInt("x2", -1), payload.optInt("y2", -1)) ||
                duration !in 80L..2000L) {
                return JSONObject().put("ok", false).put("error", "swipe requires valid viewport coordinates and durationMs 80..2000")
            }
        }
        if (route == "POST /v1/browser/scroll") {
            val direction = payload.optString("direction", "").lowercase()
            val amount = payload.optInt("amount", 500)
            if (direction !in setOf("up", "down") || amount !in 80..1200) {
                return JSONObject().put("ok", false).put("error", "scroll requires direction up/down and amount 80..1200")
            }
        }

        // State polling is served from a volatile snapshot and never waits for the UI thread.
        if (route == "GET /v1/browser/state") {
            return JSONObject().put("ok", true)
                .put("url", cachedBrowserUrl)
                .put("title", cachedBrowserTitle)
                .put("loading", pageLoading)
                .put("navigationStatus", if (cachedNavigationStatus == "started" && pageLoading &&
                    cachedNavigationStartedAt > 0L &&
                    System.currentTimeMillis() - cachedNavigationStartedAt >= navigationTimeoutMs) "timed_out" else cachedNavigationStatus)
                .put("navigationError", cachedNavigationError)
                .put("navigationStartedAt", cachedNavigationStartedAt)
                .put("navigationFinishedAt", cachedNavigationFinishedAt)
                .put("navigationTimeoutMs", navigationTimeoutMs)
                .put("desktopMode", desktopMode)
                .put("zoomFactor", browserZoomFactor.toDouble())
                .put("observeLoopRunning", loopRunning)
                .put("canGoBack", cachedCanGoBack)
                .put("canGoForward", cachedCanGoForward)
                .put("viewportWidth", cachedBrowserWidth)
                .put("viewportHeight", cachedBrowserHeight)
                .put("lastCaptureOk", latestCaptureOk)
                .put("lastCaptureFile", latestCaptureFileName)
                .put("lastCaptureTimestamp", latestCaptureTimestamp)
                .put("agentLastAction", lastAgentAction)
                .put("agentLastActionStatus", lastAgentActionStatus)
                .put("agentLastActionAt", lastAgentActionAt)
                .put("timestamp", System.currentTimeMillis())
        }

        if (route == "GET /health") {
            return JSONObject().put("ok", true).put("bridge", "loop-browser")
                .put("version", 1).put("ready", !isFinishing)
        }

        // Reset navigation telemetry when a navigation-like action is accepted, before
        // it is queued on the UI thread. This prevents an old loading timestamp from
        // making the new request appear timed out while it is still waiting in the queue.
        if (route == "POST /v1/browser/navigate" || route == "POST /v1/browser/reload" ||
            route == "POST /v1/browser/back" || route == "POST /v1/browser/forward" ||
            route == "POST /v1/browser/desktop") {
            cachedNavigationStatus = "queued"
            cachedNavigationError = ""
            cachedNavigationStartedAt = 0L
            cachedNavigationFinishedAt = 0L
            pageLoading = false
        }

        // Do not block the socket worker waiting on Android's main thread. WebView can
        // be busy loading/rendering; queue the operation and acknowledge the request.
        runOnUiThread {
            try {
                when (route) {
                    "POST /v1/browser/navigate" -> navigate(payload.optString("url", "").trim())
                    "POST /v1/browser/zoom" -> changeBrowserZoom(
                        payload.optString("action", "").lowercase(),
                        payload.optDouble("factor", 1.1).toFloat()
                    )
                    "POST /v1/browser/tap" -> {
                        val x = payload.optInt("x")
                        val y = payload.optInt("y")
                        performTouchGesture(x, y, x, y, 80L)
                    }
                    "POST /v1/browser/type" -> typeIntoFocusedElement(payload.optString("text", ""))
                    "POST /v1/browser/select" -> selectWordAt(payload.optInt("x"), payload.optInt("y"))
                    "POST /v1/browser/select_all" -> selectAllInFocusedElement()
                    "POST /v1/browser/copy" -> copyCurrentSelection()
                    "POST /v1/browser/paste" -> {
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        val clipText = if (clipboard.hasPrimaryClip() && clipboard.primaryClipDescription?.hasMimeType("text/plain") == true) {
                            clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
                        } else ""
                        if (clipText.isEmpty()) {
                            lastAgentAction = "paste"
                            lastAgentActionStatus = "rejected_empty_clipboard"
                            lastAgentActionAt = System.currentTimeMillis()
                            status.text = "Paste skipped • clipboard is empty"
                        } else {
                            typeIntoFocusedElement(clipText)
                        }
                    }
                    "POST /v1/browser/swipe" -> performTouchGesture(
                        payload.optInt("x1"), payload.optInt("y1"),
                        payload.optInt("x2"), payload.optInt("y2"),
                        payload.optLong("durationMs", 350L)
                    )
                    "POST /v1/browser/scroll" -> {
                        val amount = payload.optInt("amount", 500)
                        val centerX = (browser.width / 2).coerceAtLeast(1)
                        val startY = if (payload.optString("direction").lowercase() == "down")
                            (browser.height * 3 / 4).coerceAtLeast(1) else (browser.height / 4).coerceAtLeast(1)
                        val endY = if (payload.optString("direction").lowercase() == "down")
                            (startY - amount).coerceAtLeast(1) else (startY + amount).coerceAtMost(browser.height - 1)
                        performTouchGesture(centerX, startY, centerX, endY, 350L)
                    }
                    "POST /v1/browser/back" -> if (::browser.isInitialized && browser.canGoBack()) browser.goBack()
                    "POST /v1/browser/forward" -> if (::browser.isInitialized && browser.canGoForward()) browser.goForward()
                    "POST /v1/browser/desktop" -> {
                        val requested = payload.opt("enabled")
                        if (requested !is Boolean) {
                            android.util.Log.w("LoopBrowserBridge", "Desktop toggle requires boolean enabled")
                        } else if (desktopMode != requested) {
                            toggleDesktopMode()
                        }
                    }
                    "POST /v1/browser/reload" -> if (::browser.isInitialized) browser.reload()
                    "POST /v1/browser/capture" -> {
                        val capture = captureViewportForBridge()
                        latestCaptureFileName = capture?.name.orEmpty()
                        latestCaptureOk = capture != null
                        latestCaptureTimestamp = System.currentTimeMillis()
                        android.util.Log.i(
                            "LoopBrowserBridge",
                            "MCP capture completed: ok=$latestCaptureOk file=$latestCaptureFileName"
                        )
                    }
                    "POST /v1/browser/start" -> startObserveLoop("mcp")
                    "POST /v1/browser/stop" -> stopObserveLoop("MCP")
                    "POST /v1/browser/agent/step" -> {
                        val action = payload.optString("action", "").trim().lowercase()
                        when (action) {
                            "observe" -> {
                                lastAgentActionStatus = "completed"
                                status.text = "Agent observed current page • " + browser.title.orEmpty()
                            }
                            "navigate" -> navigate(payload.optString("url", "").trim()).also {
                                lastAgentActionStatus = "executed"
                            }
                            "back" -> {
                                if (browser.canGoBack()) { browser.goBack(); lastAgentActionStatus = "executed" }
                                else lastAgentActionStatus = "rejected_no_history"
                            }
                            "forward" -> {
                                if (browser.canGoForward()) { browser.goForward(); lastAgentActionStatus = "executed" }
                                else lastAgentActionStatus = "rejected_no_forward_history"
                            }
                            "reload" -> { browser.reload(); lastAgentActionStatus = "executed" }
                            "capture" -> {
                                val capture = captureViewportForBridge()
                                latestCaptureFileName = capture?.name.orEmpty()
                                latestCaptureOk = capture != null
                                latestCaptureTimestamp = System.currentTimeMillis()
                                lastAgentActionStatus = if (capture != null) "completed" else "failed_capture"
                            }
                            "desktop_on", "desktop_off" -> {
                                val requested = action == "desktop_on"
                                if (desktopMode != requested) toggleDesktopMode()
                                lastAgentActionStatus = "executed"
                            }
                            "stop" -> { stopObserveLoop("agent"); lastAgentActionStatus = "completed" }
                        }
                        lastAgentActionAt = System.currentTimeMillis()
                        android.util.Log.i("LoopBrowserBridge", "Agent step: action=$action status=$lastAgentActionStatus")
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("LoopBrowserBridge", "Queued action failed: $route", e)
            }
        }
        return JSONObject().put("ok", true).put("accepted", true)
            .put("queued", true).put("action", route)
    }

    /** Selects the word under a viewport-local point; never accepts page-supplied selectors or scripts. */
    private fun selectWordAt(x: Int, y: Int) {
        val script = """
            (function() {
              const x = $x, y = $y;
              let range = document.caretRangeFromPoint ? document.caretRangeFromPoint(x, y) :
                (document.caretPositionFromPoint ? (function(p) {
                  if (!p) return null;
                  const r = document.createRange(); r.setStart(p.offsetNode, p.offset); r.collapse(true); return r;
                })(document.caretPositionFromPoint(x, y)) : null);
              if (!range || !range.startContainer) return JSON.stringify({ok:false,error:'no_text_at_point'});
              const node = range.startContainer;
              if (node.nodeType !== Node.TEXT_NODE || !node.textContent || !node.textContent.trim())
                return JSON.stringify({ok:false,error:'point_is_not_text'});
              const selection = window.getSelection();
              selection.removeAllRanges();
              selection.addRange(range);
              if (typeof selection.modify === 'function') {
                selection.modify('move', 'backward', 'word');
                selection.modify('extend', 'forward', 'word');
              } else {
                const text = node.textContent, at = range.startOffset;
                let start = at, end = at;
                while (start > 0 && /[\w'-]/.test(text[start-1])) start--;
                while (end < text.length && /[\w'-]/.test(text[end])) end++;
                const wordRange = document.createRange(); wordRange.setStart(node,start); wordRange.setEnd(node,end);
                selection.removeAllRanges(); selection.addRange(wordRange);
              }
              const selected = window.getSelection().toString();
              return JSON.stringify({ok:selected.length>0,error:selected.length ? '' : 'nothing_selected',length:selected.length});
            })()
        """.trimIndent()
        browser.evaluateJavascript(script) { raw -> handleSelectionResult("select", raw) }
    }

    /** Select all text only inside the currently focused editable control. */
    private fun selectAllInFocusedElement() {
        val script = """
            (function() {
              const el = document.activeElement;
              if (!el) return JSON.stringify({ok:false,error:'no_focused_element'});
              if ((el instanceof HTMLInputElement || el instanceof HTMLTextAreaElement) &&
                  typeof el.select === 'function' && !el.disabled && !el.readOnly) {
                el.select();
                return JSON.stringify({ok:true,length:(el.value || '').length});
              }
              if (el.isContentEditable) {
                const range = document.createRange(); range.selectNodeContents(el);
                const selection = window.getSelection(); selection.removeAllRanges(); selection.addRange(range);
                return JSON.stringify({ok:true,length:selection.toString().length});
              }
              return JSON.stringify({ok:false,error:'focus_an_editable_field_first'});
            })()
        """.trimIndent()
        browser.evaluateJavascript(script) { raw -> handleSelectionResult("select_all", raw) }
    }

    /** Copies only the current explicit selection; clipboard contents are never returned by the bridge. */
    private fun copyCurrentSelection() {
        val script = """
            (function() {
              const el = document.activeElement;
              let text = '';
              if ((el instanceof HTMLInputElement || el instanceof HTMLTextAreaElement) &&
                  typeof el.selectionStart === 'number' && typeof el.selectionEnd === 'number') {
                text = (el.value || '').slice(el.selectionStart, el.selectionEnd);
              } else {
                text = window.getSelection ? window.getSelection().toString() : '';
              }
              return JSON.stringify({ok:text.length>0,length:text.length,text:text,error:text.length ? '' : 'no_selection'});
            })()
        """.trimIndent()
        browser.evaluateJavascript(script) { raw ->
            try {
                val decoded = org.json.JSONTokener(raw ?: "null").nextValue() as? String
                val result = JSONObject(decoded ?: "{}")
                if (result.optBoolean("ok")) {
                    val clip = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clip.setPrimaryClip(android.content.ClipData.newPlainText("Loop Browser selection", result.optString("text", "")))
                    lastAgentAction = "copy"
                    lastAgentActionStatus = "completed"
                    lastAgentActionAt = System.currentTimeMillis()
                    status.text = "Copied selected text • " + result.optInt("length", 0) + " characters"
                } else {
                    lastAgentAction = "copy"
                    lastAgentActionStatus = "rejected_no_selection"
                    lastAgentActionAt = System.currentTimeMillis()
                    status.text = "Copy failed • no text selected"
                }
            } catch (e: Exception) {
                lastAgentAction = "copy"
                lastAgentActionStatus = "failed"
                lastAgentActionAt = System.currentTimeMillis()
                status.text = "Copy failed • result unavailable"
            }
        }
    }

    private fun handleSelectionResult(action: String, raw: String?) {
        try {
            val decoded = org.json.JSONTokener(raw ?: "null").nextValue() as? String
            val result = JSONObject(decoded ?: "{}")
            lastAgentAction = action
            lastAgentActionStatus = if (result.optBoolean("ok")) "completed" else "rejected"
            lastAgentActionAt = System.currentTimeMillis()
            status.text = if (result.optBoolean("ok")) {
                "Selection ready • " + result.optInt("length", 0) + " characters"
            } else {
                "Selection failed • " + result.optString("error", "not selectable")
            }
        } catch (e: Exception) {
            lastAgentAction = action
            lastAgentActionStatus = "failed"
            lastAgentActionAt = System.currentTimeMillis()
            status.text = "Selection failed • result unavailable"
        }
    }

    /**
     * Inserts plain text into the page's currently focused editable element.
     * The caller cannot provide JavaScript or a selector; text is JSON-escaped and
     * inserted only into an input, textarea, or contenteditable element.
     */
    private fun typeIntoFocusedElement(text: String) {
        val safeText = JSONObject.quote(text)
        val script = """
            (function() {
              const el = document.activeElement;
              if (!el || el.disabled || el.readOnly) {
                return JSON.stringify({ok:false,error:'no_editable_focused_element'});
              }
              const isInput = el instanceof HTMLInputElement &&
                !['button','submit','reset','checkbox','radio','file','hidden','image'].includes(el.type);
              const isTextArea = el instanceof HTMLTextAreaElement;
              const isEditable = el.isContentEditable;
              if (!(isInput || isTextArea || isEditable)) {
                return JSON.stringify({ok:false,error:'focus_an_editable_field_first'});
              }
              const text = $safeText;
              if (isEditable) {
                const inserted = document.execCommand('insertText', false, text);
                if (!inserted) el.textContent = (el.textContent || '') + text;
              } else {
                const proto = isTextArea ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
                const descriptor = Object.getOwnPropertyDescriptor(proto, 'value');
                const setter = descriptor && descriptor.set;
                if (!setter) return JSON.stringify({ok:false,error:'field_setter_unavailable'});
                const value = el.value || '';
                const start = typeof el.selectionStart === 'number' ? el.selectionStart : value.length;
                const end = typeof el.selectionEnd === 'number' ? el.selectionEnd : value.length;
                setter.call(el, value.slice(0, start) + text + value.slice(end));
                if (typeof el.setSelectionRange === 'function') {
                  el.setSelectionRange(start + text.length, start + text.length);
                }
              }
              el.dispatchEvent(new InputEvent('input', {bubbles:true, inputType:'insertText', data:text}));
              el.dispatchEvent(new Event('change', {bubbles:true}));
              return JSON.stringify({
                ok:true,
                fieldType:isInput ? el.type : (isTextArea ? 'textarea' : 'contenteditable'),
                insertedLength:text.length
              });
            })()
        """.trimIndent()
        browser.evaluateJavascript(script) { raw ->
            try {
                val decoded = org.json.JSONTokener(raw ?: "null").nextValue() as? String
                val result = JSONObject(decoded ?: "{}")
                if (result.optBoolean("ok")) {
                    val kind = result.optString("fieldType", "field")
                    val length = result.optInt("insertedLength", 0)
                    status.text = "Text entered • $kind • $length characters"
                    lastAgentAction = "type"
                    lastAgentActionStatus = "completed"
                } else {
                    status.text = "Text entry failed • " + result.optString("error", "field not editable")
                    lastAgentActionStatus = "rejected_no_editable_field"
                }
                lastAgentActionAt = System.currentTimeMillis()
            } catch (e: Exception) {
                status.text = "Text entry failed • result unavailable"
                lastAgentActionStatus = "failed"
                android.util.Log.w("LoopBrowserBridge", "Unable to parse text-entry result", e)
            }
        }
    }

    private fun validTouchPoint(x: Int, y: Int): Boolean =
        ::browser.isInitialized && browser.width > 0 && browser.height > 0 &&
            x >= 0 && y >= 0 && x < browser.width && y < browser.height

    /** Dispatches a WebView touch sequence in viewport-local pixel coordinates. */
    private fun performTouchGesture(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) {
        if (!validTouchPoint(x1, y1) || !validTouchPoint(x2, y2)) {
            lastAgentActionStatus = "rejected_invalid_coordinates"
            return
        }
        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x1.toFloat(), y1.toFloat(), 0)
        browser.dispatchTouchEvent(down)
        down.recycle()
        val steps = if (x1 == x2 && y1 == y2) 1 else 8
        for (step in 1 until steps) {
            val delay = durationMs * step / steps
            loopHandler.postDelayed({
                if (!::browser.isInitialized || isFinishing) return@postDelayed
                val fraction = step.toFloat() / steps.toFloat()
                val x = x1 + ((x2 - x1) * fraction).toInt()
                val y = y1 + ((y2 - y1) * fraction).toInt()
                val now = SystemClock.uptimeMillis()
                val move = MotionEvent.obtain(downTime, now, MotionEvent.ACTION_MOVE, x.toFloat(), y.toFloat(), 0)
                browser.dispatchTouchEvent(move)
                move.recycle()
            }, delay)
        }
        loopHandler.postDelayed({
            if (!::browser.isInitialized || isFinishing) return@postDelayed
            val now = SystemClock.uptimeMillis()
            val up = MotionEvent.obtain(downTime, now, MotionEvent.ACTION_UP, x2.toFloat(), y2.toFloat(), 0)
            browser.dispatchTouchEvent(up)
            up.recycle()
            lastAgentAction = if (x1 == x2 && y1 == y2) "tap" else "swipe"
            lastAgentActionStatus = "dispatched"
            lastAgentActionAt = System.currentTimeMillis()
            status.text = "Touch " + lastAgentAction + " dispatched • (" + x2 + "," + y2 + ")"
            android.util.Log.i("LoopBrowserBridge", "Touch gesture dispatched: action=" + lastAgentAction + " from=(" + x1 + "," + y1 + ") to=(" + x2 + "," + y2 + ")")
        }, durationMs)
    }

    private fun captureViewportForBridge(): File? {
        if (!::browser.isInitialized || browser.width <= 0 || browser.height <= 0) return null
        return try {
            val bitmap = Bitmap.createBitmap(browser.width, browser.height, Bitmap.Config.ARGB_8888)
            browser.draw(Canvas(bitmap))
            val folder = File(getExternalFilesDir(null), "captures")
            if (!folder.exists() && !folder.mkdirs()) {
                bitmap.recycle()
                return null
            }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
            val output = File(folder, "mcp-$stamp.png")
            val ok = FileOutputStream(output).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            if (ok && output.exists()) output else null
        } catch (_: Exception) { null }
    }

    private fun setControlStripHidden(hidden: Boolean) {
        stripHidden = hidden
        displayRow.visibility = View.VISIBLE
        stripToggleButton.visibility = View.GONE
        stripToggleButton.text = "×"
        stripToggleButton.contentDescription = "Hide fullscreen controls"
        stripToggleButton.gravity = android.view.Gravity.CENTER_VERTICAL or if (hidden) android.view.Gravity.END else android.view.Gravity.START
        stripToggleButton.setPadding(if (hidden) 0 else 12.dp(), 0, if (hidden) 12.dp() else 0, 0)
    }

    private fun toggleFullscreen() {
        fullscreenMode = !fullscreenMode
        addressBar.visibility = if (fullscreenMode) View.GONE else View.VISIBLE
        actionsRow.visibility = if (fullscreenMode) View.GONE else View.VISIBLE
        automationPanel.visibility = if (fullscreenMode) View.GONE else
            if (automationToggle.text == "Hide tools") View.VISIBLE else View.GONE
        status.visibility = if (fullscreenMode) View.GONE else
            if (loopRunning || automationPanel.visibility == View.VISIBLE) View.VISIBLE else View.GONE
        progress.visibility = if (fullscreenMode) View.GONE else progress.visibility
        fullscreenButton.text = if (fullscreenMode) "Expand" else "Collapse"
        if (!fullscreenMode) {
            setControlStripHidden(false)
        } else {
            setControlStripHidden(true)
        }
        displayRow.setBackgroundColor(if (fullscreenMode) 0xFFE3E7EE.toInt() else 0xFFF5F7FB.toInt())
        if (fullscreenMode) {
            address.clearFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(address.windowToken, 0)
        }
    }

    private fun changeBrowserZoom(action: String, requestedFactor: Float = 1.1f) {
        if (!::browser.isInitialized) return
        when (action) {
            "in" -> {
                if (browserZoomFactor < 3.0f) {
                    browser.zoomBy(1.1f)
                    browserZoomFactor = (browserZoomFactor * 1.1f).coerceAtMost(3.0f)
                }
            }
            "out" -> {
                if (browserZoomFactor > 0.5f) {
                    browser.zoomBy(1.0f / 1.1f)
                    browserZoomFactor = (browserZoomFactor / 1.1f).coerceAtLeast(0.5f)
                }
            }
            "reset" -> {
                val ratio = 1.0f / browserZoomFactor
                if (ratio.isFinite() && ratio > 0f) browser.zoomBy(ratio)
                browserZoomFactor = 1.0f
            }
            "set" -> {
                val target = requestedFactor.coerceIn(0.5f, 3.0f)
                val ratio = target / browserZoomFactor
                if (ratio.isFinite() && ratio > 0f) browser.zoomBy(ratio)
                browserZoomFactor = target
            }
            else -> return
        }
        lastAgentAction = "zoom_" + action
        lastAgentActionStatus = "dispatched"
        lastAgentActionAt = System.currentTimeMillis()
        status.text = "Browser zoom: " + (browserZoomFactor * 100).toInt() + "%"
    }

    private fun toggleDesktopMode() {
        desktopMode = !desktopMode
        browser.settings.apply {
            useWideViewPort = desktopMode
            loadWithOverviewMode = desktopMode
            userAgentString = if (desktopMode) {
                "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
            } else {
                mobileUserAgent
            }
        }
        desktopButton.text = if (desktopMode) "Desktop: On" else "Desktop: Off"
        val currentUrl = browser.url ?: address.text.toString()
        if (currentUrl.isNotBlank()) browser.loadUrl(currentUrl)
        status.text = if (desktopMode) "Desktop mode enabled • reloading page" else "Mobile mode enabled • reloading page"
    }

    private fun toggleObserveLoop() {
        if (loopRunning) stopObserveLoop("user") else startObserveLoop("user")
    }

    private fun startObserveLoop(source: String) {
        if (isFinishing || !::browser.isInitialized) return
        if (loopRunning) return
        loopRunning = true
        if (::loopButton.isInitialized) loopButton.text = "Stop Loop"
        if (::status.isInitialized) {
            status.visibility = View.VISIBLE
            status.text = "Observe loop started • source=$source • 5-second interval"
        }
        loopStep = 0
        observeAndAct(true)
    }

    private fun stopObserveLoop(source: String) {
        loopRunning = false
        loopHandler.removeCallbacksAndMessages(null)
        if (::loopButton.isInitialized) loopButton.text = "Start Observe Loop"
        if (::status.isInitialized) {
            status.text = "Observe loop stopped • source=$source"
            status.visibility = if (::automationPanel.isInitialized && automationPanel.visibility == View.VISIBLE) View.VISIBLE else View.GONE
        }
    }

    private fun observeAndAct(continueLoop: Boolean) {
        if (!::browser.isInitialized || isFinishing) return
        loopStep++
        val selector = selectorInput.text.toString().trim()
        val safeSelector = org.json.JSONObject.quote(selector)
        val script = """
            (function() {
              var selector = $safeSelector;
              var clicked = false;
              if (selector) {
                try {
                  var target = document.querySelector(selector);
                  if (target) {
                    target.click();
                    clicked = true;
                  }
                } catch (e) {}
              }
              return JSON.stringify({
                title: document.title || '',
                url: location.href,
                readyState: document.readyState,
                text: (document.body ? document.body.innerText : '').slice(0, 1200),
                selector: selector,
                clicked: clicked
              });
            })();
        """.trimIndent()
        browser.evaluateJavascript(script) { result ->
            val decoded = try {
                val raw = if (result == null || result == "null") "{}" else
                    org.json.JSONTokener(result).nextValue().toString()
                org.json.JSONObject(raw)
            } catch (_: Exception) { org.json.JSONObject() }
            val title = decoded.optString("title", browser.title.orEmpty())
            val clicked = decoded.optBoolean("clicked", false)
            status.text = "Loop #$loopStep • " + (if (clicked) "clicked" else "observed") + " • " + title
            saveViewportCapture("loop-$loopStep")
            if (continueLoop && loopRunning) {
                loopHandler.postDelayed({ observeAndAct(true) }, 5000L)
            }
        }
    }

    private fun saveViewportCapture(prefix: String) {
        if (browser.width <= 0 || browser.height <= 0) return
        try {
            val bitmap = Bitmap.createBitmap(browser.width, browser.height, Bitmap.Config.ARGB_8888)
            browser.draw(Canvas(bitmap))
            val folder = File(getExternalFilesDir(null), "captures")
            if (!folder.exists()) folder.mkdirs()
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
            val output = File(folder, "$prefix-$stamp.png")
            val saved = FileOutputStream(output).use { bitmap.compress(Bitmap.CompressFormat.PNG, 90, it) }
            bitmap.recycle()
            if (saved && output.exists() && output.length() > 0L) {
                latestCaptureFileName = output.name
                latestCaptureOk = true
                latestCaptureTimestamp = System.currentTimeMillis()
                android.util.Log.i(
                    "LoopBrowserBridge",
                    "Observe-loop capture completed: step=$loopStep file=$latestCaptureFileName bytes=${output.length()}"
                )
            } else {
                latestCaptureOk = false
                latestCaptureTimestamp = System.currentTimeMillis()
                android.util.Log.w("LoopBrowserBridge", "Observe-loop capture failed: step=$loopStep")
            }
        } catch (e: Exception) {
            latestCaptureOk = false
            latestCaptureTimestamp = System.currentTimeMillis()
            android.util.Log.e("LoopBrowserBridge", "Observe-loop capture exception: step=$loopStep", e)
        }
    }

    private fun navigate(raw: String) {
        val input = raw.trim()
        if (input.isEmpty()) return
        val candidate = when {
            input.startsWith("https://", true) || input.startsWith("http://", true) -> input
            input.contains(" ") || !input.contains(".") ->
                "https://www.google.com/search?q=" + android.net.Uri.encode(input)
            else -> "https://$input"
        }
        val uri = android.net.Uri.parse(candidate)
        if (uri.scheme != "https" && uri.scheme != "http") {
            Toast.makeText(this, "Only HTTP/HTTPS pages are supported", Toast.LENGTH_SHORT).show()
            return
        }
        browser.loadUrl(candidate)
    }

    private fun captureViewport() {
        if (!::browser.isInitialized || browser.width <= 0 || browser.height <= 0) {
            Toast.makeText(this, "Page is not ready yet", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val bitmap = Bitmap.createBitmap(browser.width, browser.height, Bitmap.Config.ARGB_8888)
            browser.draw(Canvas(bitmap))
            val folder = File(getExternalFilesDir(null), "captures")
            if (!folder.exists()) folder.mkdirs()
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
            val output = File(folder, "viewport-$stamp.png")
            FileOutputStream(output).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            status.text = "Captured • " + output.name
            Toast.makeText(this, "Screenshot saved: " + output.name, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            status.text = "Capture failed: " + e.javaClass.simpleName
            Toast.makeText(this, "Screenshot capture failed", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        loopRunning = false
        loopHandler.removeCallbacksAndMessages(null)
        browserBridge?.stop()
        browserBridge = null
        if (::browser.isInitialized) browser.destroy()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Android API; kept for API 26 compatibility")
    override fun onBackPressed() {
        if (::browser.isInitialized && browser.canGoBack()) browser.goBack()
        else super.onBackPressed()
    }
    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()
}
