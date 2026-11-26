package eu.kanade.tachiyomi.network

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import java.nio.file.Files

@Execution(ExecutionMode.CONCURRENT)
class NetworkResourcesTest {

    @Test
    fun resourcesAreStableAcrossReads() {
        val dir = Files.createTempDirectory("yomu-net").toFile()
        val resources = NetworkResources(dir)
        resources.cache shouldBe resources.cache
        resources.dispatcher shouldBe resources.dispatcher
        resources.connectionPool shouldBe resources.connectionPool
    }

    @Test
    fun cachePointsAtTheGivenDirectory() {
        val dir = Files.createTempDirectory("yomu-net").toFile()
        NetworkResources(dir).cache.directory shouldBe dir
    }

    /**
     * Hanya nilai yang benar-benar tertanam di dalam client yang pantas ikut signature. Provider DNS
     * dibaca pada setiap pencarian nama (lihat PreferenceDnsTest), jadi ia tidak membuat client basi.
     */
    @Test
    fun signatureCoversOnlyWhatIsBakedIntoTheClient() {
        val preferences = NetworkPreferences(InMemoryPreferenceStore(), isDebugBuild = false)
        preferences.clientSignature() shouldBe ClientSignature(false, true)
        preferences.verboseLogging.set(true)
        preferences.redactSensitiveHeaders.set(false)
        preferences.clientSignature() shouldBe ClientSignature(true, false)
        preferences.dohProvider.set(PREF_DOH_CLOUDFLARE)
        preferences.clientSignature() shouldBe ClientSignature(true, false)
    }
}
