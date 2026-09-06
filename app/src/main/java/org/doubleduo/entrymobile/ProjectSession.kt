package org.doubleduo.entrymobile

import android.content.ContentResolver
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class ProjectSession(
    private val resolver: ContentResolver,
    private val root: File,
) {
    private val tempDir get() = File(root, "temp")
    val filesRoot: File get() = root

    init { root.mkdirs() }

    fun reset() {
        if (root.exists()) root.deleteRecursively()
        root.mkdirs()
    }

    fun loadProject(uriString: String): String {
        reset()
        EntArchive.unpack(resolver, Uri.parse(uriString), root)
        val projectFile = File(tempDir, "project.json")
        require(projectFile.isFile) { "temp/project.json was not found in this .ent" }
        val project = JSONObject(projectFile.readText(Charsets.UTF_8))
        rewriteForMobile(project)
        project.put("savedPath", uriString)
        return project.toString()
    }

    fun saveProject(projectJson: String, uriString: String) {
        val project = JSONObject(projectJson)
        project.remove("savedPath")
        rewriteForArchive(project)
        tempDir.mkdirs()
        File(tempDir, "project.json").writeText(project.toString(), Charsets.UTF_8)
        EntArchive.packTemp(resolver, tempDir, Uri.parse(uriString))
        // Restore live URLs in the in-memory JSON supplied by the renderer is unnecessary;
        // Entry re-exports the current workspace on the next save.
    }

    fun clearProjectFiles() {
        reset()
        tempDir.mkdirs()
    }

    fun importRaw(uriString: String, kind: String): JSONObject {
        val uri = Uri.parse(uriString)
        val id = UUID.randomUUID().toString().replace("-", "")
        val ext = extensionFor(uriString, if (kind == "sound") "mp3" else "png")
        val sub = "${id.substring(0, 2)}/${id.substring(2, 4)}/$kind"
        val destDir = File(tempDir, sub).apply { mkdirs() }
        val dest = File(destDir, "$id.$ext")
        resolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Cannot import selected file" }
            dest.outputStream().use { input.copyTo(it) }
        }
        return JSONObject().apply {
            put("filename", id)
            put("ext", ".$ext")
            put("fileurl", sessionUrl("temp/$sub/${dest.name}"))
            put("path", sessionUrl("temp/$sub/${dest.name}"))
        }
    }

    private fun rewriteForMobile(project: JSONObject) {
        val objects = project.optJSONArray("objects") ?: return
        for (i in 0 until objects.length()) {
            val sprite = objects.optJSONObject(i)?.optJSONObject("sprite") ?: continue
            rewriteMediaArrayForMobile(sprite.optJSONArray("pictures"))
            rewriteMediaArrayForMobile(sprite.optJSONArray("sounds"))
        }
    }

    private fun rewriteMediaArrayForMobile(items: JSONArray?) {
        if (items == null) return
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            listOf("fileurl", "thumbUrl").forEach { key ->
                if (item.has(key)) item.put(key, fromExternal(item.optString(key)))
            }
        }
    }

    private fun rewriteForArchive(project: JSONObject) {
        val objects = project.optJSONArray("objects") ?: return
        for (i in 0 until objects.length()) {
            val sprite = objects.optJSONObject(i)?.optJSONObject("sprite") ?: continue
            rewriteMediaArrayForArchive(sprite.optJSONArray("pictures"))
            rewriteMediaArrayForArchive(sprite.optJSONArray("sounds"))
        }
    }

    private fun rewriteMediaArrayForArchive(items: JSONArray?) {
        if (items == null) return
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            listOf("fileurl", "thumbUrl").forEach { key ->
                if (item.has(key)) item.put(key, toExternal(item.optString(key)))
            }
        }
    }

    private fun fromExternal(value: String): String {
        var result = value.replace("%5C", "\\", ignoreCase = true)
        when {
            result.startsWith("./bower_components") -> result = result
                .replaceFirst("./bower_components", "../../../node_modules")
                .replace("entryjs", "entry-js")
            result.startsWith("/lib") -> result = result.replaceFirst("/lib", "../../../node_modules")
            result.contains("temp") -> {
                result = result.substring(result.indexOf("temp")).replace('\\', '/')
                result = sessionUrl(result)
            }
            result.contains("/node_modules/@entrylabs/entry") -> result = result.replace(
                "/node_modules/@entrylabs/entry", "../../../node_modules/entry-js"
            )
        }
        return stripRemoteScheme(result)
    }

    private fun toExternal(value: String): String {
        var result = stripRemoteScheme(value).replace('\\', '/')
        if (result.startsWith(SESSION_PREFIX)) {
            return result.removePrefix(SESSION_PREFIX).substringAfter("temp", "temp").let { "temp$it" }
        }
        if (result.contains("/session/temp/")) return "temp/" + result.substringAfter("/session/temp/")
        if (result.startsWith("../../../node_modules")) {
            return result.replaceFirst("../../../node_modules", "./bower_components")
        }
        val tempIndex = result.indexOf("temp")
        return if (tempIndex >= 0) result.substring(tempIndex) else result
    }

    private fun sessionUrl(path: String) = "$SESSION_PREFIX${path.trimStart('/')}"

    private fun stripRemoteScheme(value: String): String {
        if (value.startsWith(SESSION_PREFIX)) return value
        return value.replace(Regex("^.*//"), "")
    }

    private fun extensionFor(uri: String, fallback: String): String {
        val tail = uri.substringAfterLast('/').substringBefore('?')
        val ext = tail.substringAfterLast('.', "").lowercase()
        return ext.takeIf { it.matches(Regex("[a-z0-9]{1,5}")) } ?: fallback
    }

    companion object {
        const val SESSION_PREFIX = "https://appassets.androidplatform.net/session/"
    }
}
