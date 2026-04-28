package com.fxcalc.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var dbHelper: CalcDatabaseHelper
    private val IMPORT_REQUEST_CODE = 1001

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        dbHelper = CalcDatabaseHelper(this)

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.setSupportZoom(false)
            setBackgroundColor(0xFF0C0C0E.toInt())
            webViewClient = WebViewClient()
        }

        webView.addJavascriptInterface(CalcBridge(dbHelper, this, webView), "Android")
        setContentView(webView)
        webView.loadUrl("file:///android_asset/index.html")
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    fun openFilePicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "application/json"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(Intent.createChooser(intent, "Select backup file"), IMPORT_REQUEST_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == IMPORT_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            data?.data?.let { uri ->
                try {
                    val json = contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: return
                    val count = dbHelper.importCalculations(json)
                    runOnUiThread {
                        Toast.makeText(this, "Imported $count calculations", Toast.LENGTH_SHORT).show()
                        webView.evaluateJavascript("if(typeof loadSaved==='function')loadSaved()", null)
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        Toast.makeText(this, "Import failed: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
}

class CalcBridge(
    private val db: CalcDatabaseHelper,
    private val activity: MainActivity,
    private val webView: WebView
) {

    companion object {
        private const val TAG = "FXCalcBridge"
        private const val FETCH_URL = "https://ntprogress.ru/local_api/public/east"
        private const val HARD_TIMEOUT_MS = 10_000L
    }

    private val executor = Executors.newSingleThreadExecutor()

    @JavascriptInterface
    fun saveCalc(name: String, data: String): Boolean {
        return try {
            db.saveCalculation(name, data)
            true
        } catch (e: Exception) {
            false
        }
    }

    @JavascriptInterface
    fun loadAll(): String {
        return try {
            db.getAllCalculations()
        } catch (e: Exception) {
            "[]"
        }
    }

    @JavascriptInterface
    fun deleteCalc(id: Long): Boolean {
        return try {
            db.deleteCalculation(id)
            true
        } catch (e: Exception) {
            false
        }
    }

    @JavascriptInterface
    fun exportData(): String {
        return try {
            val json = db.getAllCalculations()
            val sdf = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault())
            val fileName = "fxcalc_backup_${sdf.format(Date())}.json"
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val file = File(downloadsDir, fileName)
            file.writeText(json)
            "Exported to Downloads/$fileName"
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    @JavascriptInterface
    fun importData() {
        activity.runOnUiThread {
            activity.openFilePicker()
        }
    }

    /**
     * Async fetch: returns immediately, then delivers result to JS via window.onQuoteResult(jsonString).
     * Uses a background thread with a hard timeout that interrupts the request if it hangs.
     */
    @JavascriptInterface
    fun fetchQuoteAsync(requestId: String) {
        Log.d(TAG, "fetchQuoteAsync: starting requestId=$requestId")

        val worker = Thread {
            val result = doFetch()
            Log.d(TAG, "fetchQuoteAsync: worker returning result for $requestId")
            deliverResult(requestId, result)
        }
        worker.name = "FXCalc-fetch-$requestId"
        worker.isDaemon = true
        worker.start()

        // Hard-timeout watchdog on a separate thread
        executor.submit {
            try {
                worker.join(HARD_TIMEOUT_MS)
                if (worker.isAlive) {
                    Log.w(TAG, "fetchQuoteAsync: hard timeout, interrupting worker for $requestId")
                    worker.interrupt()
                    // Give it a moment to die
                    Thread.sleep(200)
                    if (worker.isAlive) {
                        @Suppress("DEPRECATION")
                        try { worker.stop() } catch (_: Throwable) {}
                    }
                    deliverResult(requestId, """{"error":"timeout (${HARD_TIMEOUT_MS}ms)"}""")
                }
            } catch (e: Exception) {
                Log.e(TAG, "watchdog error", e)
            }
        }
    }

    private fun doFetch(): String {
        val startTime = System.currentTimeMillis()
        var conn: HttpURLConnection? = null
        try {
            Log.d(TAG, "doFetch: connecting to $FETCH_URL")
            val url = URL(FETCH_URL)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 7000
                readTimeout = 7000
                instanceFollowRedirects = true
                useCaches = false
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Cache-Control", "no-cache")
                setRequestProperty("User-Agent", "FXCalculator/2.1 (Android)")
                setRequestProperty("Connection", "close")
            }

            val code = conn.responseCode
            val elapsed = System.currentTimeMillis() - startTime
            Log.d(TAG, "doFetch: HTTP $code in ${elapsed}ms")

            if (code in 300..399) {
                val location = conn.getHeaderField("Location")
                if (location != null) {
                    Log.d(TAG, "doFetch: redirect to $location")
                    conn.disconnect()
                    val redirUrl = URL(location)
                    val redirConn = (redirUrl.openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 7000
                        readTimeout = 7000
                        useCaches = false
                        setRequestProperty("Accept", "application/json")
                        setRequestProperty("User-Agent", "FXCalculator/2.1 (Android)")
                        setRequestProperty("Connection", "close")
                    }
                    val redirCode = redirConn.responseCode
                    if (redirCode != 200) {
                        redirConn.disconnect()
                        return """{"error":"HTTP $redirCode after redirect"}"""
                    }
                    val redirBody = redirConn.inputStream.bufferedReader().use { it.readText() }
                    redirConn.disconnect()
                    return redirBody
                }
            }

            if (code != 200) {
                Log.e(TAG, "doFetch: HTTP $code")
                return """{"error":"HTTP $code"}"""
            }

            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val total = System.currentTimeMillis() - startTime
            Log.d(TAG, "doFetch: ${body.length} bytes in ${total}ms")
            return body
        } catch (e: InterruptedException) {
            Log.w(TAG, "doFetch: interrupted")
            return """{"error":"interrupted"}"""
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startTime
            Log.e(TAG, "doFetch: failed after ${elapsed}ms", e)
            val msg = "${e.javaClass.simpleName}: ${e.message ?: "unknown"}".replace("\"", "'").replace("\n", " ")
            return """{"error":"$msg"}"""
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    private fun deliverResult(requestId: String, json: String) {
        // Encode JSON as a JS string literal — simplest and safest is to JSON-quote it
        val escaped = jsStringLiteral(json)
        val safeId = jsStringLiteral(requestId)
        val js = "if(window.onQuoteResult)window.onQuoteResult($safeId,$escaped);"
        activity.runOnUiThread {
            try {
                webView.evaluateJavascript(js, null)
            } catch (e: Exception) {
                Log.e(TAG, "deliverResult: evaluateJavascript failed", e)
            }
        }
    }

    private fun jsStringLiteral(s: String): String {
        val sb = StringBuilder(s.length + 16)
        sb.append('"')
        for (ch in s) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\u2028' -> sb.append("\\u2028")
                '\u2029' -> sb.append("\\u2029")
                else -> if (ch.code < 0x20) sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
