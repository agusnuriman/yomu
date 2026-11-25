# A2 — DNS aman aktif tanpa buka ulang aplikasi (plan)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Membuat perubahan pref DNS aman (dan dua pref yang dibaca saat build) langsung memakai `OkHttpClient` baru, tanpa membuka ulang aplikasi, termasuk untuk gambar.

**Architecture:** `NetworkClientHost` di `:core:common` memegang client saat ini, mendengarkan `Preference.changes()`, dan membangun ulang hanya saat `ClientSignature` berubah. `NetworkResources` memegang `Cache`/`Dispatcher`/`ConnectionPool` tunggal supaya rebuild tidak membuka cache dua kali dan tidak membocorkan thread. `NetworkHelper` tetap satu-satunya tempat interceptor chain dirakit; konsumen mengambil client lewat getter supaya ikut client baru.

**Tech Stack:** Kotlin, OkHttp 4/5 (`okhttp3.Cache`, `Dispatcher`, `ConnectionPool`, `dnsoverhttps`), kotlinx-coroutines, moko-resources, Metro/Injekt DI, JUnit5 + kotest, Gradle.

**Spec:** `docs/superpowers/specs/2026-11-25-a2-live-secure-dns-design.md`

## Global Constraints

- Semua kode baru di repo `D:\Projects\Yomu\app`, modul `:core:common` kecuali disebut lain.
- Tidak boleh ada referensi `android.content.Context` di `NetworkResources`/`ClientSignature`/`NetworkClientHost` (syarat agar teruji di JVM).
- Jangan pernah memanggil `close()` pada `Cache`, `shutdown()` pada `Dispatcher`, atau `evictAll()` pada `ConnectionPool` milik client lama.
- String baru hanya `base` (en) + `in` (id); kunci lama yang digantikan dihapus.
- Tes memakai `org.junit.jupiter.api.Test` + kotest `shouldBe`, kelas bertanda `@Execution(ExecutionMode.CONCURRENT)`, source set `core/common/src/test/java`.
- `.\gradlew.bat spotlessApply` sebelum commit tiap task.
- Tanpa `exitProcess`, tanpa restart aplikasi.

---

### Task 1: `NetworkResources` dan `ClientSignature`

**Files:**
- Create: `core/common/src/main/kotlin/eu/kanade/tachiyomi/network/NetworkResources.kt`
- Create: `core/common/src/test/java/eu/kanade/tachiyomi/network/NetworkResourcesTest.kt`

**Interfaces:**
- Produces:
  - `class NetworkResources(cacheDir: java.io.File)` dengan properti `val cache: okhttp3.Cache`, `val dispatcher: okhttp3.Dispatcher`, `val connectionPool: okhttp3.ConnectionPool`.
  - `data class ClientSignature(val dohProvider: Int, val verboseLogging: Boolean, val redactSensitiveHeaders: Boolean)` di file yang sama dengan `NetworkResources.kt` dibolehkan; jika dipisah, nama file `ClientSignature.kt`.
  - `fun NetworkPreferences.clientSignature(): ClientSignature` — ditaruh di `SecureDns.kt` atau `NetworkResources.kt`, satu tempat saja.

- [ ] **Step 1: Tulis tes gagal**

`NetworkResourcesTest.kt`, tiga tes:

```kotlin
@Test fun resourcesAreStableAcrossReads() {
    val dir = Files.createTempDirectory("yomu-net").toFile()
    val resources = NetworkResources(dir)
    resources.cache shouldBe resources.cache
    resources.dispatcher shouldBe resources.dispatcher
    resources.connectionPool shouldBe resources.connectionPool
}

@Test fun cachePointsAtTheGivenDirectory() {
    val dir = Files.createTempDirectory("yomu-net").toFile()
    NetworkResources(dir).cache.directory shouldBe dir
}

@Test fun signatureReadsTheThreePrefs() {
    val preferences = NetworkPreferences(InMemoryPreferenceStore(), isDebugBuild = false)
    preferences.clientSignature() shouldBe ClientSignature(-1, false, true)
    preferences.dohProvider.set(PREF_DOH_CLOUDFLARE)
    preferences.verboseLogging.set(true)
    preferences.redactSensitiveHeaders.set(false)
    preferences.clientSignature() shouldBe ClientSignature(PREF_DOH_CLOUDFLARE, true, false)
}
```

- [ ] **Step 2: Jalankan, pastikan gagal**

Run: `.\gradlew.bat :core:common:testDebugUnitTest --tests "*NetworkResourcesTest"`
Expected: kompilasi gagal, `Unresolved reference 'NetworkResources'` / `'clientSignature'`.

- [ ] **Step 3: Implementasi minimal**

`NetworkResources(cacheDir)` membuat `Cache(cacheDir, 5L * 1024 * 1024)` dengan konstanta
`private const val CACHE_SIZE = 5L * 1024 * 1024` (nilai persis dari `NetworkHelper` lama),
`Dispatcher()` dan `ConnectionPool()` default. `clientSignature()` membaca tiga pref lewat `get()`.

- [ ] **Step 4: Jalankan, pastikan lulus**

Run: sama seperti Step 2. Expected: `BUILD SUCCESSFUL`, 3 tes lulus.

- [ ] **Step 5: Commit**

`git add core/common && git commit -m "feat(network): hold shared okhttp resources and a client signature"`

---

### Task 2: `NetworkClientHost` — rebuild di saat pref berubah

**Files:**
- Create: `core/common/src/main/kotlin/eu/kanade/tachiyomi/network/NetworkClientHost.kt`
- Create: `core/common/src/test/java/eu/kanade/tachiyomi/network/TestPreferences.kt` (utilitas tes)
- Create: `core/common/src/test/java/eu/kanade/tachiyomi/network/NetworkClientHostTest.kt`

**Interfaces:**
- Consumes: `NetworkResources`, `ClientSignature`, `NetworkPreferences.clientSignature()` dari Task 1.
- Produces:
  - `class NetworkClientHost(scope: kotlinx.coroutines.CoroutineScope, preferences: NetworkPreferences, resources: NetworkResources, build: (NetworkResources, ClientSignature) -> OkHttpClient) { val client: OkHttpClient }`
  - `class FlowPreference<T>(...) : Preference<T>` di source tes — `changes()` flow panas (MutableSharedFlow + `replay`/ignition), `set()` memicu emisi.

- [ ] **Step 1: Tulis utilitas `FlowPreference` di source tes**

Konstruktor `FlowPreference(key: String, initial: T)`, `get()/set()` backed `MutableStateFlow`,
`changes(): Flow<T> = flowOf(get()) + stateFlow.drop(1)` atau setara yang **memancarkan nilai awal
satu kali** (meniru `onStart { emit("ignition") }` milik `AndroidPreference`) lalu memancarkan
setiap `set()` berikutnya. `stateIn`/`isSet`/`delete`/`defaultValue` diisi sesuai interface.

- [ ] **Step 2: Tulis tes gagal**

Empat tes, semua dengan `runBlocking` (tanpa dependency `kotlinx-coroutines-test`) dan `build`
fiktif yang mengembalikan `OkHttpClient()` baru serta mencatat setiap `ClientSignature` yang diterima:

```kotlin
@Test fun buildsOnceOnStartAndIgnitionDoesNotRebuild()      // builds == 1 setelah collect ignition
@Test fun rebuildsWhenDohProviderChanges()                   // set(1) -> instance berbeda, builds == 2
@Test fun rebuildsWhenVerboseLoggingChanges()                // set verbose -> builds == 2
@Test fun doesNotRebuildForIrrelevantChange()                // set nilai yang sama -> builds tetap 1
```

Untuk assertions instance: `host.client shouldNotBe sameAs(first)` (kotest `shouldNotBe sameInstanceAs`
atau bandingkan `===` eksplisit lewat `assertThat`-gaya `shouldBe false`).

- [ ] **Step 3: Jalankan, pastikan gagal**

Run: `.\gradlew.bat :core:common:testDebugUnitTest --tests "*NetworkClientHostTest"`
Expected: `Unresolved reference 'NetworkClientHost'`.

- [ ] **Step 4: Implementasi `NetworkClientHost`**

Field `@Volatile private var current: OkHttpClient` + `private var signature: ClientSignature`
diinisialisasi dari `preferences.clientSignature()`. `init { scope.launch { merge(prefs.dohProvider.changes(), prefs.verboseLogging.changes(), prefs.redactSensitiveHeaders.changes()).collect { rebuildIfChanged() } } }`.
`rebuildIfChanged()` membaca `preferences.clientSignature()` dan hanya memanggil `build(resources, sig)`
saat `sig != signature` lalu menulis `signature` dan `current` (urutan: `current` terakhir, supaya
pembaca tidak pernah melihat signature baru dengan client lama).

- [ ] **Step 5: Jalankan, pastikan lulus**

Run: sama Step 3. Expected: 4 tes lulus.

- [ ] **Step 6: Commit**

`git commit -am "feat(network): rebuild okhttp client when a build-time pref changes"`

---

### Task 3: `NetworkHelper` memakai host dan sumber daya bersama

**Files:**
- Modify: `core/common/src/main/kotlin/eu/kanade/tachiyomi/network/NetworkHelper.kt`

**Interfaces:**
- Consumes: `NetworkResources`, `NetworkClientHost` dari Task 1–2.
- Produces: `NetworkHelper.client: OkHttpClient` (getter, tetap nama dan tipe yang sama — tidak ada konsumen yang berubah signature), `val resources: NetworkResources` tersedia bila ada yang butuh.

- [ ] **Step 1: Angkat sumber daya dan hilangkan `by lazy`**

`private val resources = NetworkResources(File(context.cacheDir, "network_cache"))`, ubah
`clientBuilder()` menjadi `private fun buildClient(resources: NetworkResources, signature: ClientSignature): OkHttpClient`
yang memakai `signature.dohProvider/verboseLogging/redactSensitiveHeaders` alih-alih `preferences.*.get()`,
`.cache(resources.cache)`, `.dispatcher(resources.dispatcher)`, `.connectionPool(resources.connectionPool)`,
dan mempertahankan interceptor `client` yang sekarang (`CloudflareInterceptor(...)`), lalu
`val client: OkHttpClient get() = host.client` dengan `private val host = NetworkClientHost(scope, preferences, resources, ::buildClient)`.

- [ ] **Step 2: Pastikan tidak ada `Cache(...)` yang tersisa di dalam builder**

Run: `git diff -- core/common/src/main/kotlin/eu/kanade/tachiyomi/network/NetworkHelper.kt`
Expected: `Cache(` hanya muncul di `NetworkResources.kt`; `by lazy` hilang dari `client`.

- [ ] **Step 3: Kompilasi dan tes**

Run: `.\gradlew.bat spotlessApply :core:common:testDebugUnitTest :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`, tanpa failure.

- [ ] **Step 4: Commit**

`git commit -am "refactor(network): serve NetworkHelper.client from the live host"`

---

### Task 4: konsumen yang meng-cache client jadi live

**Files:**
- Modify: `app/app/src/main/java/eu/kanade/tachiyomi/App.kt` (`newImageLoader`)
- Modify: `app/app/src/main/java/eu/kanade/tachiyomi/data/coil/MangaCoverFetcher.kt`
- Modify: `app/app/src/main/java/eu/kanade/tachiyomi/extension/util/ExtensionInstaller.kt`

**Interfaces:**
- Consumes: `NetworkHelper.client` getter (Task 3).
- Produces: `MangaCoverFetcher` konstruktor/factory memakai `callFactory: () -> okhttp3.Call.Factory`.

- [ ] **Step 1: Ganti provider Coil**

`App.kt`: hapus `val callFactoryLazy = lazy { networkHelper.value.client }`, pakai
`val callFactory: () -> Call.Factory = { networkHelper.value.client }`; `OkHttpNetworkFetcherFactory(callFactory)`;
`MangaCoverFetcher.MangaCoverFactory(callFactory, coverCache, sourceManager)` dan `.MangaFactory(callFactory, ...)`.

- [ ] **Step 2: Ganti tipe parameter di `MangaCoverFetcher`**

`private val callFactoryLazy: Lazy<Call.Factory>` → `private val callFactory: () -> Call.Factory`
di konstruktor utama, `MangaFactory`, `MangaCoverFactory`; pemakaiannya
`val client = source?.client ?: callFactory()`.

- [ ] **Step 3: `ExtensionInstaller` live**

`private val httpClient: OkHttpClient by lazy { networkHelper.client }` →
`private val httpClient: OkHttpClient get() = networkHelper.client`.

- [ ] **Step 4: Verifikasi tidak ada `lazy` client yang tersisa**

Run: `Select-String -Path app\app\src\main\java\eu\kanade\tachiyomi\App.kt,app\app\src\main\java\eu\kanade\tachiyomi\data\coil\MangaCoverFetcher.kt,app\app\src\main\java\eu\kanade\tachiyomi\extension\util\ExtensionInstaller.kt -Pattern "lazy \{ network"`
Expected: 0 hasil.

- [ ] **Step 5: Kompilasi**

Run: `.\gradlew.bat spotlessApply :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

`git commit -am "fix(image): make coil and the installer follow the live http client"`

---

### Task 5: Copy yang berjanji benar

**Files:**
- Modify: `i18n/src/commonMain/moko-resources/base/strings.xml`, `.../in/strings.xml`
- Modify: `app/src/main/java/eu/kanade/presentation/browse/BrowseSourceScreen.kt`
- Modify: `app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsAdvancedScreen.kt`

**Interfaces:**
- Produces: kunci `secure_dns_enabled`; kunci `secure_dns_enabled_restart` tidak ada lagi.

- [ ] **Step 1: Copy diganti**

base: `<string name="secure_dns_enabled">Secure DNS is on — try again now.</string>`
in: `<string name="secure_dns_enabled">DNS aman aktif — coba lagi sekarang.</string>`
Hapus `secure_dns_enabled_restart` dari kedua file.

- [ ] **Step 2: `BrowseSourceScreen` memakai kunci baru**

`stringResource(MR.strings.secure_dns_enabled_restart)` → `stringResource(MR.strings.secure_dns_enabled)`.
Perilaku tick snackbar (`secureDnsEnabledTick`) tidak diubah.

- [ ] **Step 3: Toast restart yang jadi bohong dihapus**

Di `SettingsAdvancedScreen.kt`, hapus `context.toast(MR.strings.requires_app_restart)` dari tiga
`onValueChanged`: `redactSensitiveHeaders` (~L100), `verboseLogging` (~L109), dan DoH (~L302),
beserta `if (it) { ... }`/`onValueChanged = { ... }` yang tinggal kosong — biarkan `onValueChanged = { true }`
hanya kalau itu syarat tipe. Jangan sentuh toast untuk `default_user_agent` (~L254, ~L267) dan
`pref_tablet_ui_mode` (SettingsAppearanceScreen ~L130).

- [ ] **Step 4: Verifikasi tidak ada konsumen kunci lama**

Run: `Get-ChildItem -Recurse -Include *.kt,*.xml app,i18n,core | Select-String -Pattern "secure_dns_enabled_restart"`
Expected: 0 hasil.

- [ ] **Step 5: Build**

Run: `.\gradlew.bat spotlessApply :i18n:compileKotlinJvm :app:compileDebugKotlin` (jika task `:i18n:compileKotlinJvm` tidak ada, gunakan `:app:compileDebugKotlin` yang memaksa generateMR)
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

`git commit -am "feat(i18n): promise live secure DNS instead of an app restart"`

---

### Task 6: Verifikasi end-to-end di MuMu (tanpa membuka ulang aplikasi)

**Files:** tanpa perubahan kode (kecuali ditemukan bug).

**Interfaces:** Consumes seluruh A2.

- [ ] **Step 1: Build dan install x86_64 debug**

Run: `.\gradlew.bat assembleDebug` lalu `adb -s 127.0.0.1:16416 install -r app\app\build\outputs\apk\x86_64\debug\app-x86_64-debug.apk`
Expected: `Success`.

- [ ] **Step 2: Pastikan keadaan awal**

`pidof app.mihon.dev` (catat), pref `doh_provider` = `-1` (lewat dialog, bukan edit file —
SharedPreferences tidak terlihat proses hidup), wifi hidup. Buka MangaDex sampai layar error
menampilkan `Pakai DNS aman` (baseline = bukti A1 `a1-35-online-action.png`).

- [ ] **Step 3: Tekan DNS aman dan coba lagi TANPA membuka ulang**

Tap `Pakai DNS aman` → `Oke` → baca snackbar → tap `Coba lagi` dalam proses yang sama.
Expected: snackbar berbunyi `DNS aman aktif — coba lagi sekarang.` (tanpa kata buka ulang),
`pidof` **sama** dengan Step 2, daftar judul termuat (beberapa judul dengan sampul), dan
`adb logcat -d -s OkHttp` menunjukkan `--> GET https://cloudflare-dns.com/dns-query?dns=`.
Simpan `evidence\a1-37-live-dns-loaded.png` + `.xml`.

- [ ] **Step 4: Gambar ikut live**

Sampul pada daftar Step 3 terlihat (bukan placeholder) — ini yang membuktikan provider Coil ikut client baru.
Simpan `evidence\a1-38-live-covers.png`.

- [ ] **Step 5: Settings jalur kedua**

Settings → Advanced → DNS aman → pilih `Google`. Expected: tanpa toast "perlu buka ulang", dan
request berikutnya memakai Google DoH bila log dihidupkan; lalu kembalikan ke `-1` dan pastikan
`Pakai DNS aman` muncul lagi di layar error sumber yang diblokir.
Simpan `evidence\a1-39-settings-no-restart-toast.png`.

- [ ] **Step 6: Suite penuh + tulis ledger per langkah**

Run: `.\gradlew.bat testDebugUnitTest :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`, semua tes hijau; laporkan jumlah agregat dari `**/test-results/**/TEST-*.xml`.

---

## Review Focus

Kelas input yang tes task-nya tidak otomatis menutup, dan apa yang harus dilakukan reviewer:

1. **Dua request berjalan saat pref berubah** — yang lama harus selesai dengan client lama, yang
   baru memakai DoH. Tidak ada `close()` pada dispatcher/cache yang memutusnya.
2. **Emisi awal `changes()` (ignition)** — tidak boleh membangun client kedua saat app start;
   dipegang `doesNotRebuild...`/`buildsOnceOnStart...` di Task 2, tapi jalur produksinya
   (`AndroidPreference`) cuma terbukti di perangkat.
3. **Pref diubah dari pintu yang belum dipikirkan** (backup/restore, Settings lama, dialog sumber) —
   observasi pref menutup semuanya; pastikan tidak ada `rebuildClient()` eksplisit yang tersisa.
4. **Gambar setelah DoH aktif** — kegagalan di sini terlihat seperti "tombol tidak bekerja";
   Task 4 Step 4 (grep `lazy { network`) dan Task 6 Step 4 adalah gerbangnya.
5. **Ulang kali pertama: `NetworkResources` membuka direktori cache** — dua `Cache` di direktori
   sama adalah bug kelas tinggi; Task 3 Step 2 memastikan hanya satu tempat yang membuatnya.
