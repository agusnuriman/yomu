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

/**
 * Bentuk kegagalan alamat murni, tanpa mempedulikan status DNS aman. Teks error memakainya
 * supaya kegagalan alamat tetap dijelaskan dengan nama domain, walau penawarannya tidak
 * ditawarkan lagi karena DNS aman sudah menyala.
 */
fun dnsFailureShape(message: String?): DnsFailure {
    if (message.isNullOrEmpty()) return DnsFailure.NotDns

    if (message.startsWith("Unable to resolve host")) return DnsFailure.BlockedOrUnreachable

    val match = connectedAddressRegex.find(message) ?: return DnsFailure.NotDns
    val address = match.groupValues[2].removeSurrounding("[", "]").lowercase(Locale.ROOT)
    // Alamat IPv6 tidak bisa dibandingkan dengan prefix sinkhole IPv4, jadi bentuk ini
    // tidak memberi bukti apa pun soal DNS: biarkan pesannya bicara apa adanya.
    if (address.contains(':')) return DnsFailure.NotDns

    val prefixMatched = knownRpzSinkholePrefixes.any { address.startsWith(it) }
    return if (prefixMatched) DnsFailure.BlockedOrUnreachable else DnsFailure.NotDns
}

fun evaluateDnsFailure(message: String?, dohEnabled: Boolean): DnsFailure {
    if (dohEnabled) return DnsFailure.NotDns
    return dnsFailureShape(message)
}

/** Penjelasan yang dipakai untuk sebuah kegagalan IOException. */
enum class FailureText {
    /** Biarkan pesan aslinya lewat: tidak ada terjemahan yang jujur untuk kasus ini. */
    Raw,

    /** Tidak ada jalur keluar sama sekali; DNS bukan penyebabnya. */
    Offline,

    /** Alamat pemberian DNS tidak tercapai atau nama tidak bisa diterjemahkan. */
    DnsBlocked,
}

/** Bentuk pesan kegagalan yang terjadi di tingkat jaringan. */
private val networkLayerRegex = Regex(
    "failed to connect|connect failed|socket failed|unable to resolve host|" +
        "network is unreachable|enetunreach|ehostunreach|enonet|software caused connection abort",
    RegexOption.IGNORE_CASE,
)

/**
 * Kegagalan jaringan murni, dibedakan dari kegagalan IOException lain seperti file sumber
 * lokal yang hilang atau arsip rusak, supaya pesan "tidak ada koneksi" tidak menutupi
 * penyebab yang sebenarnya.
 */
fun isNetworkLayerFailure(message: String?): Boolean =
    message != null && networkLayerRegex.containsMatchIn(message)

/**
 * Bentuk pesan errno Android saat sistem menolak membaca atau menulis berkas: sisa unduhan
 * instalasi lama (uid berbeda) atau folder publik yang tidak diizinkan scoped storage.
 * Dipakai supaya pengguna diberi tahu berkasnya tak terbaca, bukan mentahan "open failed: EACCES".
 */
private val filePermissionRegex = Regex("\\bE(ACCES|PERM)\\s*\\(")

fun isUnreadableFileFailure(message: String?): Boolean =
    message != null && filePermissionRegex.containsMatchIn(message)

/**
 * Pilih penjelasan untuk kegagalan IOException: [FailureText.Offline] saat perangkat tidak
 * punya jalur keluar, [FailureText.DnsBlocked] saat alamat DNS mencurigakan, dan
 * [FailureText.Raw] untuk sisanya agar pesan aslinya tetap terbaca.
 */
fun chooseFailureText(message: String?, online: Boolean): FailureText {
    if (!isNetworkLayerFailure(message)) return FailureText.Raw
    if (!online) return FailureText.Offline
    if (dnsFailureShape(message) == DnsFailure.BlockedOrUnreachable) return FailureText.DnsBlocked
    return FailureText.Raw
}
