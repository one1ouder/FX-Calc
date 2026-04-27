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

        webView.addJavascriptInterface(CalcBridge(dbHelper, this), "Android")
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

class CalcBridge(private val db: CalcDatabaseHelper, private val activity: MainActivity) {

    companion object {
        private const val TAG = "FXCalcBridge"
    }

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

    @JavascriptInterface
    fun fetchQuote(): String {
        // Endpoint redirects from /east/ to /east — call the final URL directly
        return doFetch("https://ntprogress.ru/local_api/public/east")
    }

    private fun doFetch(urlString: String): String {
        val startTime = System.currentTimeMillis()
        var conn: HttpURLConnection? = null
        try {
            Log.d(TAG, "fetchQuote: starting request to $urlString")
            val url = URL(urlString)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
                instanceFollowRedirects = true
                useCaches = false
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Cache-Control", "no-cache")
                setRequestProperty("User-Agent", "FXCalculator/2.0 (Android)")
                setRequestProperty("Connection", "close")
            }

            val code = conn.responseCode
            val elapsed = System.currentTimeMillis() - startTime
            Log.d(TAG, "fetchQuote: response code $code in ${elapsed}ms")

            // Handle redirects manually if instanceFollowRedirects didn't work
            if (code in 300..399) {
                val location = conn.getHeaderField("Location")
                if (location != null && location != urlString) {
                    Log.d(TAG, "fetchQuote: following redirect to $location")
                    conn.disconnect()
                    return doFetch(location)
                }
            }

            if (code != 200) {
                val errBody = try {
                    conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                } catch (e: Exception) { "" }
                Log.e(TAG, "fetchQuote: HTTP $code — $errBody")
                return """{"error":"HTTP $code"}"""
            }

            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val totalElapsed = System.currentTimeMillis() - startTime
            Log.d(TAG, "fetchQuote: ${body.length} bytes received in ${totalElapsed}ms total")
            return body
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startTime
            Log.e(TAG, "fetchQuote: failed after ${elapsed}ms — ${e.javaClass.simpleName}: ${e.message}", e)
            val msg = "${e.javaClass.simpleName}: ${e.message ?: "unknown"}".replace("\"", "'")
            return """{"error":"$msg"}"""
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }
}
