package io.github.tigaotg.openkinguard

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract

/** The user's own Storage Access Framework folder; no file content is copied or uploaded. */
internal data class PersonalMedicalEntry(
    val documentId: String,
    val name: String,
    val mimeType: String,
) {
    val isFolder: Boolean get() = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
}

internal object PersonalMedicalFolder {
    private const val MAX_DISPLAYED_ITEMS = 250

    fun rootId(tree: Uri): String {
        require(tree.scheme == "content" && DocumentsContract.isTreeUri(tree)) {
            "请重新选择具有读取权限的病历文件夹"
        }
        return DocumentsContract.getTreeDocumentId(tree)
    }

    fun title(context: Context, tree: Uri): String {
        val document = DocumentsContract.buildDocumentUriUsingTree(tree, rootId(tree))
        context.contentResolver.query(document,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0).orEmpty().take(60)
        }
        return "已选病历文件夹"
    }

    fun children(context: Context, tree: Uri, parentId: String): List<PersonalMedicalEntry> {
        rootId(tree)
        val listUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE)
        val files = mutableListOf<PersonalMedicalEntry>()
        context.contentResolver.query(listUri, columns, null, null, null)?.use { cursor ->
            while (cursor.moveToNext() && files.size < MAX_DISPLAYED_ITEMS) {
                val id = cursor.getString(0) ?: continue
                val name = cursor.getString(1)?.trim().orEmpty().take(100)
                val mimeType = cursor.getString(2).orEmpty().ifBlank { "application/octet-stream" }
                files += PersonalMedicalEntry(id, name.ifBlank { "未命名文件" }, mimeType)
            }
        } ?: throw IllegalStateException("无法读取该文件夹；可能已被移动或访问授权失效")
        return files.sortedWith(compareByDescending<PersonalMedicalEntry> { it.isFolder }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    fun openFile(context: Context, tree: Uri, item: PersonalMedicalEntry) {
        require(!item.isFolder) { "文件夹应在应用内打开" }
        val document = DocumentsContract.buildDocumentUriUsingTree(tree, item.documentId)
        context.startActivity(Intent(Intent.ACTION_VIEW)
            .setDataAndType(document, item.mimeType)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }
}
