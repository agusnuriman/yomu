package eu.kanade.presentation.util

import android.content.Context
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.network.failedConnectHost
import eu.kanade.tachiyomi.network.secureDnsOffer
import eu.kanade.tachiyomi.util.system.isOnline
import mihon.app.di.appGraph
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.data.source.NoResultsException
import tachiyomi.domain.source.model.SourceNotInstalledException
import tachiyomi.i18n.MR
import java.io.IOException
import java.net.UnknownHostException

context(context: Context)
val Throwable.formattedMessage: String
    get() {
        when (this) {
            is HttpException -> return context.stringResource(MR.strings.exception_http, code)
            is UnknownHostException -> {
                return if (!context.isOnline()) {
                    context.stringResource(MR.strings.exception_offline)
                } else {
                    context.stringResource(MR.strings.exception_unknown_host, message ?: "")
                }
            }

            is NoResultsException -> return context.stringResource(MR.strings.no_results_found)
            is SourceNotInstalledException -> return context.stringResource(MR.strings.loader_not_implemented_error)

            // Koneksi gagal ke alamat yang diberikan DNS: kemungkinan besar dibelokkan penyedia,
            // bukan sumbernya yang mati. Hanya diganti kalau tawaran DNS aman memang relevan.
            is IOException -> {
                val dohProvider = context.appGraph.networkPreferences.dohProvider.get()
                if (secureDnsOffer(message, dohProvider)) {
                    return context.stringResource(
                        MR.strings.exception_dns_blocked,
                        failedConnectHost(message) ?: "",
                    )
                }
            }
        }
        return when (val className = this::class.simpleName) {
            "Exception", "IOException" -> message ?: className
            else -> "$className: $message"
        }
    }
