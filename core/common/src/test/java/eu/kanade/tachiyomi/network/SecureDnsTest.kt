package eu.kanade.tachiyomi.network

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import tachiyomi.core.common.preference.InMemoryPreferenceStore

@Execution(ExecutionMode.CONCURRENT)
class SecureDnsTest {

    private val blocked = "Failed to connect to api.mangadex.org/202.169.44.80:443"

    @Test
    fun testOfferWhenDohDisabled() {
        secureDnsOffer(blocked, dohProvider = -1) shouldBe true
    }

    @Test
    fun testNoOfferWhenDohEnabled() {
        secureDnsOffer(blocked, dohProvider = PREF_DOH_CLOUDFLARE) shouldBe false
    }

    @Test
    fun testNoOfferForNonDnsError() {
        secureDnsOffer("Failed to connect to api.mangadex.org/45.129.229.1:443", -1) shouldBe false
        secureDnsOffer("HTTP error 503", -1) shouldBe false
    }

    @Test
    fun testSecureDnsDisabledByDefault() {
        val preferences = NetworkPreferences(InMemoryPreferenceStore(), isDebugBuild = false)
        preferences.secureDnsEnabled() shouldBe false
    }

    @Test
    fun testEnableSecureDnsSetsCloudflareProvider() {
        val preferences = NetworkPreferences(InMemoryPreferenceStore(), isDebugBuild = false)
        preferences.enableSecureDns()
        preferences.dohProvider.get() shouldBe PREF_DOH_CLOUDFLARE
        preferences.secureDnsEnabled() shouldBe true
    }
}
