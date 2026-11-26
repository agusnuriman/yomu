package tachiyomi.core.common.storage

import android.content.Context
import android.os.Environment
import androidx.core.net.toUri
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import java.io.File

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class AndroidStorageFolderProvider(
    private val context: Context,
) : FolderProvider {

    override fun directory(): File {
        val appName = context.stringResource(MR.strings.app_name)
        val publicDir = File(
            Environment.getExternalStorageDirectory().absolutePath + File.separator + appName,
        )
        // Pola yang sama dengan CoverCache: dir eksternal privat, internal kalau eksternal tak ada.
        val privateDir = context.getExternalFilesDir(appName) ?: File(context.filesDir, appName)
        return pickBaseDir(publicDir, privateDir)
    }

    override fun path(): String {
        return directory().toUri().toString()
    }
}

/**
 * Folder publik hanya dipakai kalau benar-benar bisa dipakai. Di Android 11+ (scoped storage) aplikasi
 * boleh membuat direktori di storage publik tapi tidak boleh membuat file di dalamnya: MediaProvider
 * menolaknya dengan EPERM, sehingga unduhan gagal tanpa pernah selesai. Sisa folder instalasi lama
 * dengan uid berbeda juga tidak bisa ditulisi. Folder eksternal privat tidak butuh izin sama sekali.
 */
internal fun pickBaseDir(publicDir: File, privateDir: File): File {
    return if (publicDir.mkdirs() || publicDir.canWrite()) publicDir else privateDir
}
