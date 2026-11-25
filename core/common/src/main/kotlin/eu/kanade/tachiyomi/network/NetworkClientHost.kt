package eu.kanade.tachiyomi.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * Pemegang OkHttpClient saat ini. Client dibangun ulang hanya kalau salah satu pref yang dibacanya
 * saat build berubah nilai — itu yang membuat DNS aman bisa aktif tanpa membuka ulang aplikasi.
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

    @Volatile
    private var current: OkHttpClient

    private var signature: ClientSignature

    /** Client yang cocok dengan pref terakhir. Konsumen wajib mengambil lewat getter ini. */
    val client: OkHttpClient get() = current

    init {
        signature = preferences.clientSignature()
        current = build(resources, signature)
        scope.launch {
            merge(
                preferences.dohProvider.changes().asBuildSignal(),
                preferences.verboseLogging.changes().asBuildSignal(),
                preferences.redactSensitiveHeaders.changes().asBuildSignal(),
            ).collect { rebuildIfChanged() }
        }
    }

    private fun rebuildIfChanged() {
        val newSignature = preferences.clientSignature()
        if (newSignature == signature) return
        signature = newSignature
        current = build(resources, newSignature)
    }
}

/**
 * Aliran perubahan pref dibawa sebagai Flow<Unit>: nilainya tidak pernah dibutuhkan, karena
 * [rebuildIfChanged] membacanya langsung dari pref, dan tiap pref punya tipe yang berbeda.
 */
private fun <T> Flow<T>.asBuildSignal(): Flow<Unit> = map { Unit }
