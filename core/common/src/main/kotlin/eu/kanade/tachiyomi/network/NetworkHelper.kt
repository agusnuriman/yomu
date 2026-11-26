package eu.kanade.tachiyomi.network

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.network.interceptor.CloudflareInterceptor
import eu.kanade.tachiyomi.network.interceptor.UncaughtExceptionInterceptor
import eu.kanade.tachiyomi.network.interceptor.UserAgentInterceptor
import kotlinx.coroutines.CoroutineScope
import mihon.core.metro.AppCoroutineScope
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.io.File
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@Inject
@SingleIn(AppScope::class)
class NetworkHelper(
    @AppCoroutineScope private val scope: CoroutineScope,
    private val context: Context,
    private val preferences: NetworkPreferences,
) {

    val cookieJar = AndroidCookieJar()

    /**
     * Dipegang di luar builder: setiap client yang pernah dibangun memakai cache, dispatcher, dan
     * connection pool yang sama, jadi rebuild tidak pernah membuka dua Cache di direktori yang sama.
     */
    private val resources = NetworkResources(File(context.cacheDir, "network_cache"))

    private val host = NetworkClientHost(scope, preferences, resources, ::buildClient)

    private fun buildClient(resources: NetworkResources, signature: ClientSignature): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .connectTimeout(30.seconds)
            .readTimeout(30.seconds)
            .callTimeout(2.minutes)
            .cache(resources.cache)
            .dispatcher(resources.dispatcher)
            .connectionPool(resources.connectionPool)
            .addInterceptor(UncaughtExceptionInterceptor())
            .addInterceptor(UserAgentInterceptor(::defaultUserAgentProvider))

        if (signature.verboseLogging) {
            val httpLoggingInterceptor = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.HEADERS
                if (signature.redactSensitiveHeaders) {
                    redactHeader("Authorization")
                    redactHeader("Auth")
                }
            }
            builder.addNetworkInterceptor(httpLoggingInterceptor)
        }

        // Snapshot sebelum dns() dipasang: client transport DoH harus bisa menanyakan alamat
        // server DoH-nya lewat DNS sistem. Kalau dns() sudah terpasang saat snapshot ini diambil,
        // pertanyaan itu dijawab oleh dirinya sendiri dan pencarian nama tidak pernah selesai.
        val dnsBootstrap = builder.build()
        builder.dns(
            PreferenceDns(preferences.dohProvider) { provider ->
                dohResolver(provider, dnsBootstrap) ?: Dns.SYSTEM
            },
        )

        return builder
            .addInterceptor(
                CloudflareInterceptor(context, cookieJar, scope, ::defaultUserAgentProvider),
            )
            .build()
    }

    /**
     * Client yang dipakai seluruh app. Ikut dibangun ulang saat pref yang tertanam di dalamnya
     * berubah; pilihan DNS aman tidak perlu menunggu itu karena dibaca saat nama dicari.
     */
    val client: OkHttpClient get() = host.client

    /**
     * @deprecated Since extension-lib 1.5
     */
    @Deprecated("The regular client handles Cloudflare by default")
    @Suppress("UNUSED")
    val cloudflareClient: OkHttpClient get() = client

    fun defaultUserAgentProvider() = preferences.defaultUserAgent.get().trim()
}
