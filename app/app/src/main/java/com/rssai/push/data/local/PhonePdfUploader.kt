package com.rssai.push.data.local

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.rssai.push.data.ApiClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.File
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
                .sortedByDescending { it.length }
                .take(maxFiles)

            if (pdfs.isEmpty()) return@withContext PhonePdfUploadSummary()

            val parts = mutableListOf<MultipartBody.Part>()
            val errors = mutableListOf<String>()
            for (pdf in pdfs) {
                try {
                    parts += MultipartBody.Part.createFormData(
                        "files",
                        pdf.name,
                        PdfRequestBody(context.contentResolver, pdf)
                    )
                } catch (e: Exception) {
                    errors += "${pdf.name}: ${e.message}"
                }
            }
            if (parts.isEmpty()) {
                return@withContext PhonePdfUploadSummary(found = pdfs.size, errors = errors)
            }

            val response = ApiClient.api.uploadPdf(parts)
            val uploadErrors = response.errors.map { item ->
                val filename = item["filename"].orEmpty()
                val error = item["error"].orEmpty()
                if (filename.isBlank()) error else "$filename: $error"
            }.filter { it.isNotBlank() }
            PhonePdfUploadSummary(
                found = pdfs.size,
                uploaded = response.uploaded,
                errors = errors + uploadErrors
            )
        }

    private fun scanDownloads(maxAgeDays: Int): List<PhonePdf> {
        val cutoff = System.currentTimeMillis() - maxAgeDays * 24L * 60L * 60L * 1000L
        return scanPersistedDownloadTree(cutoff) + scanMediaStore(cutoff) + scanDirectDownload(cutoff)
    }

    private fun savedDownloadTreeUri(): Uri? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_DOWNLOAD_TREE_URI, null)
            ?: return null
        return runCatching { Uri.parse(raw) }.getOrNull()
    }

    private fun scanPersistedDownloadTree(cutoffMillis: Long): List<PhonePdf> {
        val treeUri = savedDownloadTreeUri() ?: return emptyList()
        if (!hasDownloadTreeAccess()) return emptyList()

        val result = mutableListOf<PhonePdf>()
        val childrenUri = try {
            val treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
            DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocumentId)
        } catch (_: Exception) {
            return emptyList()
        }
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        try {
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
                val modifiedCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameCol) ?: continue
                    val mime = cursor.getString(mimeCol).orEmpty()
                    if (mime != "application/pdf" && !name.endsWith(".pdf", ignoreCase = true)) continue
                    val size = cursor.getLong(sizeCol)
                    if (size < 20_000L) continue
                    val modified = cursor.getLong(modifiedCol)
                    if (modified > 0 && modified < cutoffMillis) continue
                    val documentId = cursor.getString(idCol)
                    val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
                    result += PhonePdf(name = name, length = size, uri = uri)
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
        val dirs = listOf(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "dlmanager")
        )
        return dirs.flatMap { dir ->
            try {
                dir.listFiles { file -> file.isFile && file.name.endsWith(".pdf", ignoreCase = true) }
                    ?.filter { it.length() >= 20_000L && it.lastModified() >= cutoffMillis && it.canRead() }
                    ?.map { PhonePdf(name = it.name, length = it.length(), file = it) }
                    .orEmpty()
            } catch (_: Exception) {
                emptyList()
            }
        }
    }
}

private const val PREFS = "phone_pdf_uploader"
private const val KEY_DOWNLOAD_TREE_URI = "downloadTreeUri"

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
