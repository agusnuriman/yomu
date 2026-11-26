package eu.kanade.tachiyomi.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.atomic.AtomicReference

/**
 * Pemegang OkHttpClient saat ini. Client dibangun ulang hanya kalau salah satu pref yang dibacanya
 * saat build berubah nilai.
 *
 * Perubahan itu diperiksa sendiri setiap kali client diambil, BUKAN hanya lewat notifikasi pref:
 * jalur notifikasi terbukti tidak pernah mengirim apa pun di perangkat uji, dan jalur itu cuma
 * mempercepat rebuild, tidak pernah menjadi satu-satunya yang menegakkan kebenaran client. Pilihan
 * DNS aman juga tidak lewat sini — ia dibaca saat nama dicari oleh [PreferenceDns], jadi client yang
 * sudah digenggam konsumen tidak pernah basi karenanya.
 *
 * Client lama tidak pernah ditutup: request yang sedang berjalan menyelesaikannya dengan
 * konfigurasi lama, sementara cache, dispatcher, dan connection pool tetap milik [resources] dan
 * dipakai client baru tanpa dibuat ulang.
 */
class NetworkClientHost(
    scope: CoroutineScope,
    private val preferences: NetworkPreferences,
    private val resources: NetworkResources,
    private val build: (NetworkResources, ClientSignature) -> OkHttpClient,
) {

    /**
     * Signature dan client milik signature itu, ditukar sebagai satu kesatuan supaya tidak pernah
     * ada pasangan yang tertukar di antara dua jalur rebuild.
     */
    private data class Built(val signature: ClientSignature, val client: OkHttpClient)

    private val state: AtomicReference<Built>

    init {
        val signature = preferences.clientSignature()
        state = AtomicReference(Built(signature, build(resources, signature)))
        scope.launch {
            merge(
                preferences.verboseLogging.changes().asBuildSignal(),
                preferences.redactSensitiveHeaders.changes().asBuildSignal(),
            ).collect { fresh() }
        }
    }

    /** Client yang cocok dengan pref saat ini. Konsumen wajib mengambil lewat getter ini. */
    val client: OkHttpClient get() = fresh().client

    /**
     * Membangun ulang kalau pref yang tertanam di dalam client sudah berubah nilainya. Dua jalur yang
     * balapan membangun dua client dan yang kalah dibuang: [AtomicReference.compareAndSet] membuat
     * paling banyak satu di antaranya yang dipublikasikan, jadi tidak ada client aktif yang lahir
     * dari signature lain.
     */
    private fun fresh(): Built {
        val current = state.get()
        val wanted = preferences.clientSignature()
        if (wanted == current.signature) return current
        val rebuilt = Built(wanted, build(resources, wanted))
        return if (state.compareAndSet(current, rebuilt)) rebuilt else state.get()
    }
}

/**
 * Aliran perubahan pref dibawa sebagai Flow<Unit>: nilainya tidak pernah dibutuhkan, karena
 * [NetworkClientHost] membaca pref itu sendiri saat memeriksa, dan tiap pref punya tipe yang
 * berbeda.
 */
private fun <T> Flow<T>.asBuildSignal(): Flow<Unit> = map { }
