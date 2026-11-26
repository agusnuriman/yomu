package eu.kanade.tachiyomi.network

import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import java.io.File

/**
 * Sumber daya OkHttp yang dimiliki satu proses. Setiap client yang pernah dibangun berbagi objek
 * yang sama, jadi membangun ulang client tidak pernah membuka dua [Cache] di direktori yang sama
 * atau menambah dispatcher dan connection pool yang tidak pernah ditutup.
 */
class NetworkResources(cacheDir: File) {

    val cache: Cache = Cache(cacheDir, CACHE_SIZE)

    val dispatcher: Dispatcher = Dispatcher()

    val connectionPool: ConnectionPool = ConnectionPool()

    companion object {
        private const val CACHE_SIZE = 5L * 1024 * 1024 // 5 MiB
    }
}

/**
 * Pref yang nilainya tertanam di dalam [OkHttpClient] saat ia dibangun. Hanya perubahan pada salah
 * satu dari dua nilai ini yang pantas membangun client baru. Provider DNS tidak ikut: ia dibaca
 * pada setiap pencarian nama oleh [PreferenceDns], jadi client lama tidak pernah basi karenanya.
 */
data class ClientSignature(
    val verboseLogging: Boolean,
    val redactSensitiveHeaders: Boolean,
)

fun NetworkPreferences.clientSignature(): ClientSignature = ClientSignature(
    verboseLogging = verboseLogging.get(),
    redactSensitiveHeaders = redactSensitiveHeaders.get(),
)
