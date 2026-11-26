package eu.kanade.tachiyomi.network

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress

/**
 * Based on https://github.com/square/okhttp/blob/ef5d0c83f7bbd3a0c0534e7ca23cbc4ee7550f3b/okhttp-dnsoverhttps/src/test/java/okhttp3/dnsoverhttps/DohProviders.java
 */

const val PREF_DOH_CLOUDFLARE = 1
const val PREF_DOH_GOOGLE = 2
const val PREF_DOH_ADGUARD = 3
const val PREF_DOH_QUAD9 = 4
const val PREF_DOH_ALIDNS = 5
const val PREF_DOH_DNSPOD = 6
const val PREF_DOH_360 = 7
const val PREF_DOH_QUAD101 = 8
const val PREF_DOH_MULLVAD = 9
const val PREF_DOH_CONTROLD = 10
const val PREF_DOH_NJALLA = 11
const val PREF_DOH_SHECAN = 12

/**
 * Satu endpoint per provider. Bentuknya data, bukan fungsi: nilainya dibutuhkan saat sebuah nama
 * dicari (lihat [PreferenceDns]), bukan saat client dibangun, jadi tidak ada lagi alasan
 * [OkHttpClient.Builder] ikut campur di sini.
 *
 * Alamat bootstrap ditulis apa adanya dari hulu, kecuali satu: spasi di depan URL Mullvad dibuang
 * dari datanya. OkHttp ternyata tetap bisa membaca URL yang diawali spasi, jadi membuang spasinya
 * adalah kerapian data dan bukan tambalan bug; kode tidak perlu menormalkan apa pun.
 */
private data class DohEndpoint(val url: String, val bootstrapHosts: List<String>)

private val dohEndpoints: Map<Int, DohEndpoint> = mapOf(
    PREF_DOH_CLOUDFLARE to DohEndpoint(
        url = "https://cloudflare-dns.com/dns-query",
        bootstrapHosts = listOf(
            "162.159.36.1",
            "162.159.46.1",
            "1.1.1.1",
            "1.0.0.1",
            "162.159.132.53",
            "2606:4700:4700::1111",
            "2606:4700:4700::1001",
            "2606:4700:4700::0064",
            "2606:4700:4700::6400",
        ),
    ),
    PREF_DOH_GOOGLE to DohEndpoint(
        url = "https://dns.google/dns-query",
        bootstrapHosts = listOf(
            "8.8.4.4",
            "8.8.8.8",
            "2001:4860:4860::8888",
            "2001:4860:4860::8844",
        ),
    ),
    // AdGuard "Default" DNS works too but for the sake of making sure no site is blacklisted,
    // we use "Unfiltered"
    PREF_DOH_ADGUARD to DohEndpoint(
        url = "https://dns-unfiltered.adguard.com/dns-query",
        bootstrapHosts = listOf(
            "94.140.14.140",
            "94.140.14.141",
            "2a10:50c0::1:ff",
            "2a10:50c0::2:ff",
        ),
    ),
    PREF_DOH_QUAD9 to DohEndpoint(
        url = "https://dns.quad9.net/dns-query",
        bootstrapHosts = listOf(
            "9.9.9.9",
            "149.112.112.112",
            "2620:fe::fe",
            "2620:fe::9",
        ),
    ),
    PREF_DOH_ALIDNS to DohEndpoint(
        url = "https://dns.alidns.com/dns-query",
        bootstrapHosts = listOf(
            "223.5.5.5",
            "223.6.6.6",
            "2400:3200::1",
            "2400:3200:baba::1",
        ),
    ),
    PREF_DOH_DNSPOD to DohEndpoint(
        url = "https://doh.pub/dns-query",
        bootstrapHosts = listOf(
            "1.12.12.12",
            "120.53.53.53",
        ),
    ),
    PREF_DOH_360 to DohEndpoint(
        url = "https://doh.360.cn/dns-query",
        bootstrapHosts = listOf(
            "101.226.4.6",
            "218.30.118.6",
            "123.125.81.6",
            "140.207.198.6",
            "180.163.249.75",
            "101.199.113.208",
            "36.99.170.86",
        ),
    ),
    PREF_DOH_QUAD101 to DohEndpoint(
        url = "https://dns.twnic.tw/dns-query",
        bootstrapHosts = listOf(
            "101.101.101.101",
            "2001:de4::101",
            "2001:de4::102",
        ),
    ),
    /*
     * Mullvad DoH
     * without ad blocking option
     * Source: https://mullvad.net/en/help/dns-over-https-and-dns-over-tls
     */
    PREF_DOH_MULLVAD to DohEndpoint(
        url = "https://dns.mullvad.net/dns-query",
        bootstrapHosts = listOf(
            "194.242.2.2",
            "2a07:e340::2",
        ),
    ),
    /*
     * Control D
     * unfiltered option
     * Source: https://controld.com/free-dns/?
     */
    PREF_DOH_CONTROLD to DohEndpoint(
        url = "https://freedns.controld.com/p0",
        bootstrapHosts = listOf(
            "76.76.2.0",
            "76.76.10.0",
            "2606:1a40::",
            "2606:1a40:1::",
        ),
    ),
    /*
     * Njalla
     * Non logging and uncensored
     */
    PREF_DOH_NJALLA to DohEndpoint(
        url = "https://dns.njal.la/dns-query",
        bootstrapHosts = listOf(
            "95.215.19.53",
            "2001:67c:2354:2::53",
        ),
    ),
    // Source: https://shecan.ir/
    PREF_DOH_SHECAN to DohEndpoint(
        url = "https://free.shecan.ir/dns-query",
        bootstrapHosts = listOf(
            "178.22.122.100",
            "185.51.200.2",
        ),
    ),
)

/**
 * Resolver DoH untuk [provider], memakai [bootstrap] sebagai transportnya.
 *
 * [bootstrap] wajib client yang pencari alamatnya masih sistem: kalau tidak, pertanyaan
 * "cloudflare-dns.com nya IP berapa?" harus dijawab lewat DoH, dan DoH harus menjawab pertanyaan
 * itu lewat dirinya sendiri.
 *
 * Mengembalikan null saat DNS aman mati (provider -1) atau saat id tidak dikenal.
 */
fun dohResolver(provider: Int, bootstrap: OkHttpClient): DnsOverHttps? =
    dohEndpoints[provider]?.let { endpoint ->
        DnsOverHttps.Builder()
            .client(bootstrap)
            .url(endpoint.url.toHttpUrl())
            .bootstrapDnsHosts(endpoint.bootstrapHosts.map { InetAddress.getByName(it) })
            .build()
    }
