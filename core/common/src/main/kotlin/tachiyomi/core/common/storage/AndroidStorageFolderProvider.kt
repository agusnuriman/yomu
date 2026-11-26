package tachiyomi.core.common.storage

import android.content.Context
import android.os.Environment
import androidx.core.net.toUri
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.io.File

/**
 * Nama folder di disk. Sengaja tidak mengikuti string app_name: mengubahnya memindahkan akar
 * unduhan dan folder simpan gambar, sehingga unduhan lama tidak terlihat lagi oleh aplikasi.
 * Migrasi data harus eksplisit, bukan efek samping dari ganti nama tampilan.
 */
const val STORAGE_DIR_NAME = "Mihon"

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class AndroidStorageFolderProvider(
    private val context: Context,
) : FolderProvider {

    override fun directory(): File {
        val publicDir = File(
            Environment.getExternalStorageDirectory().absolutePath + File.separator + STORAGE_DIR_NAME,
        )
        // Pola yang sama dengan CoverCache: dir eksternal privat, internal kalau eksternal tak ada.
        val privateDir = context.getExternalFilesDir(STORAGE_DIR_NAME) ?: File(context.filesDir, STORAGE_DIR_NAME)
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
