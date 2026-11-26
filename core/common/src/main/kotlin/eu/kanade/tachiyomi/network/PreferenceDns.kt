package eu.kanade.tachiyomi.network

import okhttp3.Dns
import tachiyomi.core.common.preference.Preference
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * Pencari alamat yang membaca pref DNS aman pada setiap pencarian nama, jadi pilihan pengguna berlaku
 * pada koneksi berikutnya tanpa menunggu apa pun: tidak perlu client baru, tidak perlu notifikasi
 * perubahan pref, dan tidak masalah kalau ada konsumen yang menggenggam client lama.
 *
 * Di perangkat uji (MuMu, Android 12) jalur notifikasi itu terbukti tidak pernah mengirim apa pun ke
 * [Preference.changes()] — listener yang dipegang kuat sekalipun tidak dipanggil — jadi janji A2 tidak
 * boleh bersandar padanya.
 *
 * Resolusi per provider dibuat sekali lalu dipakai ulang: membangun [okhttp3.dnsoverhttps.DnsOverHttps]
 * untuk setiap nama akan membuang koneksi yang sudah hidup ke server DoH-nya.
 */
class PreferenceDns(
    private val dohProvider: Preference<Int>,
    private val resolverFor: (Int) -> Dns,
) : Dns {

    private val resolvers = ConcurrentHashMap<Int, Dns>()

    override fun lookup(hostname: String): List<InetAddress> = resolver(dohProvider.get()).lookup(hostname)

    private fun resolver(provider: Int): Dns =
        resolvers[provider] ?: resolverFor(provider).also { resolvers[provider] = it }
}
