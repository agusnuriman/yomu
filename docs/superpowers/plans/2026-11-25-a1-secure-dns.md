# A1 - Secure DNS untuk sumber yang diblokir provider: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ketika sebuah sumber gagal karena address-nya disesatkan provider, pengguna bisa memulihkannya dari layar error itu juga lewat satu aksi "DNS aman", tanpa VPN dan tanpa harus tahu istilah DNS.

**Architecture:** Detektor murni berbasis pola pesan error OkHttp tinggal di `:core:common` (network), satu helper `SecureDns` menjadi jembatan ke preferensi `doh_provider`, dan UI hanya menambah satu aksi pada `EmptyScreen` sumber plus satu dialog konfirmasi. Tidak ada traffic jaringan tambahan untuk mendeteksi; peringatan muncul dari pola error, bukan dari probe ke pihak ketiga.

**Tech Stack:** Kotlin, OkHttp + okhttp-dnsoverhttps, Jetpack Compose (Material3), moko-resources (MR.strings), Metro DI, SQLDelight tidak tersentuh, Gradle, JUnit 5 + kotest-assertions.

**Spec:** `docs/superpowers/specs/2026-11-25-a1-dns-block-ux-design.md` (root repo: `D:\Projects\Yomu`)

## Global Constraints

- Root build Gradle ada di `D:\Projects\Yomu\app` (bukan `D:\Projects\Yomu`); semua perintah `.\gradlew.bat` dijalankan dari sana.
- Modul: `:core:common` di `app/core/common`, `:app` di `app/app`, `:i18n` di `app/i18n`.
- Sumber kebenaran i18n: `app/i18n/src/commonMain/moko-resources/base/strings.xml` (Inggris, default) dan `.../in/strings.xml` (Indonesia). Bahasa lain mengikuti, tanpa edit manual.
- Nilai pref DoH persis seperti yang ada: key `doh_provider`, default `-1`, Cloudflare = `PREF_DOH_CLOUDFLARE` = `1`.
- `NetworkHelper.client` dibangun `by lazy`: perubahan DoH tidak bisa berlaku panas. Restart app selalu manual, tanpa `exitProcess`.
- Jangan pernah mengirim nama domain yang gagal ke resolver pihak ketiga untuk keperluan deteksi. Aksi dan pesan tidak boleh mengklaim "diblokir" sebagai fakta.
- Pola error yang jadi acuan, verbatim dari perangkat: `Failed to connect to api.mangadex.org/202.169.44.80:443`.
- Tes unit memakai `org.junit.jupiter.api.Test` + `io.kotest.matchers.shouldBe`, sama seperti `domain/src/test/java/.../ChapterRecognitionTest.kt`.
- Kode dan komentar mengikuti gaya file sekitar; jangan menambah komentar generik.

## Review Focus

Input yang tidak dijamin oleh tes unit per task, tetapi akan dialami pengguna nyata. Baris ini dicek deliberate saat review dan task-nya punya tes/verifikasi yang menempel:

1. Jaringan benar-benar mati: pengguna harus tetap melihat "Tidak ada koneksi internet" dan tidak boleh ditawari DNS aman. Cabra `isOnline()` di `ExceptionFormatter` dieksekusi lebih dulu dari pesan blok, dan `secureDnsOffer` tidak pernah menerima informasi online sehingga call site wajib memeriksanya. -> Task 6 Step 4 (buktinya manual, tidak ada tes JVM untuk kopel Compose di repo ini).
2. Domain yang benar-benar mati atau salah IP-nya (bukan sinkhole): aksi DNS aman tidak muncul, pesan tetap seperti dulu. -> Task 1 Step 1 (`testRealIpFailureIsNotDns`) + Task 6 Step 5.
3. DoH sudah aktif: aksi harus hilang dan tidak pernah muncul lagi di sesi itu. -> Task 1 Step 1 (`testOfferSuppressedWhenDohAlreadyEnabled`) + Task 6 Step 6.
4. Setelah restart manual: pref bertahan dan MangaDex benar-benar termuat. -> Task 6 Step 7 (bukti screenshot).
5. Pengguna dengan bahasa selain Indonesia/Inggris: string baru tidak boleh hilang atau crash; fallback base dipakai. -> Task 2 Step 4 (build `:i18n` membuktikan MR tergenerate).

---

### Task 1: Detektor murni `DnsBlockage` di `:core:common`

**Files:**
- Create: `core/common/src/main/kotlin/eu/kanade/tachiyomi/network/DnsBlockage.kt`
- Test: `core/common/src/test/java/eu/kanade/tachiyomi/network/DnsBlockageTest.kt` (srcset `test` belum ada, dibuat di task ini)

**Interfaces:**
- Consumes: tidak ada (fungsinya murni, tanpa Android).
- Produces:
  - `enum class eu.kanade.tachiyomi.network.DnsFailure { NotDns, BlockedOrUnreachable }`
  - `fun eu.kanade.tachiyomi.network.evaluateDnsFailure(message: String?, dohEnabled: Boolean): DnsFailure`
  - `val eu.kanade.tachiyomi.network.knownRpzSinkholePrefixes: Set<String>`

- [ ] **Step 1: Tulis tes yang gagal**

Buat `core/common/src/test/java/eu/kanade/tachiyomi/network/DnsBlockageTest.kt` dengan isi ini persis (string error disalin dari bukti perangkat):

```kotlin
package eu.kanade.tachiyomi.network

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

@Execution(ExecutionMode.CONCURRENT)
class DnsBlockageTest {

    @Test
    fun testPoisonedAddressFailureIsBlockedOrUnreachable() {
        val message = "Failed to connect to api.mangadex.org/202.169.44.80:443"
        evaluateDnsFailure(message, dohEnabled = false) shouldBe DnsFailure.BlockedOrUnreachable
    }

    @Test
    fun testRealIpFailureIsNotDns() {
        val message = "Failed to connect to api.mangadex.org/45.129.229.1:443"
        evaluateDnsFailure(message, dohEnabled = false) shouldBe DnsFailure.NotDns
    }

    @Test
    fun testUnresolvableHostIsBlockedOrUnreachable() {
        val message = "Unable to resolve host api.mangadex.org: no information for query"
        evaluateDnsFailure(message, dohEnabled = false) shouldBe DnsFailure.BlockedOrUnreachable
    }

    @Test
    fun testOfferSuppressedWhenDohAlreadyEnabled() {
        val message = "Failed to connect to api.mangadex.org/202.169.44.80:443"
        evaluateDnsFailure(message, dohEnabled = true) shouldBe DnsFailure.NotDns
    }

    @Test
    fun testNullAndForeignMessagesAreNotDns() {
        evaluateDnsFailure(null, dohEnabled = false) shouldBe DnsFailure.NotDns
        evaluateDnsFailure("", dohEnabled = false) shouldBe DnsFailure.NotDns
        evaluateDnsFailure("HTTP error 503", dohEnabled = false) shouldBe DnsFailure.NotDns
        evaluateDnsFailure("timeout", dohEnabled = false) shouldBe DnsFailure.NotDns
    }

    @Test
    fun testAddressOutsideKnownPrefixIsNotDns() {
        evaluateDnsFailure(
            "Failed to connect to api.mangadex.org/202.169.45.80:443",
            dohEnabled = false,
        ) shouldBe DnsFailure.NotDns
        evaluateDnsFailure(
            "Failed to connect to api.mangadex.org/104.16.0.1:443",
            dohEnabled = false,
        ) shouldBe DnsFailure.NotDns
    }

    @Test
    fun testIpv6FailureIsReportedWithoutAddressMatch() {
        evaluateDnsFailure(
            "Failed to connect to api.mangadex.org/2606:4700::64:443",
            dohEnabled = false,
        ) shouldBe DnsFailure.BlockedOrUnreachable
    }

    @Test
    fun testSecondPrefixIsRecognised() {
        evaluateDnsFailure(
            "Failed to connect to nyaa.si/103.52.52.10:443",
            dohEnabled = false,
        ) shouldBe DnsFailure.BlockedOrUnreachable
    }
}
```

- [ ] **Step 2: Jalankan tes, pastikan gagal**

Run: `cd D:\Projects\Yomu\app; .\gradlew.bat :core:common:testDebugUnitTest --tests "eu.kanade.tachiyomi.network.DnsBlockageTest"`
Expected: BUILD FAILED dengan `unresolved reference: evaluateDnsFailure` (kompilasi test, bukan failure assertion). Kalau task ini tidak punya variant debug, pakai `testReleaseUnitTest` variant yang sama dipakai modul lain.

- [ ] **Step 3: Implementasi `DnsBlockage.kt`**

Buat `core/common/src/main/kotlin/eu/kanade/tachiyomi/network/DnsBlockage.kt`:

```kotlin
package eu.kanade.tachiyomi.network

import java.util.Locale

/**
 * Address yang dipakai resolver Biznet/Telkom (RPZ trustpositifkominfo) untuk menyesatkan
 * pencarian domain. Ditambahkan terus; kecocokan hanya menaikkan keyakinan,
 * ketiadaan kecocokan tidak menyangkal kemungkinan DNS ikut berperan.
 */
val knownRpzSinkholePrefixes: Set<String> = setOf("202.169.44.", "103.52.52.")

enum class DnsFailure {
    /** Error jelas bukan soal alamat: HTTP, timeout, TLS, dll. */
    NotDns,

    /** Alamat gagal dicapai atau tidak bisa diterjemahkan; DNS mungkin ikut berperan. */
    BlockedOrUnreachable,
}

private val connectedAddressRegex =
    Regex("""Failed to connect to ([^/\s]+)/(\[[^\]]+]|[0-9a-fA-F:.]+):\d+""")

fun evaluateDnsFailure(message: String?, dohEnabled: Boolean): DnsFailure {
    if (dohEnabled || message.isNullOrEmpty()) return DnsFailure.NotDns

    if (message.startsWith("Unable to resolve host")) return DnsFailure.BlockedOrUnreachable

    val match = connectedAddressRegex.find(message) ?: return DnsFailure.NotDns
    val address = match.groupValues[2].removeSurrounding("[", "]").lowercase(Locale.ROOT)
    if (address.contains(':')) return DnsFailure.BlockedOrUnreachable
    val prefixMatched = knownRpzSinkholePrefixes.any { address.startsWith(it) }
    return if (prefixMatched) DnsFailure.BlockedOrUnreachable else DnsFailure.NotDns
}
```

- [ ] **Step 4: Jalankan tes, pastikan lulus**

Run: `cd D:\Projects\Yomu\app; .\gradlew.bat :core:common:testDebugUnitTest --tests "eu.kanade.tachiyomi.network.DnsBlockageTest"`
Expected: BUILD SUCCESSFUL, 8 tes lolos, 0 gagal.

- [ ] **Step 5: Commit**

```powershell
cd D:\Projects\Yomu
git add app/core/common/src
git commit -m "feat(network): detect DNS-level address failure for blocked sources"
```

---

### Task 2: String baru, Inggris (base) dan Indonesia

**Files:**
- Modify: `i18n/src/commonMain/moko-resources/base/strings.xml` (dekat `exception_unknown_host`)
- Modify: `i18n/src/commonMain/moko-resources/in/strings.xml`

**Interfaces:**
- Consumes: tidak ada.
- Produces key MR untuk Task 3 dan 4:
  - `MR.strings.exception_dns_blocked` (1 arg: host)
  - `MR.strings.action_use_secure_dns`
  - `MR.strings.secure_dns_dialog_title`
  - `MR.strings.secure_dns_dialog_message` (1 arg: host)
  - `MR.strings.secure_dns_enabled_restart`

- [ ] **Step 1: Tambah lima string ke base**

Di `base/strings.xml`, tepat setelah entri `exception_unknown_host`, tambahkan (apostrof di-escape dengan `\'` seperti entri tetangga):

```xml
<string name="exception_dns_blocked">Couldn\'t reach %s. The address may be redirected by your internet provider, or it may not resolve.</string>
<string name="action_use_secure_dns">Use secure DNS</string>
<string name="secure_dns_dialog_title">Use secure DNS?</string>
<string name="secure_dns_dialog_message">Yomu will look up %s through Cloudflare DNS instead of your provider\'s DNS. The app must be restarted before it takes effect.</string>
<string name="secure_dns_enabled_restart">Secure DNS enabled. Restart the app to apply.</string>
```

- [ ] **Step 2: Tambah varian Indonesia**

Di `in/strings.xml`, tambahkan lima kunci yang sama:

```xml
<string name="exception_dns_blocked">Tidak bisa menghubungi %s. Alamatnya mungkin dibelokkan penyedia internetmu, atau memang tidak terdaftar.</string>
<string name="action_use_secure_dns">Pakai DNS aman</string>
<string name="secure_dns_dialog_title">Pakai DNS aman?</string>
<string name="secure_dns_dialog_message">Yomu akan mencari alamat %s lewat Cloudflare DNS, bukan DNS penyedia internetmu. Aplikasi harus dibuka ulang supaya berlaku.</string>
<string name="secure_dns_enabled_restart">DNS aman aktifkan. Buka ulang aplikasi untuk menerapkannya.</string>
```

- [ ] **Step 3: Verifikasi MR tergenerate**

Run: `cd D:\Projects\Yomu\app; .\gradlew.bat :i18n:generateMRandroidMain`
Jika task tersebut tidak ada, jalankan `.\gradlew.bat :i18n:assembleDebug` sebagai gantinya.
Expected: BUILD SUCCESSFUL, tanpa error "duplicated key". Kalau moko menolak nama `in` (seharusnya tidak, direktori sudah ada), catat sebagai ruling dan pakai `id`.

- [ ] **Step 4: Verifikasi fallback bahasa lain**

Run: `cd D:\Projects\Yomu\app; .\gradlew.bat :i18n:assembleDebug`
Expected: BUILD SUCCESSFUL. Lima kunci baru tidak ada di 68 direktori bahasa lain; itu diterima karena base menjadi fallback.

- [ ] **Step 5: Commit**

```powershell
cd D:\Projects\Yomu
git add app/i18n/src/commonMain/moko-resources
git commit -m "feat(i18n): add secure DNS strings (en base + id)"
```

---

### Task 3: Helper `SecureDns` dan pesan error yang menyarankan aksi

**Files:**
- Create: `core/common/src/main/kotlin/eu/kanade/tachiyomi/network/SecureDns.kt`
- Test: `core/common/src/test/java/eu/kanade/tachiyomi/network/SecureDnsTest.kt`
- Modify: `app/src/main/java/eu/kanade/presentation/util/ExceptionFormatter.kt:17-23`

**Interfaces:**
- Consumes: `evaluateDnsFailure(message, dohEnabled)`, `NetworkPreferences.dohProvider`, `PREF_DOH_CLOUDFLARE` (Task 1; konstanta sudah ada).
- Produces:
  - `fun eu.kanade.tachiyomi.network.secureDnsOffer(message: String?, dohProvider: Int): Boolean`
  - `fun eu.kanade.tachiyomi.network.NetworkPreferences.secureDnsEnabled(): Boolean`
  - `fun eu.kanade.tachiyomi.network.NetworkPreferences.enableSecureDns()`

- [ ] **Step 1: Tulis tes yang gagal**

`core/common/src/test/java/eu/kanade/tachiyomi/network/SecureDnsTest.kt`. Fungsi ini Android-free, jadi `NetworkPreferences` dites lewat `PreferenceStore` in-memory milik Task 4 hanya bila tersedia; kalau tidak, cukup uji `secureDnsOffer`:

```kotlin
package eu.kanade.tachiyomi.network

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

@Execution(ExecutionMode.CONCURRENT)
class SecureDnsTest {

    private val blocked = "Failed to connect to api.mangadex.org/202.169.44.80:443"

    @Test
    fun testOfferWhenDohDisabled() {
        secureDnsOffer(blocked, dohProvider = -1) shouldBe true
    }

    @Test
    fun testNoOfferWhenDohEnabled() {
        secureDnsOffer(blocked, dohProvider = PREF_DOH_CLOUDFLARE) shouldBe false
    }

    @Test
    fun testNoOfferForNonDnsError() {
        secureDnsOffer("Failed to connect to api.mangadex.org/45.129.229.1:443", -1) shouldBe false
        secureDnsOffer("HTTP error 503", -1) shouldBe false
    }
}
```

- [ ] **Step 2: Jalankan tes, pastikan gagal**

Run: `cd D:\Projects\Yomu\app; .\gradlew.bat :core:common:testDebugUnitTest --tests "eu.kanade.tachiyomi.network.SecureDnsTest"`
Expected: BUILD FAILED, `unresolved reference: secureDnsOffer`.

- [ ] **Step 3: Implementasi `SecureDns.kt`**

```kotlin
package eu.kanade.tachiyomi.network

fun secureDnsOffer(message: String?, dohProvider: Int): Boolean =
    evaluateDnsFailure(message, dohEnabled = dohProvider != -1) == DnsFailure.BlockedOrUnreachable

fun NetworkPreferences.secureDnsEnabled(): Boolean = dohProvider.get() != -1

fun NetworkPreferences.enableSecureDns() {
    dohProvider.set(PREF_DOH_CLOUDFLARE)
}
```

- [ ] **Step 4: Jalankan tes, pastikan lulus**

Run: `cd D:\Projects\Yomu\app; .\gradlew.bat :core:common:testDebugUnitTest --tests "eu.kanade.tachiyomi.network.*"`
Expected: BUILD SUCCESSFUL, semua tes Task 1 dan 3 lulus.

- [ ] **Step 5: Pakai di ExceptionFormatter**

Di `app/src/main/java/eu/kanade/presentation/util/ExceptionFormatter.kt`, cabang `is UnknownHostException` hanya menangani "host tidak bisa diterjemahkan". Tawaran DNS aman harus menyasar kedua bentuk kegagalan, jadi tambahkan cabang paling awal sebelum `is HttpException`, dan import `android.content.pm.ContextWrapper` tidak diperlukan karena `context` sudah tersedia:

```kotlin
is IOException -> {
    val networkPreferences = context.appGraph.networkPreferences
    if (secureDnsOffer(message, networkPreferences.dohProvider.get())) {
        return context.stringResource(MR.strings.exception_dns_blocked, this.message ?: "")
    }
}
```

Import baru: `eu.kanade.tachiyomi.network.secureDnsOffer` dan `mihon.app.di.appGraph`. Perhatikan `HttpException` bukan `IOException` (ia mewarisi `IllegalStateException`), jadi `is HttpException` di atas tetap lebih dulu terpilih dan tidak berubah. Cabang `is UnknownHostException` yang ada di bawah tidak disentuh: pesan `Unable to resolve host` tetap memakai `exception_unknown_host` seperti sebelumnya, supaya perubahan ini hanya mempengaruhi jalur koneksi-gagal.

- [ ] **Step 6: Verifikasi kompilasi**

Run: `cd D:\Projects\Yomu\app; .\gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL tanpa error unresolved reference.

- [ ] **Step 7: Commit**

```powershell
cd D:\Projects\Yomu
git add app/core/common/src app/app/src/main/java/eu/kanade/presentation/util/ExceptionFormatter.kt
git commit -m "feat(network): suggest secure DNS when a source address fails"
```

---

### Task 4: Aksi "Pakai DNS aman" plus dialog di layar sumber

**Files:**
- Modify: `app/src/main/java/eu/kanade/presentation/browse/BrowseSourceScreen.kt:52-114`
- Test: tidak ada tes otomatis untuk Compose di repo ini; dibuktikan lewat build + jalan manual Task 6.

**Interfaces:**
- Consumes: `secureDnsOffer`, `NetworkPreferences.enableSecureDns()`, `MR.strings.action_use_secure_dns`, `secure_dns_dialog_*`, `secure_dns_enabled_restart` (Task 2, 3).
- Produces: perilaku UI; tanpa API baru untuk task lain.

- [ ] **Step 1: Ambil state pref dan error di `BrowseSourceContent`**

Di `BrowseSourceContent`, setelah `val context = LocalContext.current` (baris 52), tambahkan pembacaan pref yang hidup (flow), dan hitung apakah aksi harus muncul. `getErrorMessage` sudah ada di baris 57; gunakan sumber pesan yang sama supaya konsistensi terjaga:

```kotlin
val networkPreferences = remember { context.appGraph.networkPreferences }
val dnsOverHttpsEnabled = networkPreferences.secureDnsEnabled()
val errorThrowable = (errorState as? LoadState.Error)?.error
val offerSecureDns = errorThrowable !is HttpException &&
    context.isOnline() &&
    secureDnsOffer(errorThrowable?.message, networkPreferences.dohProvider.get())
var showSecureDnsDialog by remember(errorState) { mutableStateOf(false) }
```

`isOnline()` adalah ekstensi `Context` (`app/src/main/java/eu/kanade/tachiyomi/util/system/NetworkExtensions.kt:16`), jadi `context.isOnline()` valid. `appGraph` adalah ekstensi `Context` di `app/src/main/java/mihon/app/di/AppGraphUtils.kt:6`; `NetworkPreferences` diekspos sebagai properti di `AppGraph.kt:87`. Jangan membuat accessor baru. `Preference<T>` tidak punya properti `flow` - hanya `get()`/`set()`/`changes()` - jadi baca pref sinkron dan tulis ulang komponennya lewat state lokal.

- [ ] **Step 2: Tambah aksi pada EmptyScreen**

Di dalam `actions = if (source is LocalSource) { ... } else { listOf(...) }` (baris 95-112), sisipkan satu `EmptyScreenAction` **setelah** Retry dan **sebelum** Open in WebView, hanya saat `offerSecureDns`:

```kotlin
buildList {
    add(EmptyScreenAction(MR.strings.action_retry, MaterialSymbols.Rounded.Refresh, mangaList::refresh))
    if (offerSecureDns) {
        add(
            EmptyScreenAction(
                stringRes = MR.strings.action_use_secure_dns,
                icon = MaterialSymbols.Rounded.Public,
                onClick = { showSecureDnsDialog = true },
            ),
        )
    }
    add(EmptyScreenAction(MR.strings.action_open_in_web_view, MaterialSymbols.Rounded.Public, onWebViewClick))
    add(EmptyScreenAction(MR.strings.label_help, MaterialSymbols.AutoMirroredRounded.Help, onHelpClick))
}
```

Sesuaikan nama parameter `EmptyScreenAction` dengan yang sudah dipakai di file itu (`stringRes`, `icon`, `onClick`); jangan mengubah strukturnya.

- [ ] **Step 3: Render dialog**

Setelah blok `if (mangaList.itemCount == 0) { ... }` (sebelum `when (displayMode)`), tambahkan:

```kotlin
if (showSecureDnsDialog) {
    AlertDialog(
        onDismissRequest = { showSecureDnsDialog = false },
        title = { Text(stringResource(MR.strings.secure_dns_dialog_title)) },
        text = { Text(stringResource(MR.strings.secure_dns_dialog_message, source?.name ?: "")) },
        confirmButton = {
            TextButton(
                onClick = {
                    networkPreferences.enableSecureDns()
                    showSecureDnsDialog = false
                },
            ) { Text(stringResource(MR.strings.confirm)) }
        },
        dismissButton = {
            TextButton(onClick = { showSecureDnsDialog = false }) {
                Text(stringResource(MR.strings.cancel))
            }
        },
    )
}
```

Pakai kunci `MR.strings.confirm` / `MR.strings.cancel` yang sudah ada; kalau nama sebenarnya berbeda (`mr` sudah punya `action_cancel`), pakai yang sudah tersedia, jangan membuat string baru untuk tombol.

- [ ] **Step 4: Snackbar setelah aktif**

Di `LaunchedEffect(errorState)` yang sudah ada (baris 61-73) tambahkan cabang, atau effect terpisah yang lebih mudah dibaca:

```kotlin
var secureDnsJustEnabled by remember(errorState) { mutableStateOf(false) }

LaunchedEffect(secureDnsJustEnabled) {
    if (secureDnsJustEnabled) {
        snackbarHostState.showSnackbar(stringResource(MR.strings.secure_dns_enabled_restart))
        secureDnsJustEnabled = false
    }
}
```

`showSecureDnsDialog = false` pada Step 3 diganti menjadi dua baris: `networkPreferences.enableSecureDns()` lalu `secureDnsJustEnabled = true`. Effect tidak boleh bergantung pada nilai pref, karena akan menyalakan snackbar saat pengguna masuk layar dengan DoH yang sudah aktif dari sesi sebelumnya.

- [ ] **Step 5: Verifikasi kompilasi kedua call site**

Run: `cd D:\Projects\Yomu\app; .\gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. `MigrateSourceSearchScreen.kt:105` harus tetap kompilasi tanpa perubahan, karena tidak ada parameter baru yang wajib.

- [ ] **Step 6: Commit**

```powershell
cd D:\Projects\Yomu
git add app/app/src/main/java/eu/kanade/presentation/browse/BrowseSourceScreen.kt
git commit -m "feat(browse): offer secure DNS on blocked source error screen"
```

---

### Task 5: Pindahkan baris DNS-over-HTTPS ke tempat yang ketemu

**Files:**
- Modify: `app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsAdvancedScreen.kt:203-268`

**Interfaces:**
- Consumes: `networkPreferences.dohProvider`, `MR.strings.pref_dns_over_https` (sudah ada).
- Produces: tanpa API baru.

- [ ] **Step 1: Buat grup sendiri untuk DNS**

`getNetworkGroup(networkPreferences)` saat ini menaruh DoH di bawah User-Agent dan logging. Keluarkan `Preference.PreferenceItem.ListPreference` DoH (baris 246-268) dari daftar item grup tersebut, lalu daftarkan grup baru setelahnya:

```kotlin
private fun getDnsGroup(networkPreferences: NetworkPreferences): Preference.PreferenceGroup
```

Isinya satu-satunya item DoH, dengan `title` `MR.strings.pref_dns_over_https` dan `subtitle` yang menjelaskan dalam satu kalimat kapan itu berguna (misalnya memakai string baru `pref_dns_over_https_summary` yang kamu tambahkan di base dan `in` pada task ini juga). Urutan grup pada baris 130-136 menjadi `getNetworkGroup(...)`, `getDnsGroup(...)`, `getLibraryGroup(...)`.

- [ ] **Step 2: Verifikasi kompilasi**

Run: `cd D:\Projects\Yomu\app; .\gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```powershell
cd D:\Projects\Yomu
git add app/app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsAdvancedScreen.kt app/i18n/src/commonMain/moko-resources
git commit -m "feat(settings): give DNS-over-HTTPS its own group"
```

---

### Task 6: Verifikasi end-to-end di MuMu

Tanpa perubahan kode. Kalau salah satu langkah tidak cocok dengan Expected, hentikan dan diagnosis (systematic-debugging) sebelum lanjut.

- [ ] **Step 1: Build dan pasang APK debug**

Run: `cd D:\Projects\Yomu\app; .\gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL, `app\app\build\outputs\apk\debug\app-arm64-v8a-debug.apk` (nama variant mengikuti output proyek).
Run: `adb install -r <apk>`
Expected: `Performing Streamed Install` lalu `Success`.

- [ ] **Step 2: Kembalikan pref ke keadaan bersih**

Run: `adb shell run-as app.mihon.dev grep -o 'doh_provider[^/]*' /data/data/app.mihon.dev/shared_prefs/app.mihon.dev_preferences.xml`
Expected: kosong (key tidak ada) ATAU `doh_provider" value="-1"`. Kalau masih `1` dari M2.1, jalankan `adb shell am force-stop app.mihon.dev` lalu hapus key itu lewat skrip `sed` yang menarget `</map>` (jangan GNU-only), dan ulangi grep.

- [ ] **Step 3: Buka MangaDex tanpa DoH**

Run: `adb shell am start -n app.mihon.dev/eu.kanade.tachiyomi.ui.main.MainActivity`, tap tab Browse -> Sources -> MangaDex.
Expected: layar error berisi teks Indonesia "Tidak bisa menghubungi ... Alamatnya mungkin dibelokkan penyedia internetmu" dan aksi **Pakai DNS aman** terlihat bersama Retry. Simpan screenshot ke `D:\Projects\Yomu\evidence\a1-01-error.png`.

- [ ] **Step 4: Cek jalur offline**

Matikan koneksi emulator (atau `adb shell svc wifi disable; svc data disable`), buka sumber lagi.
Expected: pesan "Tidak ada koneksi internet", dan **tanpa** aksi DNS aman. Nyalakan kembali.

- [ ] **Step 5: Cek domain yang bukan sinkhole**

Buka sumber yang address-nya asli tapi gagal (misalnya sumber dengan IP normal yang timeout).
Expected: aksi DNS aman tidak muncul untuk error non-DNS.

- [ ] **Step 6: Aktifkan lewat dialog**

Tap aksi, screenshot dialog (`a1-02-dialog.png`), konfirmasi, screenshot snackbar (`a1-03-snackbar.png`).
Run: `adb shell run-as app.mihon.dev grep -o 'doh_provider[^/]*' /data/data/app.mihon.dev/shared_prefs/app.mihon.dev_preferences.xml`
Expected: `doh_provider" value="1"` dan aksi sudah hilang dari layar.

- [ ] **Step 7: Restart dan buktikan**

Run: `adb shell am force-stop app.mihon.dev` lalu start ulang, buka MangaDex -> Popular.
Expected: daftar termuat tanpa error. Screenshot `a1-04-loaded.png`.
Run: `adb logcat -d -t 2000 | Select-String "cloudflare-dns.com"`
Expected: ada baris `--> GET https://cloudflare-dns.com/dns-query`.

- [ ] **Step 8: Tutup dengan catatan ledger**

Append hasil ke `D:\Projects\Yomu\.sdd\progress.md` (via `Add-Content -Encoding utf8`, bukan tool tulis di workspace) satu baris per langkah di atas.

## Catatan deviasi dari spec

Spec menyebut "probe kontrol ke Google DoH" sebagai penguat bukti. Rencana ini **tidak** menjalankan probe itu: mengirim domain yang diblokir ke resolver pihak ketiga adalah kebocoran metadata, dan tombol tetap berguna tanpa itu. Ganti: deteksi dari pola error saja, dengan pesan yang tidak mengklaim pemblokiran sebagai fakta (`exception_dns_blocked` menyebut dua kemungkinan). Ini keputusan saya, bukan permintaanmu, dan sudah ditulis ulang di spec.