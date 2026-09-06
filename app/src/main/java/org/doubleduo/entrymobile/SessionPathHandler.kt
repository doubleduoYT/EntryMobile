package org.doubleduo.entrymobile

import android.webkit.MimeTypeMap
import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import java.io.File

class SessionPathHandler(private val root: File) : WebViewAssetLoader.PathHandler {
    override fun handle(path: String): WebResourceResponse? {
        return try {
            val canonicalRoot = root.canonicalFile
            val target = File(root, path).canonicalFile
            if (!(target.path == canonicalRoot.path || target.path.startsWith(canonicalRoot.path + File.separator))) return null
            if (!target.isFile) return null
            val ext = target.extension.lowercase()
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: when (ext) {
                "svg" -> "image/svg+xml"
                "json" -> "application/json"
                "js" -> "application/javascript"
                "wasm" -> "application/wasm"
                else -> "application/octet-stream"
            }
            WebResourceResponse(mime, null, target.inputStream().buffered())
        } catch (_: Throwable) {
            null
        }
    }
}
