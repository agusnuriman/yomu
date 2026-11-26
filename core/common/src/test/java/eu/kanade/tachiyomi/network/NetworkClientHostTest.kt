package eu.kanade.tachiyomi.network

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import java.nio.file.Files

@Execution(ExecutionMode.CONCURRENT)
class NetworkClientHostTest {

    /**
     * Pembangunan client fiktif: mencatat setiap signature yang diterima dan instance yang
     * dikembalikan, supaya tes bisa membedakan "dibangun ulang" dari "client yang sama".
     */
    private class Recorder {
        val signatures = mutableListOf<ClientSignature>()
        val clients = mutableListOf<OkHttpClient>()

        val build: (NetworkResources, ClientSignature) -> OkHttpClient = { _, signature ->
            signatures += signature
            OkHttpClient().also { clients += it }
        }
    }

    /**
     * Dispatchers.Unconfined membuat emisi pref diproses inline oleh coroutine yang memanggil
     * set(), jadi tes membaca hasil rebuild tanpa perlu menunggu.
     */
    private fun newHost(preferences: NetworkPreferences, recorder: Recorder): NetworkClientHost = NetworkClientHost(
        scope = CoroutineScope(Dispatchers.Unconfined + Job()),
        preferences = preferences,
        resources = NetworkResources(Files.createTempDirectory("yomu-host").toFile()),
        build = recorder.build,
    )

    private fun newPreferences(): NetworkPreferences = NetworkPreferences(FlowPreferenceStore(), isDebugBuild = false)

    @Test
    fun buildsOnceOnStartAndIgnitionDoesNotRebuild() {
        val recorder = Recorder()
        val host = newHost(newPreferences(), recorder)

        recorder.signatures shouldBe listOf(ClientSignature(false, true))
        (host.client === recorder.clients.single()) shouldBe true
    }

    /**
     * Provider DNS tidak ikut membangun client: nilainya dibaca saat nama dicari, jadi client yang
     * lama tetap memakai pilihan terbaru. Membangun ulang di sini hanya membuang client aktif.
     */
    @Test
    fun doesNotRebuildWhenOnlyTheDnsProviderChanges() {
        val recorder = Recorder()
        val preferences = newPreferences()
        val host = newHost(preferences, recorder)
        val first = host.client

        preferences.dohProvider.set(PREF_DOH_CLOUDFLARE)

        recorder.signatures.size shouldBe 1
        (host.client === first) shouldBe true
    }

    @Test
    fun rebuildsWhenVerboseLoggingChanges() {
        val recorder = Recorder()
        val preferences = newPreferences()
        val host = newHost(preferences, recorder)
        val first = host.client

        preferences.verboseLogging.set(true)

        recorder.signatures.size shouldBe 2
        recorder.signatures.last() shouldBe ClientSignature(true, true)
        (host.client === first) shouldBe false
    }

    /**
     * Jalur notifikasi pref terbukti mati di perangkat uji, jadi getter harus memeriksa sendiri.
     * Host ini memakai scope yang sudah dibatalkan supaya kolektor tidak menolong tesnya: satu-satunya
     * kesempatan rebuild adalah saat client diambil.
     */
    @Test
    fun getterRebuildsWhenNoNotificationEverArrives() {
        val recorder = Recorder()
        val preferences = newPreferences()
        val host = NetworkClientHost(
            scope = CoroutineScope(Dispatchers.Unconfined + Job().apply { cancel() }),
            preferences = preferences,
            resources = NetworkResources(Files.createTempDirectory("yomu-host-quiet").toFile()),
            build = recorder.build,
        )
        val first = host.client

        preferences.verboseLogging.set(true)
        val second = host.client

        (second === first) shouldBe false
        recorder.signatures.last() shouldBe ClientSignature(true, true)
        (host.client === second) shouldBe true
        recorder.signatures.size shouldBe 2
    }

    @Test
    fun doesNotRebuildForIrrelevantChange() {
        val recorder = Recorder()
        val preferences = newPreferences()
        val host = newHost(preferences, recorder)
        val first = host.client

        preferences.dohProvider.set(-1)
        preferences.redactSensitiveHeaders.set(true)
        preferences.defaultUserAgent.set("Mozilla/5.0 (Linux; Android 10; K)")

        recorder.signatures.size shouldBe 1
        (host.client === first) shouldBe true
    }
}
