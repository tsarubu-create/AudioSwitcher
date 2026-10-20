package io.github.tsarubucreate.audioswitcher.transcription

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri
import java.io.FileNotFoundException
import java.io.IOException

/**
 * 利用者が選んだフォルダ（Obsidian の Vault の中）に md を作り、追記する（Storage Access Framework）。
 *
 * フォルダは最初に1回だけ選んでもらい、その書き込みの許可を覚えておく（[TranscriptionSettings]）。
 */
class SafTranscriptOutput(private val resolver: ContentResolver, private val treeUri: Uri) : TranscriptOutput {
    override fun create(displayName: String): String {
        val folder = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        val created = try {
            DocumentsContract.createDocument(resolver, folder, MIME_TYPE, displayName)
        } catch (e: SecurityException) {
            throw IOException("保存先に書く許可がありません。保存先を選び直してください", e)
        } catch (e: FileNotFoundException) {
            throw IOException("保存先のフォルダが見つかりません。保存先を選び直してください", e)
        }
        return created?.toString() ?: throw IOException("ファイルを作れませんでした: $displayName")
    }

    override fun append(handle: String, text: String) {
        try {
            val stream = resolver.openOutputStream(handle.toUri(), "wa") ?: throw IOException("ファイルを開けませんでした")
            stream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        } catch (e: SecurityException) {
            throw IOException("保存先に書く許可がありません。保存先を選び直してください", e)
        } catch (e: FileNotFoundException) {
            throw IOException("ファイルが見つかりません（Obsidian などで消された・動かされた）", e)
        }
    }

    private companion object {
        const val MIME_TYPE = "text/markdown"
    }
}
