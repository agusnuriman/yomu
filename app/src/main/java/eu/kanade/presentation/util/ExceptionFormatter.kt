package eu.kanade.presentation.util

import android.content.Context
import eu.kanade.tachiyomi.network.FailureText
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.network.chooseFailureText
import eu.kanade.tachiyomi.network.dnsFailureHost
import eu.kanade.tachiyomi.network.isUnreadableFileFailure
import eu.kanade.tachiyomi.util.system.isOnline
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

            // Berkas unduhan milik instalasi lama tidak bisa dibaca uid sekarang; pesan sistemnya
            // bahasa Inggris dan mentah, jadi diganti penjelasan yang bisa ditindaklanjuti.
            is IOException -> if (isUnreadableFileFailure(message)) {
                return context.stringResource(MR.strings.exception_unreadable_file)
            }

            is NoResultsException -> return context.stringResource(MR.strings.no_results_found)
            is SourceNotInstalledException -> return context.stringResource(MR.strings.loader_not_implemented_error)

            // Koneksi gagal ke alamat yang diberikan DNS: kemungkinan besar dibelokkan penyedia,
            // bukan sumbernya yang mati. Tanpa koneksi sama sekali, pesan offline yang dipakai
            // supaya teks dan aksi di layar tidak saling bertentangan. Kegagalan yang bukan
            // soal jaringan (file sumber lokal hilang, arsip rusak) tetap membawa pesannya
            // sendiri.
            is IOException -> {
                val failureText = chooseFailureText(message, online = context.isOnline())
                if (failureText == FailureText.Offline) {
                    return context.stringResource(MR.strings.exception_offline)
                }
                if (failureText == FailureText.DnsBlocked) {
                    return context.stringResource(
                        MR.strings.exception_dns_blocked,
                        dnsFailureHost(message) ?: "",
                    )
                }
            }
        }
        return when (val className = this::class.simpleName) {
            "Exception", "IOException" -> message ?: className
            else -> "$className: $message"
        }
    }
