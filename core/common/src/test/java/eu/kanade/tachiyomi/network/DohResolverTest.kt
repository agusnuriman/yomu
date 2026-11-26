package eu.kanade.tachiyomi.network

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/**
 * Setiap provider yang muncul di Pengaturan harus benar-benar bisa dibangun. Kalau satu URL atau
 * alamat bootstrapnya tidak bisa dibaca, pengguna cukup memilih provider itu lalu aplikasinya
 * mati saat nama dicari — itu kegagalan yang tes ini jaga.
 */
@Execution(ExecutionMode.CONCURRENT)
class DohResolverTest {

    /** Client transport untuk DoH: di tes tidak pernah dipakai untuk menghubungi apa pun. */
    private val bootstrap = OkHttpClient()

    private val allProviders = listOf(
        PREF_DOH_CLOUDFLARE,
        PREF_DOH_GOOGLE,
        PREF_DOH_ADGUARD,
        PREF_DOH_QUAD9,
        PREF_DOH_ALIDNS,
        PREF_DOH_DNSPOD,
        PREF_DOH_360,
        PREF_DOH_QUAD101,
        PREF_DOH_MULLVAD,
        PREF_DOH_CONTROLD,
        PREF_DOH_NJALLA,
        PREF_DOH_SHECAN,
    )

    @Test
    fun everyListedProviderBuildsAResolver() {
        allProviders.forEach { provider ->
            dohResolver(provider, bootstrap) shouldNotBe null
        }
    }

    @Test
    fun providerOffHasNoResolver() {
        dohResolver(-1, bootstrap) shouldBe null
        dohResolver(99, bootstrap) shouldBe null
    }
}
