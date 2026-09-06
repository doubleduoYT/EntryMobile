package org.doubleduo.entrymobile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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
    private var pendingWebPermissionRequest: PermissionRequest? = null

    private val openDocumentLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val id = pendingOpenRequest.also { pendingOpenRequest = null } ?: return@registerForActivityResult
        if (uri == null) {
            resolveJs(id, true, "{\"canceled\":true,\"filePaths\":[]}")
        } else {
            persistReadPermission(uri)
            resolveJs(id, true, JSONObject().put("canceled", false).put("filePaths", JSONArray().put(uri.toString())).toString())
        }
    }

    private val openMultipleDocumentsLauncher =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            val id = pendingOpenRequest.also { pendingOpenRequest = null } ?: return@registerForActivityResult
            uris.forEach(::persistReadPermission)
            resolveJs(
                id,
                true,
                JSONObject()
                    .put("canceled", uris.isEmpty())
                    .put("filePaths", JSONArray().apply { uris.forEach { put(it.toString()) } })
                    .toString()
            )
        }

    private val createDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            val id = pendingSaveRequest.also { pendingSaveRequest = null } ?: return@registerForActivityResult
            if (uri == null) {
                resolveJs(id, true, "{\"canceled\":true}")
            } else {
                persistWritePermission(uri)
                resolveJs(id, true, JSONObject().put("canceled", false).put("filePath", uri.toString()).toString())
            }
        }

    private val mediaPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val request = pendingWebPermissionRequest.also { pendingWebPermissionRequest = null }
                ?: return@registerForActivityResult
            val required = androidPermissionsFor(request.resources)
            if (required.all { grants[it] == true || ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
                request.grant(request.resources)
            } else {
                request.deny()
            }
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
            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) {
                    runOnUiThread {
                        val androidPermissions = androidPermissionsFor(request.resources)
                        if (androidPermissions.isEmpty() || androidPermissions.all {
                                ContextCompat.checkSelfPermission(this@MainActivity, it) == PackageManager.PERMISSION_GRANTED
                            }) {
                            request.grant(request.resources)
                        } else if (pendingWebPermissionRequest == null) {
                            pendingWebPermissionRequest = request
                            mediaPermissionLauncher.launch(androidPermissions.toTypedArray())
                        } else {
                            request.deny()
                        }
                    }
                }

                override fun onPermissionRequestCanceled(request: PermissionRequest) {
                    if (pendingWebPermissionRequest == request) pendingWebPermissionRequest = null
                    super.onPermissionRequestCanceled(request)
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    assetLoader.shouldInterceptRequest(request.url)

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val url = request.url
                    return if (url.host == "appassets.androidplatform.net") false else {
                        openExternal(url.toString())
                        true
                    }
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

        val hasPreparedWeb = runCatching {
            assets.open("web/src/main/views/main.html").close()
            true
        }.getOrDefault(false)
        webView.loadUrl(
            if (hasPreparedWeb) "https://appassets.androidplatform.net/assets/web/src/main/views/main.html"
            else "https://appassets.androidplatform.net/assets/web/fallback.html"
        )
    }

    fun openDocument(requestId: String, optionsJson: String) {
        runOnUiThread {
            if (pendingOpenRequest != null) {
                resolveJs(requestId, false, JSONObject().put("message", "Another file dialog is already open").toString())
                return@runOnUiThread
            }
            pendingOpenRequest = requestId
            val options = runCatching { JSONObject(optionsJson) }.getOrNull()
            val properties = options?.optJSONArray("properties")
            var multi = false
            if (properties != null) {
                for (i in 0 until properties.length()) {
                    if (properties.optString(i) == "multiSelections") multi = true
                }
            }
            val mimeTypes = inferMimeTypes(options)
            if (multi) openMultipleDocumentsLauncher.launch(mimeTypes)
            else openDocumentLauncher.launch(mimeTypes)
        }
    }

    fun createDocument(requestId: String, optionsJson: String) {
        runOnUiThread {
            if (pendingSaveRequest != null) {
                resolveJs(requestId, false, JSONObject().put("message", "Another save dialog is already open").toString())
                return@runOnUiThread
            }
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
                requireNotNull(out)
                file.inputStream().use { it.copyTo(out) }
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
            requireNotNull(output)
            output.write(bytes)
        }
    }

    fun openExternal(url: String) {
        if (url.isBlank()) return
        runOnUiThread { runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }
    }

    private fun inferMimeTypes(options: JSONObject?): Array<String> {
        val result = linkedSetOf<String>()
        val filters = options?.optJSONArray("filters")
        if (filters != null) {
            for (i in 0 until filters.length()) {
                val extensions = filters.optJSONObject(i)?.optJSONArray("extensions") ?: continue
                for (j in 0 until extensions.length()) {
                    when (extensions.optString(j).lowercase()) {
                        "ent" -> result += "application/octet-stream"
                        "png" -> result += "image/png"
                        "jpg", "jpeg" -> result += "image/jpeg"
                        "gif" -> result += "image/gif"
                        "svg" -> result += "image/svg+xml"
                        "mp3" -> result += "audio/mpeg"
                        "wav" -> result += "audio/wav"
                        "csv" -> result += "text/csv"
                        "xlsx", "xls" -> result += "application/vnd.ms-excel"
                        "*" -> result += "*/*"
                    }
                }
            }
        }
        if (result.isEmpty()) result += "*/*"
        return result.toTypedArray()
    }

    private fun androidPermissionsFor(resources: Array<String>): List<String> {
        val permissions = mutableListOf<String>()
        if (resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) permissions += Manifest.permission.RECORD_AUDIO
        if (resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) permissions += Manifest.permission.CAMERA
        return permissions.distinct()
    }

    private fun persistReadPermission(uri: Uri) {
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    private fun persistWritePermission(uri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }

    override fun onDestroy() {
        pendingWebPermissionRequest?.deny()
        pendingWebPermissionRequest = null
        webView.removeJavascriptInterface("Android")
        webView.destroy()
        super.onDestroy()
    }
}
