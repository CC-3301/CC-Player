package com.cc3301.ccplayer.library

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * SAF 文件树：经 DocumentsContract 查询系统的文档提供者。
 *
 * 位置统一成 document URI（`content://…/tree/<树>/document/<文档>`）。ACTION_OPEN_DOCUMENT_TREE
 * 交回来的是 tree URI，[resolve] 先把它补成 document URI，之后每一级位置形状一致。
 */
class SafMediaFileTree(context: Context) : MediaFileTree {

    private val resolver = context.contentResolver

    override suspend fun resolve(location: String): MediaFile? = withContext(Dispatchers.IO) {
        try {
            val documentUri = documentUriOf(location.toUri())
            readDocument(documentUri)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (unreadable: Exception) {
            null
        }
    }

    override suspend fun children(directory: MediaFile): List<MediaFile> = withContext(Dispatchers.IO) {
        val directoryUri = directory.location.toUri()
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            directoryUri,
            DocumentsContract.getDocumentId(directoryUri),
        )
        val rows = resolver.query(childrenUri, PROJECTION, null, null, null)
            ?: throw IOException("文档提供者没有返回目录内容：${directory.location}")
        rows.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val childUri = DocumentsContract.buildDocumentUriUsingTree(directoryUri, cursor.getString(DOCUMENT_ID))
                    add(cursor.toMediaFile(childUri))
                }
            }
        }
    }

    private fun readDocument(documentUri: Uri): MediaFile? {
        val rows = resolver.query(documentUri, PROJECTION, null, null, null) ?: return null
        return rows.use { cursor -> if (cursor.moveToFirst()) cursor.toMediaFile(documentUri) else null }
    }

    private fun documentUriOf(location: Uri): Uri =
        if (location.pathSegments.size >= DOCUMENT_PATH_SEGMENTS) {
            location
        } else {
            DocumentsContract.buildDocumentUriUsingTree(location, DocumentsContract.getTreeDocumentId(location))
        }

    private fun Cursor.toMediaFile(uri: Uri): MediaFile = MediaFile(
        name = getString(DISPLAY_NAME) ?: "",
        location = uri.toString(),
        size = getLongOrZero(SIZE),
        lastModified = getLongOrZero(LAST_MODIFIED),
        isDirectory = getString(MIME_TYPE) == DocumentsContract.Document.MIME_TYPE_DIR,
    )

    private fun Cursor.getLongOrZero(column: Int): Long =
        if (isNull(column)) 0 else getLong(column)

    private companion object {
        /** `/tree/<树>/document/<文档>` 的段数；少于此说明拿到的是 tree URI。 */
        const val DOCUMENT_PATH_SEGMENTS = 4

        val PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )

        const val DOCUMENT_ID = 0
        const val DISPLAY_NAME = 1
        const val MIME_TYPE = 2
        const val SIZE = 3
        const val LAST_MODIFIED = 4
    }
}
