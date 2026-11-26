package eu.kanade.tachiyomi

// Identitas publik Yomu. Semua URL/user-agent yang menyebut proyek ini
// diambil dari sini supaya cukup diubah di satu tempat.
object YomuConstants {
    const val GITHUB_REPO = "agusnuriman/yomu"
    const val GITHUB_URL = "https://github.com/agusnuriman/yomu"

    // shortcut: belum ada halaman kebijakan privasi sendiri, sementara menunjuk
    // README repo; buat halaman policy sebelum distribusi publik yang luas.
    const val PRIVACY_URL = "$GITHUB_URL#readme"
}
