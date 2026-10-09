package io.github.revenge.xposed.modules.bridge

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.os.Environment
import android.util.Base64
import androidx.core.content.FileProvider
import io.github.revenge.xposed.Module
import org.xmlpull.v1.XmlPullParser
import java.io.File

/**
 * Registers `kettu.clipboard.copyImage`, which puts an image on the system clipboard.
 *
 * Android only pastes images from a `content://` URI, so the image is written into a directory
 * that one of the host app's own [FileProvider]s already exposes, and that URI is set as the clip.
 *
 * Args: `[base64: String, mimeType: String]`
 */
object ClipboardImageModule : Module() {
    private const val PATHS_META = "android.support.FILE_PROVIDER_PATHS"
    private const val FILE_PREFIX = "kettu_copy_image_"

    override fun onContext(context: Context) {
        BridgeModule.registerMethod("kettu.clipboard.copyImage") {
            val (base64, mime) = it
            copyImage(context, base64 as String, mime as? String)
        }
    }

    private fun copyImage(context: Context, base64: String, mime: String?): Map<String, Any?> {
        val bytes = Base64.decode(base64, Base64.DEFAULT)
        val ext = when {
            mime?.contains("png") == true -> "png"
            mime?.contains("gif") == true -> "gif"
            mime?.contains("webp") == true -> "webp"
            else -> "jpg"
        }

        val providers = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PROVIDERS or PackageManager.GET_META_DATA)
            .providers.orEmpty()
        val usable = providers.filter { it.grantUriPermissions && it.metaData?.getInt(PATHS_META, 0) != 0 }

        val errors = mutableListOf<String>()
        for (provider in usable) {
            val authority = provider.authority?.split(";")?.firstOrNull() ?: continue
            for (dir in exposedDirs(context, provider)) {
                try {
                    dir.mkdirs()
                    dir.listFiles { f -> f.name.startsWith(FILE_PREFIX) }?.forEach { f -> f.delete() }

                    val file = File(dir, "$FILE_PREFIX${System.currentTimeMillis()}.$ext")
                    file.writeBytes(bytes)

                    val uri = FileProvider.getUriForFile(context, authority, file)
                    val clip = ClipData.newUri(context.contentResolver, "image", uri)
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(clip)

                    return mapOf(
                        "ok" to true,
                        "uri" to uri.toString(),
                        "type" to clip.description.getMimeType(0),
                    )
                } catch (e: Throwable) {
                    errors += "$authority ${dir.path}: ${e.javaClass.simpleName} ${e.message}"
                }
            }
        }

        throw Error(
            "No usable FileProvider. providers=" +
                providers.joinToString { "${it.name}[${it.authority},grant=${it.grantUriPermissions}]" } +
                " errors=" + errors.joinToString(" | ")
        )
    }

    /** Directories declared in the provider's `FILE_PROVIDER_PATHS` XML, mapped to real paths. */
    private fun exposedDirs(context: Context, provider: ProviderInfo): List<File> {
        val dirs = mutableListOf<File>()
        val parser = context.resources.getXml(provider.metaData.getInt(PATHS_META))
        try {
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    val base: File? = when (parser.name) {
                        "root-path" -> File("/")
                        "files-path" -> context.filesDir
                        "cache-path" -> context.cacheDir
                        "external-path" -> Environment.getExternalStorageDirectory()
                        "external-files-path" -> context.getExternalFilesDir(null)
                        "external-cache-path" -> context.externalCacheDir
                        "external-media-path" -> context.externalMediaDirs.firstOrNull()
                        else -> null
                    }
                    if (base != null) dirs += File(base, parser.getAttributeValue(null, "path") ?: "")
                }
                event = parser.next()
            }
        } finally {
            parser.close()
        }
        return dirs
    }
}
