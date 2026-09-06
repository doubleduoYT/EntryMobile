package org.doubleduo.entrymobile

import android.webkit.JavascriptInterface
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

class EntryBridge(private val activity: MainActivity, private val session: ProjectSession) {
    private val io = Executors.newSingleThreadExecutor()

    @JavascriptInterface
    fun invoke(requestId: String, channel: String, argsJson: String) {
        io.execute {
            try {
                val args = JSONArray(argsJson)
                val result: Any? = when (channel) {
                    "loadProject" -> JSONObject(session.loadProject(args.getString(0)))
                    "saveProject" -> {
                        session.saveProject(args.getJSONObject(0).toString(), args.getString(1)); true
                    }
                    "resetDirectory" -> { session.clearProjectFiles(); true }
                    "importPictures" -> session.importPictures(args.getJSONArray(0))
                    "importSounds" -> session.importSounds(args.getJSONArray(0))
                    "importPicturesFromResource" -> session.importPicturesFromResource(args.getJSONArray(0))
                    "importSoundsFromResource" -> session.importSoundsFromResource(args.getJSONArray(0))
                    "getExistSoundFilePath" -> session.getExistingSoundUrl(args.getJSONObject(0))
                    "tempResourceDownload" -> {
                        val item = args.getJSONObject(0)
                        activity.copyLogicalResourceToUri(item.optString("fileurl"), args.getString(2), session)
                        true
                    }
                    "staticDownload" -> {
                        val segments = args.getJSONArray(0)
                        val path = buildString {
                            for (i in 0 until segments.length()) {
                                if (i > 0) append('/')
                                append(segments.getString(i))
                            }
                        }
                        activity.copyAssetToUri("web/src/main/static/$path", args.getString(1)); true
                    }
                    "writeFile" -> { activity.writeJsonValueToUri(args.get(0), args.getString(1)); true }
                    "checkUpdate" -> JSONArray().put("2.1.35").put(
                        JSONObject().put("hasNewVersion", false).put("recentVersion", "2.1.35")
                    )
                    "isValidAsarFile" -> true
                    "getOpenSourceText" -> ""
                    "getPapagoHeaderInfo" -> JSONObject.NULL
                    "checkPermission" -> true
                    "quit" -> { activity.runOnUiThread { activity.finish() }; true }
                    "openUrl" -> { activity.openExternal(args.optString(0)); true }
                    else -> throw UnsupportedOperationException("IPC channel not ported yet: $channel")
                }
                activity.resolveJs(requestId, true, normalize(result))
            } catch (t: Throwable) {
                activity.resolveJs(requestId, false, JSONObject().put("message", t.message ?: t.javaClass.simpleName).toString())
            }
        }
    }

    @JavascriptInterface
    fun openDialog(requestId: String, optionsJson: String) = activity.openDocument(requestId, optionsJson)

    @JavascriptInterface
    fun saveDialog(requestId: String, optionsJson: String) = activity.createDocument(requestId, optionsJson)

    private fun normalize(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject, is JSONArray -> value.toString()
        is Boolean, is Number -> value.toString()
        else -> JSONObject.quote(value.toString())
    }
}
