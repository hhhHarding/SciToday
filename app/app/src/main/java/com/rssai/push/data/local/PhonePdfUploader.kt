package com.rssai.push.data.local

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import com.rssai.push.data.ApiClient
import com.rssai.push.data.PdfUploadResponse
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

data class PhonePdfUploadSummary(
    val found: Int = 0,
    val uploaded: Int = 0,
    val errors: List<String> = emptyList()
)

private data class PhonePdf(
    val name: String,
    val length: Long,
    val uri: Uri? = null,
    val file: File? = null
)

@Singleton
class PhonePdfUploader @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    fun hasDownloadTreeAccess(): Boolean {
        val saved = savedDownloadTreeUri() ?: return false
        return context.contentResolver.persistedUriPermissions.any {
            it.uri == saved && it.isReadPermission
        }
    }

    fun persistDownloadTree(uri: Uri) {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DOWNLOAD_TREE_URI, uri.toString())
            .apply()
    }

    suspend fun uploadRecentDownloads(maxAgeDays: Int = 21, maxFiles: Int = 20): PhonePdfUploadSummary =
        withContext(Dispatchers.IO) {
            val pdfs = scanDownloads(maxAgeDays)
                .distinctBy { it.name.lowercase() + ":" + it.length }
                .filter { it.uploadKey() !in uploadedPdfKeys() }
                .sortedBy { it.length }
                .take(maxFiles)

            Log.i(TAG, "uploadRecentDownloads: found=${pdfs.size}, maxFiles=$maxFiles")
            if (pdfs.isEmpty()) return@withContext PhonePdfUploadSummary()

            val errors = mutableListOf<String>()
            var uploaded = 0
            for (pdf in pdfs) {
                try {
                    val response = uploadPdf(pdf)
                    uploaded += response.uploaded
                    response.errors.mapTo(errors) { item ->
                        val filename = item["filename"].orEmpty()
                        val error = item["error"].orEmpty()
                        if (filename.isBlank()) error else "$filename: $error"
                    }
                    if (response.uploaded > 0) markPdfUploaded(pdf)
                    Log.i(TAG, "uploadRecentDownloads: ${pdf.name} uploaded=${response.uploaded}, errors=${response.errors.size}")
                } catch (e: Exception) {
                    errors += "${pdf.name}: ${e.message}"
                    Log.w(TAG, "uploadRecentDownloads failed: ${pdf.name}: ${e.message}")
                }
            }
            Log.i(TAG, "uploadRecentDownloads: uploaded=$uploaded, errors=${errors.size}")
            PhonePdfUploadSummary(
                found = pdfs.size,
                uploaded = uploaded,
                errors = errors.filter { it.isNotBlank() }
            )
        }

    private suspend fun uploadPdf(pdf: PhonePdf): PdfUploadResponse =
        if (pdf.length >= CHUNK_UPLOAD_THRESHOLD_BYTES) {
            uploadPdfInChunks(pdf)
        } else {
            uploadPdfDirect(pdf)
        }

    private suspend fun uploadPdfDirect(pdf: PhonePdf): PdfUploadResponse {
        val part = MultipartBody.Part.createFormData(
            "files",
            pdf.name,
            PdfRequestBody(context.contentResolver, pdf)
        )
        return ApiClient.api.uploadPdf(listOf(part))
    }

    private suspend fun uploadPdfInChunks(pdf: PhonePdf): PdfUploadResponse {
        val total = ((pdf.length + PDF_CHUNK_SIZE_BYTES - 1) / PDF_CHUNK_SIZE_BYTES).toInt()
        val uploadId = pdf.uploadId()
        var latest = PdfUploadResponse(ok = true)
        for (index in 0 until total) {
            val offset = index * PDF_CHUNK_SIZE_BYTES
            val byteCount = minOf(PDF_CHUNK_SIZE_BYTES, pdf.length - offset)
            val part = MultipartBody.Part.createFormData(
                "chunk",
                pdf.name,
                PdfChunkRequestBody(context.contentResolver, pdf, offset, byteCount)
            )
            latest = ApiClient.api.uploadPdfChunk(
                chunk = part,
                uploadId = uploadId.toTextBody(),
                filename = pdf.name.toTextBody(),
                index = index.toString().toTextBody(),
                total = total.toString().toTextBody()
            )
            if (latest.errors.isNotEmpty()) return latest
            Log.i(TAG, "uploadRecentDownloads: ${pdf.name} chunk=${index + 1}/$total uploaded=${latest.uploaded}")
        }
        return latest
    }

    private fun scanDownloads(maxAgeDays: Int): List<PhonePdf> {
        val cutoff = System.currentTimeMillis() - maxAgeDays * 24L * 60L * 60L * 1000L
        val tree = scanPersistedDownloadTree(cutoff)
        val media = scanMediaStore(cutoff)
        val direct = scanDirectDownload(cutoff)
        Log.i(
            TAG,
            "scanDownloads: tree=${tree.size}, media=${media.size}, direct=${direct.size}, " +
                "allFiles=${hasAllFilesAccess()}, treeAccess=${hasDownloadTreeAccess()}"
        )
        return tree + media + direct
    }

    private fun savedDownloadTreeUri(): Uri? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_DOWNLOAD_TREE_URI, null)
            ?: return null
        return runCatching { Uri.parse(raw) }.getOrNull()
    }

    private fun uploadedPdfKeys(): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_UPLOADED_PDF_KEYS, emptySet())
            .orEmpty()

    private fun markPdfUploaded(pdf: PhonePdf) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val next = prefs.getStringSet(KEY_UPLOADED_PDF_KEYS, emptySet()).orEmpty().toMutableSet()
        next += pdf.uploadKey()
        prefs.edit().putStringSet(KEY_UPLOADED_PDF_KEYS, next).apply()
    }

    private fun PhonePdf.uploadKey(): String = "${name.lowercase()}:$length"

    private fun PhonePdf.uploadId(): String =
        sha256Hex("${name.lowercase()}:$length").take(32)

    private fun scanPersistedDownloadTree(cutoffMillis: Long): List<PhonePdf> {
        val treeUri = savedDownloadTreeUri() ?: return emptyList()
        if (!hasDownloadTreeAccess()) return emptyList()

        val result = mutableListOf<PhonePdf>()
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        val pending = ArrayDeque<String>()
        val rootDocumentId = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (_: Exception) {
            return emptyList()
        }
        pending.add(rootDocumentId)
        try {
            while (pending.isNotEmpty() && result.size < 200) {
                val parentId = pending.removeFirst()
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
                context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    val sizeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
                    val modifiedCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                    while (cursor.moveToNext()) {
                        val documentId = cursor.getString(idCol)
                        val name = cursor.getString(nameCol) ?: continue
                        val mime = cursor.getString(mimeCol).orEmpty()
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            pending.add(documentId)
                            continue
                        }
                        if (mime != "application/pdf" && !name.endsWith(".pdf", ignoreCase = true)) continue
                        val size = cursor.getLong(sizeCol)
                        if (size < 20_000L) continue
                        val modified = cursor.getLong(modifiedCol)
                        if (modified > 0 && modified < cutoffMillis) continue
                        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
                        result += PhonePdf(name = name, length = size, uri = uri)
                    }
                }
            }
        } catch (_: Exception) {
        }
        return result
    }

    private fun scanMediaStore(cutoffMillis: Long): List<PhonePdf> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return emptyList()
        }
        val result = mutableListOf<PhonePdf>()
        val projection = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DISPLAY_NAME,
            MediaStore.Downloads.SIZE,
            MediaStore.Downloads.DATE_MODIFIED,
            MediaStore.Downloads.MIME_TYPE
        )
        val selection = "(${MediaStore.Downloads.MIME_TYPE}=? OR ${MediaStore.Downloads.DISPLAY_NAME} LIKE ?) AND ${MediaStore.Downloads.DATE_MODIFIED}>=?"
        val args = arrayOf(
            "application/pdf",
            "%.pdf",
            (cutoffMillis / 1000L).toString()
        )
        val sort = "${MediaStore.Downloads.DATE_MODIFIED} DESC"
        try {
            context.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                args,
                sort
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameCol) ?: continue
                    if (!name.endsWith(".pdf", ignoreCase = true)) continue
                    val size = cursor.getLong(sizeCol)
                    if (size < 20_000L) continue
                    val id = cursor.getLong(idCol)
                    val uri = Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id.toString())
                    result += PhonePdf(name = name, length = size, uri = uri)
                }
            }
        } catch (_: Exception) {
        }
        return result
    }

    private fun scanDirectDownload(cutoffMillis: Long): List<PhonePdf> {
        if (!hasAllFilesAccess()) return emptyList()
        val dirs = listOf(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "dlmanager")
        )
        val result = linkedMapOf<String, PhonePdf>()
        dirs.forEach { dir ->
            try {
                if (!dir.exists()) return@forEach
                dir.walkTopDown()
                    .maxDepth(3)
                    .filter { file ->
                        file.isFile &&
                            file.name.endsWith(".pdf", ignoreCase = true) &&
                            file.length() >= 20_000L &&
                            file.lastModified() >= cutoffMillis &&
                            file.canRead()
                    }
                    .take(200)
                    .forEach { file ->
                        result[file.absolutePath] = PhonePdf(name = file.name, length = file.length(), file = file)
                    }
            } catch (_: Exception) {
            }
        }
        return result.values.toList()
    }
}

private const val PREFS = "phone_pdf_uploader"
private const val KEY_DOWNLOAD_TREE_URI = "downloadTreeUri"
private const val KEY_UPLOADED_PDF_KEYS = "uploadedPdfKeys"
private const val TAG = "PhonePdfUploader"
private const val CHUNK_UPLOAD_THRESHOLD_BYTES = 1_000_000L
private const val PDF_CHUNK_SIZE_BYTES = 512L * 1024L

private class PdfRequestBody(
    private val resolver: ContentResolver,
    private val pdf: PhonePdf
) : RequestBody() {
    override fun contentType() = "application/pdf".toMediaType()

    override fun contentLength(): Long = pdf.length

    override fun writeTo(sink: BufferedSink) {
        val input = pdf.file?.inputStream() ?: resolver.openInputStream(pdf.uri!!)
        input.use { stream ->
            requireNotNull(stream) { "无法读取 PDF" }
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = stream.read(buffer)
                if (read == -1) break
                sink.write(buffer, 0, read)
            }
        }
    }
}

private class PdfChunkRequestBody(
    private val resolver: ContentResolver,
    private val pdf: PhonePdf,
    private val offset: Long,
    private val byteCount: Long
) : RequestBody() {
    override fun contentType() = "application/pdf".toMediaType()

    override fun contentLength(): Long = byteCount

    override fun writeTo(sink: BufferedSink) {
        val input = pdf.file?.inputStream() ?: resolver.openInputStream(pdf.uri!!)
        input.use { stream ->
            requireNotNull(stream) { "无法读取 PDF" }
            skipFully(stream, offset)
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var remaining = byteCount
            while (remaining > 0L) {
                val read = stream.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read == -1) break
                sink.write(buffer, 0, read)
                remaining -= read
            }
            if (remaining > 0L) {
                throw IllegalStateException("PDF 分片读取不完整")
            }
        }
    }
}

private fun skipFully(stream: InputStream, bytes: Long) {
    var remaining = bytes
    while (remaining > 0L) {
        val skipped = stream.skip(remaining)
        if (skipped > 0L) {
            remaining -= skipped
            continue
        }
        if (stream.read() == -1) {
            throw IllegalStateException("PDF 分片定位失败")
        }
        remaining -= 1L
    }
}

private fun String.toTextBody(): RequestBody =
    toRequestBody("text/plain".toMediaType())

private fun sha256Hex(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
