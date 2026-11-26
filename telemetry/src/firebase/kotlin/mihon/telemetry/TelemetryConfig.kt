package mihon.telemetry

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

object TelemetryConfig {
    private var analytics: FirebaseAnalytics? = null
    private var crashlytics: FirebaseCrashlytics? = null

    fun init(context: Context) {
        // Only official Yomu builds may report telemetry
        if (!context.isYomuProductionApp()) return

        // Check if Google Play Services is available before initializing Firebase
        if (!isGooglePlayServicesAvailable(context)) {
            logcat(LogPriority.WARN) { "Google Play Services not available, skipping Firebase initialization" }
            return
        }

        try {
            analytics = FirebaseAnalytics.getInstance(context)
            analytics?.setUserProperty("preferred_abi", Build.SUPPORTED_ABIS[0])
            FirebaseApp.initializeApp(context)
            crashlytics = FirebaseCrashlytics.getInstance()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to initialize Firebase" }
        }
    }

    private fun isGooglePlayServicesAvailable(context: Context): Boolean {
        return try {
            context.packageManager
                .getPackageInfo("com.google.android.gms", PackageManager.GET_META_DATA)
                .applicationInfo
                ?.enabled == true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun setAnalyticsEnabled(enabled: Boolean) {
        analytics?.setAnalyticsCollectionEnabled(enabled)
    }

    fun setCrashlyticsEnabled(enabled: Boolean) {
        crashlytics?.isCrashlyticsCollectionEnabled = enabled
    }

    private fun Context.isYomuProductionApp(): Boolean {
        if (packageName !in YOMU_PACKAGES) return false

        return packageManager.getPackageInfo(packageName, SignatureFlags)
            .getCertificateFingerprints()
            .any { it == YOMU_CERTIFICATE_FINGERPRINT }
    }

    fun recordException(throwable: Throwable) {
        crashlytics?.recordException(throwable)
    }
}

private val YOMU_PACKAGES = hashSetOf("com.yomu.app", "com.yomu.app.dev")
private const val YOMU_CERTIFICATE_FINGERPRINT =
    "12:A6:F0:00:94:EA:3C:F7:6E:FA:77:CA:DF:CB:E2:64:D7:F7:DB:C3:CA:44:6C:23:83:2F:49:4B:11:82:C9:68"
