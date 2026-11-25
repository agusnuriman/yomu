package eu.kanade.tachiyomi.network

/**
 * True ketika kegagalan pada [message] pantas ditawarkan jalan keluar DNS aman, dan pengguna
 * belum memakainya.
 */
fun secureDnsOffer(message: String?, dohProvider: Int): Boolean =
    evaluateDnsFailure(message, dohEnabled = dohProvider != -1) == DnsFailure.BlockedOrUnreachable

fun NetworkPreferences.secureDnsEnabled(): Boolean = dohProvider.get() != -1

fun NetworkPreferences.enableSecureDns() {
    dohProvider.set(PREF_DOH_CLOUDFLARE)
}
