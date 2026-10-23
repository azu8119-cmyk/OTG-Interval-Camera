package com.example.otginterval

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File

/** Uses Storage Access Framework; no broad storage permission needed. */
object PhotoWriter {
    fun moveToChosenFolder(context: Context, treeUri: Uri, source: String, name: String) {
        val document = DocumentFile.fromTreeUri(context, treeUri)
            ?: error("Выбранная папка недоступна")
        if (!document.canWrite()) error("Нет разрешения на запись в папку")
        val dest = document.createFile("image/jpeg", name)
            ?: error("Не удалось создать файл в папке")
        try {
            val input = if (source.startsWith("content://")) {
                context.contentResolver.openInputStream(Uri.parse(source))
            } else {
                File(source).inputStream()
            } ?: error("Не удалось прочитать временный снимок")
            input.use { stream ->
                context.contentResolver.openOutputStream(dest.uri, "w")?.use { output ->
                    stream.copyTo(output)
                    output.flush()
                } ?: error("Не удалось открыть целевой файл")
            }
        } catch (ex: Exception) {
            dest.delete()
            throw ex
        } finally {
            if (!source.startsWith(ContentResolver.SCHEME_CONTENT + "://")) {
                File(source).delete()
            }
        }
    }
}
