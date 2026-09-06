package org.doubleduo.entrymobile

import android.content.ContentResolver
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.UUID

class ProjectSession(
    private val resolver: ContentResolver,
    private val assets: AssetManager,
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
    }

    fun clearProjectFiles() {
        reset()
        tempDir.mkdirs()
    }

    fun importPictures(uriStrings: JSONArray): JSONArray {
        val result = JSONArray()
        for (i in 0 until uriStrings.length()) result.put(importPicture(Uri.parse(uriStrings.getString(i))))
        return result
    }

    fun importSounds(uriStrings: JSONArray): JSONArray {
        val result = JSONArray()
        for (i in 0 until uriStrings.length()) result.put(importSound(Uri.parse(uriStrings.getString(i))))
        return result
    }

    fun importPicturesFromResource(pictures: JSONArray): JSONArray {
        val result = JSONArray()
        for (i in 0 until pictures.length()) {
            val picture = JSONObject(pictures.getJSONObject(i).toString())
            val oldId = picture.getString("filename")
            val ext = normalizedExt(picture.optString("ext", ".png"), "png")
            val prefix = "web/src/renderer/resources/uploads/${subPath(oldId)}"
            val newId = randomId()
            val imageName = "$newId.$ext"
            val imageDir = File(tempDir, "${subPath(newId)}/image").apply { mkdirs() }
            val thumbDir = File(tempDir, "${subPath(newId)}/thumb").apply { mkdirs() }
            val sourceName = "$oldId.$ext"
            assets.open("$prefix/image/$sourceName").use { copyStream(it, File(imageDir, imageName)) }
            runCatching { assets.open("$prefix/thumb/$sourceName").use { copyStream(it, File(thumbDir, imageName)) } }
                .getOrElse { createThumbnail(File(imageDir, imageName), File(thumbDir, imageName), ext) }
            if (picture.optString("imageType") == "svg") {
                runCatching {
                    assets.open("$prefix/image/$oldId.svg").use { copyStream(it, File(imageDir, "$newId.svg")) }
                }
            }
            picture.put("filename", newId)
            picture.put("fileurl", sessionUrl("temp/${subPath(newId)}/image/$imageName"))
            picture.put("thumbUrl", sessionUrl("temp/${subPath(newId)}/thumb/$imageName"))
            result.put(picture)
        }
        return result
    }

    fun importSoundsFromResource(sounds: JSONArray): JSONArray {
        val result = JSONArray()
        for (i in 0 until sounds.length()) {
            val sound = JSONObject(sounds.getJSONObject(i).toString())
            val oldId = sound.getString("filename")
            val ext = normalizedExt(sound.optString("ext", ".mp3"), "mp3")
            val prefix = "web/src/renderer/resources/uploads/${subPath(oldId)}"
            val oldName = "$oldId.$ext"
            val newId = randomId()
            val newName = "$newId.$ext"
            val soundDir = File(tempDir, "${subPath(newId)}/sound").apply { mkdirs() }
            val source = listOf("$prefix/$oldName", "$prefix/sound/$oldName").firstNotNullOfOrNull { candidate ->
                runCatching { assets.open(candidate) }.getOrNull()
            } ?: throw IllegalArgumentException("Bundled sound not found: $oldName")
            source.use { copyStream(it, File(soundDir, newName)) }
            sound.put("filename", newId)
            sound.put("fileurl", sessionUrl("temp/${subPath(newId)}/sound/$newName"))
            sound.put("path", sessionUrl("temp/${subPath(newId)}/sound/$newName"))
            result.put(sound)
        }
        return result
    }

    fun getExistingSoundUrl(sound: JSONObject): String {
        val id = sound.getString("filename")
        val ext = normalizedExt(sound.optString("ext", ".mp3"), "mp3")
        val relative = "temp/${subPath(id)}/sound/$id.$ext"
        return if (File(root, relative).isFile) sessionUrl(relative) else sessionUrl(relative)
    }

    fun resolveSessionFile(url: String): File? {
        val relative = when {
            url.startsWith(SESSION_PREFIX) -> url.removePrefix(SESSION_PREFIX)
            url.contains("/session/") -> url.substringAfter("/session/")
            url.startsWith("temp/") -> url
            else -> return null
        }
        return runCatching {
            val canonicalRoot = root.canonicalFile
            val file = File(root, relative).canonicalFile
            if ((file.path == canonicalRoot.path || file.path.startsWith(canonicalRoot.path + File.separator)) && file.isFile) file else null
        }.getOrNull()
    }

    private fun importPicture(uri: Uri): JSONObject {
        val displayName = displayName(uri, "picture.png")
        val ext = extension(uri, displayName, "png")
        val id = randomId()
        val imageDir = File(tempDir, "${subPath(id)}/image").apply { mkdirs() }
        val thumbDir = File(tempDir, "${subPath(id)}/thumb").apply { mkdirs() }
        val imageFile = File(imageDir, "$id.$ext")
        resolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Cannot import image" }
            copyStream(input, imageFile)
        }
        val thumbFile = File(thumbDir, "$id.$ext")
        createThumbnail(imageFile, thumbFile, ext)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(imageFile.absolutePath, bounds)
        val width = bounds.outWidth.takeIf { it > 0 } ?: 100
        val height = bounds.outHeight.takeIf { it > 0 } ?: 100
        return JSONObject().apply {
            put("_id", randomId())
            put("id", randomId())
            put("type", "user")
            put("name", displayName.substringBeforeLast('.', displayName))
            put("filename", id)
            put("fileurl", sessionUrl("temp/${subPath(id)}/image/${imageFile.name}"))
            put("thumbUrl", sessionUrl("temp/${subPath(id)}/thumb/${thumbFile.name}"))
            put("extension", ".$ext")
            put("ext", ".$ext")
            put("dimension", JSONObject().put("width", width).put("height", height))
            put("imageType", if (ext == "svg") "svg" else "png")
        }
    }

    private fun importSound(uri: Uri): JSONObject {
        val displayName = displayName(uri, "sound.mp3")
        val ext = extension(uri, displayName, "mp3")
        val id = randomId()
        val soundDir = File(tempDir, "${subPath(id)}/sound").apply { mkdirs() }
        val soundFile = File(soundDir, "$id.$ext")
        resolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Cannot import sound" }
            copyStream(input, soundFile)
        }
        val duration = runCatching {
            MediaMetadataRetriever().let { mmr ->
                try {
                    mmr.setDataSource(soundFile.absolutePath)
                    (mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toDoubleOrNull() ?: 0.0) / 1000.0
                } finally { mmr.release() }
            }
        }.getOrDefault(0.0)
        val url = sessionUrl("temp/${subPath(id)}/sound/${soundFile.name}")
        return JSONObject().apply {
            put("_id", randomId())
            put("type", "user")
            put("name", displayName.substringBeforeLast('.', displayName))
            put("filename", id)
            put("ext", ".$ext")
            put("fileurl", url)
            put("path", url)
            put("duration", kotlin.math.round(duration * 10.0) / 10.0)
        }
    }

    private fun createThumbnail(source: File, target: File, ext: String) {
        val bitmap = BitmapFactory.decodeFile(source.absolutePath)
        if (bitmap == null) {
            source.inputStream().use { copyStream(it, target) }
            return
        }
        val thumb = Bitmap.createScaledBitmap(bitmap, 96, 96, true)
        target.parentFile?.mkdirs()
        target.outputStream().use { output ->
            when (ext) {
                "jpg", "jpeg" -> thumb.compress(Bitmap.CompressFormat.JPEG, 90, output)
                "webp" -> thumb.compress(Bitmap.CompressFormat.WEBP, 90, output)
                else -> thumb.compress(Bitmap.CompressFormat.PNG, 100, output)
            }
        }
        if (thumb !== bitmap) thumb.recycle()
        bitmap.recycle()
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
            listOf("fileurl", "thumbUrl", "path").forEach { key ->
                if (item.has(key) && item.optString(key).isNotBlank()) item.put(key, fromExternal(item.optString(key)))
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
            listOf("fileurl", "thumbUrl", "path").forEach { key ->
                if (item.has(key) && item.optString(key).isNotBlank()) item.put(key, toExternal(item.optString(key)))
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
        if (result.startsWith(SESSION_PREFIX)) return result.removePrefix(SESSION_PREFIX).substringAfterLast("session/", result.removePrefix(SESSION_PREFIX))
        if (result.contains("/session/temp/")) return "temp/" + result.substringAfter("/session/temp/")
        if (result.startsWith("../../../node_modules")) return result.replaceFirst("../../../node_modules", "./bower_components")
        val tempIndex = result.indexOf("temp")
        return if (tempIndex >= 0) result.substring(tempIndex) else result
    }

    private fun sessionUrl(path: String) = "$SESSION_PREFIX${path.trimStart('/')}"

    private fun stripRemoteScheme(value: String): String {
        if (value.startsWith(SESSION_PREFIX)) return value
        return value.replace(Regex("^.*//"), "")
    }

    private fun displayName(uri: Uri, fallback: String): String {
        if (uri.scheme == "content") {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) return cursor.getString(0) ?: fallback
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: fallback
    }

    private fun extension(uri: Uri, displayName: String, fallback: String): String {
        val byName = displayName.substringAfterLast('.', "").lowercase()
        if (byName.matches(Regex("[a-z0-9]{1,6}"))) return byName
        return MimeTypeMap.getSingleton().getExtensionFromMimeType(resolver.getType(uri)) ?: fallback
    }

    private fun normalizedExt(value: String, fallback: String): String = value.trim().trimStart('.').lowercase().ifBlank { fallback }
    private fun subPath(id: String) = "${id.take(2)}/${id.drop(2).take(2)}"
    private fun randomId() = UUID.randomUUID().toString().replace("-", "")
    private fun copyStream(input: InputStream, destination: File) {
        destination.parentFile?.mkdirs()
        destination.outputStream().buffered().use { output -> input.buffered().copyTo(output) }
    }

    companion object {
        const val SESSION_PREFIX = "https://appassets.androidplatform.net/session/"
    }
}
