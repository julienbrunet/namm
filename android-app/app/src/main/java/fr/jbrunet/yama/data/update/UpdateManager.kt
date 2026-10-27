package fr.jbrunet.yama.data.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import fr.jbrunet.yama.BuildConfig
import fr.jbrunet.yama.data.remote.GitHubApi
import fr.jbrunet.yama.data.remote.ReleaseDto
import fr.jbrunet.yama.domain.AppVersion
import kotlinx.coroutines.delay
import java.io.File

data class AvailableUpdate(val version: AppVersion, val apkUrl: String, val notes: String?)

/**
 * Mise à jour depuis les releases GitHub : détection, téléchargement de l'APK
 * puis ouverture de l'installateur Android (l'utilisateur confirme l'installation).
 */
class UpdateManager(
    private val context: Context,
    private val api: GitHubApi,
    private val currentVersion: String = BuildConfig.VERSION_NAME,
) {

    suspend fun check(): AvailableUpdate? = api.latestRelease().toUpdate(currentVersion)

    /** Télécharge l'APK et renvoie le fichier, ou null en cas d'échec. */
    suspend fun download(update: AvailableUpdate, onProgress: (Float) -> Unit = {}): File? {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return null
        val file = File(dir, "yama-${update.version}.apk")
        dir.listFiles()?.filter { it.name.endsWith(".apk") && it != file }?.forEach { it.delete() }
        if (file.exists()) file.delete()

        val manager = context.getSystemService(DownloadManager::class.java)
        val id = manager.enqueue(
            DownloadManager.Request(Uri.parse(update.apkUrl))
                .setTitle("YAMA ${update.version}")
                .setMimeType(APK_MIME)
                .setDestinationUri(Uri.fromFile(file)),
        )
        while (true) {
            manager.query(DownloadManager.Query().setFilterById(id)).use { c ->
                if (!c.moveToFirst()) return null
                when (c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                    DownloadManager.STATUS_SUCCESSFUL -> return file.takeIf { isTrusted(it) } ?: run { file.delete(); null }
                    DownloadManager.STATUS_FAILED -> return null
                    else -> {
                        val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                        val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                        if (total > 0) onProgress(done.toFloat() / total)
                    }
                }
            }
            delay(500)
        }
    }

    /**
     * L'APK doit être cette appli, signée avec la même clé que la version installée : un fichier
     * altéré ou substitué dans le stockage partagé n'est jamais proposé à l'installation.
     */
    @Suppress("DEPRECATION")
    private fun isTrusted(apk: File): Boolean = runCatching {
        val pm = context.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val archive = pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES) ?: return false
            val installed = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            archive.packageName == context.packageName &&
                sameSigners(archive.signingInfo?.apkContentsSigners, installed.signingInfo?.apkContentsSigners)
        } else {
            val archive = pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNATURES) ?: return false
            val installed = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
            archive.packageName == context.packageName && sameSigners(archive.signatures, installed.signatures)
        }
    }.getOrDefault(false)

    private fun sameSigners(a: Array<Signature>?, b: Array<Signature>?) =
        !a.isNullOrEmpty() && !b.isNullOrEmpty() && a.toSet() == b.toSet()

    /** Ouvre l'installateur système (demande l'autorisation « sources inconnues » si besoin). */
    fun install(apk: File) {
        val pm = context.packageManager
        val intent = if (!pm.canRequestPackageInstalls()) {
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
        } else {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, APK_MIME).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    companion object {
        const val APK_MIME = "application/vnd.android.package-archive"
    }
}

internal fun ReleaseDto.toUpdate(currentVersion: String): AvailableUpdate? {
    val latest = AppVersion.parse(tagName) ?: return null
    val current = AppVersion.parse(currentVersion) ?: return null
    val apk = assets.firstOrNull { it.name.endsWith(".apk") } ?: return null
    return if (latest > current) AvailableUpdate(latest, apk.downloadUrl, body) else null
}
