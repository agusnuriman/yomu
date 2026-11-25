package eu.kanade.tachiyomi.util.system

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import kotlin.system.exitProcess

/**
 * Hentikan proses lalu jalankan ulang aplikasi, supaya objek yang dibuat sekali per proses -
 * misalnya klien jaringan dengan DNS-over-HTTPS - dibangun ulang dari nol. Peluncuran
 * dijadwalkan lewat AlarmManager sesaat sebelum proses dimatikan. Kalau penjadwalan tidak
 * bisa dilakukan, [onLaunchFailed] dipanggil dan proses dibiarkan hidup agar pengguna tidak
 * kehilangan aplikasi tanpa penjelasan.
 */
fun Context.restartApp(onLaunchFailed: () -> Unit = {}) {
    val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
    if (launchIntent == null) {
        onLaunchFailed()
        return
    }
    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

    val alarmManager = getSystemService(AlarmManager::class.java)
    if (alarmManager == null) {
        onLaunchFailed()
        return
    }

    val pendingIntent = PendingIntent.getActivity(
        this,
        0,
        launchIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    try {
        alarmManager.set(AlarmManager.RTC, System.currentTimeMillis() + 250, pendingIntent)
    } catch (_: SecurityException) {
        onLaunchFailed()
        return
    }

    exitProcess(0)
}
