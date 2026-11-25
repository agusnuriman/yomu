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

/** Bentuk pesan Android/OkHttp ketika nama domain tidak bisa diterjemahkan sama sekali. */
private val unresolvedHostRegex = Regex("""Unable to resolve host "?([^"':\s]+)""")

/**
 * Nama host dari pesan kegagalan alamat milik OkHttp/Android, baik bentuk
 * `Failed to connect to <host>/<ip>:<port>` maupun `Unable to resolve host <host>`, atau null
 * kalau pesannya bukan kegagalan alamat. Dipakai agar teks error menyebut domain, bukan
 * mentahan pesan Java.
 */
fun dnsFailureHost(message: String?): String? {
    if (message == null) return null
    connectedAddressRegex.find(message)?.let { return it.groupValues[1] }
    unresolvedHostRegex.find(message)?.let { return it.groupValues[1] }
    return null
}

fun evaluateDnsFailure(message: String?, dohEnabled: Boolean): DnsFailure {
    if (dohEnabled || message.isNullOrEmpty()) return DnsFailure.NotDns

    if (message.startsWith("Unable to resolve host")) return DnsFailure.BlockedOrUnreachable

    val match = connectedAddressRegex.find(message) ?: return DnsFailure.NotDns
    val address = match.groupValues[2].removeSurrounding("[", "]").lowercase(Locale.ROOT)
    if (address.contains(':')) return DnsFailure.BlockedOrUnreachable

    val prefixMatched = knownRpzSinkholePrefixes.any { address.startsWith(it) }
    return if (prefixMatched) DnsFailure.BlockedOrUnreachable else DnsFailure.NotDns
}
