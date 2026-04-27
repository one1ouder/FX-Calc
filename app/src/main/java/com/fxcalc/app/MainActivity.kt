package com.fxcalc.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
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
        return try {
            val url = URL("https://ntprogress.ru/local_api/public/east/")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Cache-Control", "no-cache")

            val code = conn.responseCode
            if (code != 200) {
                conn.disconnect()
                return """{"error":"HTTP $code"}"""
            }

            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            body
        } catch (e: Exception) {
            """{"error":"${e.message?.replace("\"", "'") ?: "network error"}"}"""
        }
    }
}
