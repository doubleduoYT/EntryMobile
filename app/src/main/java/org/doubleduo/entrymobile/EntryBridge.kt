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
                    "loadProject" -> session.loadProject(args.getString(0))
                    "saveProject" -> {
                        val project = when (val raw = args.get(0)) {
                            is JSONObject -> raw.toString()
                            else -> raw.toString()
                        }
                        session.saveProject(project, args.getString(1))
                        true
                    }
                    "resetDirectory" -> { session.clearProjectFiles(); true }
                    "checkUpdate" -> JSONArray().put("2.1.35").put(JSONObject.NULL)
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
    fun openDialog(requestId: String, optionsJson: String) {
        activity.openDocument(requestId, optionsJson)
    }

    @JavascriptInterface
    fun saveDialog(requestId: String, optionsJson: String) {
        activity.createDocument(requestId, optionsJson)
    }

    @JavascriptInterface
    fun importResource(requestId: String, uri: String, kind: String) {
        io.execute {
            try {
                activity.resolveJs(requestId, true, session.importRaw(uri, kind).toString())
            } catch (t: Throwable) {
                activity.resolveJs(requestId, false, JSONObject().put("message", t.message).toString())
            }
        }
    }

    private fun normalize(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject, is JSONArray -> value.toString()
        is Boolean, is Number -> value.toString()
        else -> JSONObject.quote(value.toString())
    }
}
