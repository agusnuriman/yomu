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

    @Test
    fun signatureReadsTheThreePrefs() {
        val preferences = NetworkPreferences(InMemoryPreferenceStore(), isDebugBuild = false)
        preferences.clientSignature() shouldBe ClientSignature(-1, false, true)
        preferences.dohProvider.set(PREF_DOH_CLOUDFLARE)
        preferences.verboseLogging.set(true)
        preferences.redactSensitiveHeaders.set(false)
        preferences.clientSignature() shouldBe ClientSignature(PREF_DOH_CLOUDFLARE, true, false)
    }
}
