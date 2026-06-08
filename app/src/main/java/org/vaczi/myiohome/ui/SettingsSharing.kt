package org.vaczi.myiohome.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Bridges the in-app settings backup to the OS share sheet (and back).
 *
 * Export writes the JSON to a file in the app cache and hands it out as a `content://` URI via
 * [FileProvider] (authority `<applicationId>.fileprovider`, see the manifest), so the standard Android
 * chooser can offer Gmail / SMS / etc. Import reads the bytes of an incoming `ACTION_VIEW` URI.
 */
object SettingsSharing {

    private const val SHARE_DIR = "shared"
    private const val SHARE_FILE = "myio-home-beallitasok.json"

    /** Write [json] to a cached file and launch the system share sheet for it (`application/json`). */
    fun share(context: Context, json: String) {
        val dir = File(context.cacheDir, SHARE_DIR).apply { mkdirs() }
        val file = File(dir, SHARE_FILE)
        file.writeText(json)
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "myIO Home beállítások")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Beállítások megosztása"))
    }

    /** Read the full text of [uri] (an incoming shared file), or null on any error. */
    fun readText(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
    }.getOrNull()
}
