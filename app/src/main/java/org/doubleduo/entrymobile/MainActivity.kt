package org.doubleduo.entrymobile

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewAssetLoader
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var session: ProjectSession
    private var pendingOpenRequest: String? = null
    private var pendingSaveRequest: String? = null
    private var pendingSaveName: String = "project.ent"

    private val openDocumentLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val id = pendingOpenRequest.also { pendingOpenRequest = null } ?: return@registerForActivityResult
        if (uri == null) resolveJs(id, true, "{\"canceled\":true,\"filePaths\":[]}")
        else resolveJs(id, true, JSONObject().put("canceled", false).put("filePaths", JSONArray().put(uri.toString())).toString())
    }

    private val createDocumentLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val id = pendingSaveRequest.also { pendingSaveRequest = null } ?: return@registerForActivityResult
        if (uri == null) resolveJs(id, true, "{\"canceled\":true}")
        else resolveJs(id, true, JSONObject().put("canceled", false).put("filePath", uri.toString()).toString())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = ProjectSession(contentResolver, assets, File(filesDir, "entry-session"))
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .addPathHandler("/session/", SessionPathHandler(session.filesRoot))
            .build()

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = false
            settings.allowContentAccess = true
            settings.setSupportZoom(true)
            settings.builtInZoomControls = false
            addJavascriptInterface(EntryBridge(this@MainActivity, session), "Android")
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    assetLoader.shouldInterceptRequest(request.url)

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val url = request.url
                    return if (url.host == "appassets.androidplatform.net") false else { openExternal(url.toString()); true }
                }

                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    intent?.data?.let { data ->
                        if (intent?.action == Intent.ACTION_VIEW) {
                            val js = "window.__entryMobileEmit && window.__entryMobileEmit('loadProjectFromMain', ${JSONObject.quote(data.toString())});"
                            view.postDelayed({ view.evaluateJavascript(js, null) }, 1500)
                        }
                    }
                }
            }
        }
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        setContentView(webView)
        val hasPreparedWeb = runCatching { assets.open("web/src/main/views/main.html").close(); true }.getOrDefault(false)
        webView.loadUrl(if (hasPreparedWeb) "https://appassets.androidplatform.net/assets/web/src/main/views/main.html" else "https://appassets.androidplatform.net/assets/web/fallback.html")
    }

    fun openDocument(requestId: String, optionsJson: String) {
        runOnUiThread {
            pendingOpenRequest = requestId
            openDocumentLauncher.launch(arrayOf("application/x-entryapp", "application/gzip", "application/octet-stream", "image/*", "audio/*", "text/*", "*/*"))
        }
    }

    fun createDocument(requestId: String, optionsJson: String) {
        runOnUiThread {
            pendingSaveRequest = requestId
            val options = runCatching { JSONObject(optionsJson) }.getOrNull()
            pendingSaveName = options?.optString("defaultPath")?.takeIf { it.isNotBlank() } ?: "project.ent"
            createDocumentLauncher.launch(pendingSaveName)
        }
    }

    fun resolveJs(requestId: String, ok: Boolean, payloadJson: String) {
        val js = "window.__entryMobileResolve && window.__entryMobileResolve(${JSONObject.quote(requestId)}, ${if (ok) "true" else "false"}, $payloadJson);"
        runOnUiThread { webView.evaluateJavascript(js, null) }
    }

    fun copyLogicalResourceToUri(logicalUrl: String, targetUri: String, session: ProjectSession) {
        session.resolveSessionFile(logicalUrl)?.let { file ->
            contentResolver.openOutputStream(Uri.parse(targetUri), "wt").use { out ->
                requireNotNull(out); file.inputStream().use { it.copyTo(out) }
            }
            return
        }
        val normalized = when {
            logicalUrl.startsWith("../../../node_modules/") -> "web/node_modules/" + logicalUrl.removePrefix("../../../node_modules/")
            logicalUrl.startsWith("renderer/") -> "web/src/renderer/" + logicalUrl.removePrefix("renderer/")
            logicalUrl.startsWith("./") -> "web/src/renderer/" + logicalUrl.removePrefix("./")
            else -> logicalUrl
        }
        copyAssetToUri(normalized, targetUri)
    }

    fun copyAssetToUri(assetPath: String, targetUri: String) {
        assets.open(assetPath).use { input ->
            contentResolver.openOutputStream(Uri.parse(targetUri), "wt").use { output ->
                requireNotNull(output) { "Cannot open output URI" }
                input.copyTo(output)
            }
        }
    }

    fun writeJsonValueToUri(value: Any, targetUri: String) {
        val bytes = when (value) {
            is String -> if (value.startsWith("data:") && value.contains(",")) {
                Base64.decode(value.substringAfter(','), Base64.DEFAULT)
            } else value.toByteArray(Charsets.UTF_8)
            is JSONObject, is JSONArray -> value.toString().toByteArray(Charsets.UTF_8)
            else -> value.toString().toByteArray(Charsets.UTF_8)
        }
        contentResolver.openOutputStream(Uri.parse(targetUri), "wt").use { output ->
            requireNotNull(output); output.write(bytes)
        }
    }

    fun openExternal(url: String) {
        if (url.isBlank()) return
        runOnUiThread { runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }
    }

    override fun onDestroy() {
        webView.removeJavascriptInterface("Android")
        webView.destroy()
        super.onDestroy()
    }
}
