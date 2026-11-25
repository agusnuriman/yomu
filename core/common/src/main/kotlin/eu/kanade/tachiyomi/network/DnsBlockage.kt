package eu.kanade.tachiyomi.network

import java.util.Locale

/**
 * Address yang dipakai resolver RPZ (trustpositifkominfo/Biznet) untuk menyesatkan pencarian
 * domain. Daftar ini menaikkan keyakinan, bukan syarat: ketiadaan kecocokan tidak
 * menyangkal bahwa DNS bisa ikut berperan.
 */
val knownRpzSinkholePrefixes: Set<String> = setOf("202.169.44.", "103.52.52.")

enum class DnsFailure {
    /** Kegagalan jelas bukan soal alamat: HTTP, timeout, TLS, dan sejenisnya. */
    NotDns,

    /** Alamat gagal dicapai atau tidak bisa diterjemahkan; DNS mungkin ikut berperan. */
    BlockedOrUnreachable,
}

private val connectedAddressRegex =
    Regex("""Failed to connect to ([^/\s]+)/(\[[^\]]+]|[0-9a-fA-F:.]+):\d+""")

/**
 * Nama host dari pesan "Failed to connect to <host>/<ip>:<port>" milik OkHttp, atau null kalau
 * pesannya bukan kegagalan koneksi. Dipakai agar teks error menyebut domain, bukan mentahan
 * pesan Java.
 */
fun failedConnectHost(message: String?): String? =
    message?.let { connectedAddressRegex.find(it) }?.groupValues?.get(1)

fun evaluateDnsFailure(message: String?, dohEnabled: Boolean): DnsFailure {
    if (dohEnabled || message.isNullOrEmpty()) return DnsFailure.NotDns

    if (message.startsWith("Unable to resolve host")) return DnsFailure.BlockedOrUnreachable

    val match = connectedAddressRegex.find(message) ?: return DnsFailure.NotDns
    val address = match.groupValues[2].removeSurrounding("[", "]").lowercase(Locale.ROOT)
    if (address.contains(':')) return DnsFailure.BlockedOrUnreachable

    val prefixMatched = knownRpzSinkholePrefixes.any { address.startsWith(it) }
    return if (prefixMatched) DnsFailure.BlockedOrUnreachable else DnsFailure.NotDns
}
