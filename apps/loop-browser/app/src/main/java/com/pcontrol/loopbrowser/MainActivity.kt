package com.pcontrol.loopbrowser

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        configureBrowser()
        browser.loadUrl("https://example.com")
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF5F7FB.toInt())
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(8, 8, 8, 4)
        }
        address = EditText(this).apply {
            singleLine = true
            hint = "Enter URL or search"
            setText("https://example.com")
            imeOptions = EditorInfo.IME_ACTION_GO
            layoutParams = LinearLayout.LayoutParams(0, 48.dp(), 1f)
        }
        bar.addView(address)
        bar.addView(Button(this).apply {
            text = "Go"
            setOnClickListener { navigate(address.text.toString()) }
        })
        root.addView(bar)
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(8, 0, 8, 4)
        }
        actions.addView(Button(this).apply {
            text = "Back"
            setOnClickListener { if (browser.canGoBack()) browser.goBack() }
        })
        actions.addView(Button(this).apply {
            text = "Reload"
            setOnClickListener { browser.reload() }
        })
        actions.addView(Button(this).apply {
            text = "Capture"
            setOnClickListener { captureViewport() }
        })
        root.addView(actions)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
        }
        root.addView(progress, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 3.dp()
        ))
        status = TextView(this).apply {
            text = "Loop Browser • local prototype"
            setPadding(10, 4, 10, 4)
            textSize = 12f
        }
        root.addView(status)
        browser = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        root.addView(browser)
        setContentView(root)
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
            }
        }
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

    @Deprecated("Deprecated in Android API; kept for API 26 compatibility")
    override fun onBackPressed() {
        if (::browser.isInitialized && browser.canGoBack()) browser.goBack()
        else super.onBackPressed()
    }
    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()
}
