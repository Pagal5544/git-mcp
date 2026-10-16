package com.pcontrol.loopbrowser

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
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
    private var mobileUserAgent: String = ""
    private val loopHandler = Handler(Looper.getMainLooper())
    private var loopRunning = false
    @Volatile private var pageLoading = false
    private var bridgeStatus: TextView? = null
    private var browserBridge: BrowserBridge? = null
    private var loopStep = 0

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
            override fun onPageFinished(view: WebView?, url: String?) {
                address.setText(url.orEmpty())
                status.text = "Ready • " + url.orEmpty()
            }
        }
    }

    private fun startBrowserBridge() {
        browserBridge = BrowserBridge(
            actionHandler = { route, payload -> handleBrowserBridgeAction(route, payload) },
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

    private fun handleBrowserBridgeAction(route: String, payload: JSONObject): JSONObject {
        android.util.Log.i("LoopBrowserBridge", "Dispatching UI action: $route")
        val answer = arrayOf(JSONObject().put("ok", false).put("error", "action timeout"))
        val latch = CountDownLatch(1)
        runOnUiThread {
            try {
                answer[0] = when (route) {
                    "GET /health" -> JSONObject()
                        .put("ok", true).put("bridge", "loop-browser").put("version", 1)
                        .put("ready", ::browser.isInitialized && !isFinishing)
                    "GET /v1/browser/state" -> JSONObject()
                        .put("ok", true)
                        .put("url", browser.url.orEmpty())
                        .put("title", browser.title.orEmpty())
                        .put("loading", pageLoading)
                        .put("desktopMode", desktopMode)
                        .put("observeLoopRunning", loopRunning)
                        .put("canGoBack", browser.canGoBack())
                        .put("viewportWidth", browser.width)
                        .put("viewportHeight", browser.height)
                        .put("timestamp", System.currentTimeMillis())
                    "POST /v1/browser/navigate" -> {
                        val raw = payload.optString("url", "").trim()
                        val uri = android.net.Uri.parse(raw)
                        if (raw.isBlank() || (uri.scheme != "http" && uri.scheme != "https") || uri.host.isNullOrBlank()) {
                            JSONObject().put("ok", false).put("error", "Only valid HTTP/HTTPS URLs are accepted")
                        } else {
                            navigate(raw)
                            JSONObject().put("ok", true).put("accepted", true).put("url", raw)
                        }
                    }
                    "POST /v1/browser/back" -> {
                        if (browser.canGoBack()) {
                            browser.goBack()
                            JSONObject().put("ok", true).put("performed", true)
                        } else JSONObject().put("ok", true).put("performed", false).put("reason", "no history")
                    }
                    "POST /v1/browser/reload" -> {
                        browser.reload()
                        JSONObject().put("ok", true).put("performed", true)
                    }
                    "POST /v1/browser/capture" -> {
                        val output = captureViewportForBridge()
                        if (output == null) JSONObject().put("ok", false).put("error", "Capture failed or page not ready")
                        else JSONObject().put("ok", true).put("captureId", output.name)
                            .put("timestamp", System.currentTimeMillis())
                            .put("width", browser.width).put("height", browser.height)
                    }
                    "POST /v1/browser/stop" -> {
                        loopRunning = false
                        loopHandler.removeCallbacksAndMessages(null)
                        if (::loopButton.isInitialized) loopButton.text = "Start Observe Loop"
                        status.text = "Observe loop stopped by MCP"
                        JSONObject().put("ok", true).put("loopRunning", false)
                    }
                    else -> JSONObject().put("ok", false).put("error", "Unsupported action")
                }
            } catch (e: Exception) {
                answer[0] = JSONObject().put("ok", false).put("error", e.javaClass.simpleName)
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(5, TimeUnit.SECONDS)) {
            android.util.Log.e("LoopBrowserBridge", "UI action timed out: $route; Activity main thread did not complete it")
            return JSONObject().put("ok", false).put("error", "UI action timed out")
        }
        android.util.Log.i("LoopBrowserBridge", "Completed UI action: $route")
        return answer[0]
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
        loopRunning = !loopRunning
        loopButton.text = if (loopRunning) "Stop Loop" else "Start Loop"
        status.visibility = if (loopRunning || automationPanel.visibility == View.VISIBLE) View.VISIBLE else View.GONE
        if (loopRunning) {
            loopStep = 0
            status.text = "Observe loop started • 5-second interval"
            observeAndAct(true)
        } else {
            loopHandler.removeCallbacksAndMessages(null)
            status.text = "Observe loop stopped"
            status.visibility = if (automationPanel.visibility == View.VISIBLE) View.VISIBLE else View.GONE
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
            FileOutputStream(output).use { bitmap.compress(Bitmap.CompressFormat.PNG, 90, it) }
            bitmap.recycle()
        } catch (_: Exception) { }
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
