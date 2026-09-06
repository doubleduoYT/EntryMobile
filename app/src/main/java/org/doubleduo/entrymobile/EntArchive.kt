package org.doubleduo.entrymobile

import android.content.ContentResolver
import android.net.Uri
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipParameters
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream

object EntArchive {
    fun unpack(resolver: ContentResolver, uri: Uri, destination: File) {
        destination.mkdirs()
        val canonicalRoot = destination.canonicalFile
        resolver.openInputStream(uri).use { raw ->
            requireNotNull(raw) { "Cannot open .ent file" }
            TarArchiveInputStream(GzipCompressorInputStream(BufferedInputStream(raw))).use { tar ->
                var entry = tar.nextTarEntry
                while (entry != null) {
                    if (!entry.isSymbolicLink && !entry.isLink) {
                        val out = File(destination, entry.name).canonicalFile
                        require(out.path == canonicalRoot.path || out.path.startsWith(canonicalRoot.path + File.separator)) {
                            "Blocked unsafe path in archive: ${entry.name}"
                        }
                        if (entry.isDirectory) {
                            out.mkdirs()
                        } else {
                            out.parentFile?.mkdirs()
                            out.outputStream().buffered().use { tar.copyTo(it) }
                        }
                    }
                    entry = tar.nextTarEntry
                }
            }
        }
    }

    fun packTemp(resolver: ContentResolver, tempDir: File, uri: Uri) {
        require(tempDir.isDirectory) { "Project temp directory does not exist" }
        resolver.openOutputStream(uri, "wt").use { raw ->
            requireNotNull(raw) { "Cannot open output .ent file" }
            val gzip = GzipCompressorOutputStream(
                BufferedOutputStream(raw),
                GzipParameters().apply { compressionLevel = 6 }
            )
            TarArchiveOutputStream(gzip).use { tar ->
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                addRecursively(tar, tempDir, "temp")
                tar.finish()
            }
        }
    }

    private fun addRecursively(tar: TarArchiveOutputStream, file: File, archiveName: String) {
        if (file.isDirectory) {
            val dirName = if (archiveName.endsWith('/')) archiveName else "$archiveName/"
            val entry = TarArchiveEntry(file, dirName)
            tar.putArchiveEntry(entry)
            tar.closeArchiveEntry()
            file.listFiles()?.sortedBy { it.name }?.forEach {
                addRecursively(tar, it, "$archiveName/${it.name}")
            }
        } else {
            val entry = TarArchiveEntry(file, archiveName)
            entry.size = file.length()
            tar.putArchiveEntry(entry)
            FileInputStream(file).buffered().use { it.copyTo(tar) }
            tar.closeArchiveEntry()
        }
    }
}
