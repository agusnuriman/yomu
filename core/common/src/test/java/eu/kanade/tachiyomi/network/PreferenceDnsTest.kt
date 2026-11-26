package eu.kanade.tachiyomi.network

import io.kotest.matchers.shouldBe
import okhttp3.Dns
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import tachiyomi.core.common.preference.Preference
import java.net.InetAddress

/**
 * Inti janji "DNS aman langsung berlaku": pilihan pengguna dibaca pada saat nama dicari, bukan pada
 * saat client dibangun. Client yang sudah digenggam extension, request yang sedang berjalan, dan
 * notifikasi perubahan pref yang tidak pernah datang (terbukti di perangkat) tidak bisa membuat
 * keputusan ini basi.
 */
@Execution(ExecutionMode.CONCURRENT)
class PreferenceDnsTest {

    /** Resolusi palsu: mencatat siapa yang ditanya, tanpa menyentuh jaringan. */
    private class SpyDns : Dns {
        val asked = mutableListOf<String>()

        override fun lookup(hostname: String): List<InetAddress> {
            asked += hostname
            return listOf(InetAddress.getByName("127.0.0.1"))
        }
    }

    private fun dns(provider: Preference<Int>, system: SpyDns, secure: SpyDns): PreferenceDns =
        PreferenceDns(provider) { if (it == PREF_DOH_CLOUDFLARE) secure else system }

    @Test
    fun followsThePreferenceOnEveryLookup() {
        val provider = FlowPreference("doh_provider", -1)
        val system = SpyDns()
        val secure = SpyDns()
        val dns = dns(provider, system, secure)

        dns.lookup("api.mangadex.org")
        provider.set(PREF_DOH_CLOUDFLARE)
        dns.lookup("api.mangadex.org")
        provider.set(-1)
        dns.lookup("api.mangadex.org")

        system.asked shouldBe listOf("api.mangadex.org", "api.mangadex.org")
        secure.asked shouldBe listOf("api.mangadex.org")
    }

    @Test
    fun makesOneResolverPerProvider() {
        val provider = FlowPreference("doh_provider", PREF_DOH_CLOUDFLARE)
        val system = SpyDns()
        val secure = SpyDns()
        var built = 0
        val dns = PreferenceDns(provider) {
            built++
            if (it == PREF_DOH_CLOUDFLARE) secure else system
        }

        dns.lookup("api.mangadex.org")
        dns.lookup("sui.mangadex.org")

        built shouldBe 1
        secure.asked shouldBe listOf("api.mangadex.org", "sui.mangadex.org")
    }
}
