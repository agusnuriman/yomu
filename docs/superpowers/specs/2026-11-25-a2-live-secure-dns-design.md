# A2 — DNS aman aktif tanpa buka ulang aplikasi (design)

**Tanggal:** 2026-11-25
**Base:** `main` @ `1e6a18f` (Mihon v0.21.3 `feadad5` + A1 secure DNS)
**Lanjutan dari:** `docs/superpowers/specs/2026-11-25-a1-dns-block-ux-design.md`

## Intent dan why

A1 sudah menempatkan tombol "Pakai DNS aman" di layar error sumber. Satu-satunya langkah manual
yang tersisa: setelah tombol itu ditekan, aplikasi harus **dibuka ulang** supaya DNS-over-HTTPS
benar-benar dipakai, dan pengguna disuruh membuka ulang oleh snackbar. Untuk orang yang lagi
marah karena komiknya tidak muncul, "tutup lalu buka lagi" adalah tembok. A2 meruntuhkan tembok
itu: tekan, tunggu sebentar, daftar komik langsung bisa dimuat.

Pengguna target: pembaca manga di Indonesia yang sumbernya kena sinkhole RPZ penyedia internet,
perangkat kelas menengah, tidak paham DNS, tidak tahu apa itu pref.

## Kebenaran teknis hari ini (hasil inspeksi, bukan asumsi)

`core/common/src/main/kotlin/eu/kanade/tachiyomi/network/NetworkHelper.kt`:

- `clientBuilder()` membaca `preferences.dohProvider.get()`, `verboseLogging.get()`,
  `redactSensitiveHeaders.get()` **saat client dibangun**.
- `val client: OkHttpClient by lazy { ... }` — dibangun sekali per proses, tidak pernah dibangun
  ulang. Inilah akar syarat buka ulang.
- `Cache(File(context.cacheDir, "network_cache"), 5 MiB)` dibuat **di dalam** `clientBuilder()`.
- `cookieJar` sudah field bersama, aman terhadap rebuild.

Konsumen `NetworkHelper.client` (dipetakan nyata, bukan diperkirakan):

| Konsumen | Cara ambil client | Live setelah rebuild? |
|---|---|---|
| `source-api/.../HttpSource.kt:79` | `open val client get() = network.client` | **ya** |
| `app/.../data/track/BaseTracker.kt:43` | `get() = networkService.client` | **ya** |
| `data/.../ReleaseServiceImpl.kt:30`, `ExtensionStoreService`, `NewUpdateScreenModel` | `network.client.newCall(...)` per request | **ya** |
| `app/.../extension/util/ExtensionInstaller.kt:52` | `by lazy { networkHelper.client }` | **tidak** |
| `app/.../App.kt:233` (Coil) | `lazy { networkHelper.value.client }` dipakai `OkHttpNetworkFetcherFactory(callFactoryLazy::value)` dan `MangaCoverFetcher.*Factory(callFactoryLazy, ...)` | **tidak** |
| `app/.../data/track/Komga.kt:24` | `by lazy { networkService.client.newBuilder().dns(Dns.SYSTEM) }` | tidak, **tapi tidak relevan**: DoH sengaja dimatikan di sana |

Konsekuensi penting: kalau hanya `NetworkHelper` yang diperbaiki, **sampul dan halaman gambar
tetap gagal** karena Coil menyimpan client lama. Domain yang disinkhole juga merusak gambar, jadi
itu akan terlihat seperti "tombolnya tidak bekerja".

Jalur pref: `Preference<T>.changes(): Flow<T>` (`core/common/.../preference/Preference.kt:21`).
`AndroidPreference.changes()` = flow panas dengan `onStart { emit("ignition") }` — jadi kolektor
**selalu dapat satu emisi saat mulai**, dan lagi setiap kali key berubah.
`InMemoryPreferenceStore.changes()` = `flow { data }` — dingin, hanya satu emisi, tidak pernah
emisi ulang. Artinya: kolektor tidak bisa dibuktikan lewat InMemoryPreferenceStore; tes pakai
`Preference` tiruan di source tes.

## Goals

1. Mengaktifkan DNS aman dari layar sumber membuat request **berikutnya** sudah lewat DoH, tanpa
   buka aplikasi.
2. Hal yang sama berlaku untuk DNS aman yang diubah dari Settings Advanced → DNS.
3. Ganti pref yang juga dibaca saat build (`verbose_logging`, `redact_sensitive_headers`) ikut
   langsung berlaku — karena mesin rebuild-nya sama, dan membiarkannya berarti meninggalkan toast
   "perlu buka ulang" yang sekarang jadi bohong untuk DoH tapi jujur untuk dua pref itu.
4. Gambar (sampul + halaman) ikut client baru.
5. Tidak ada kebocoran: satu `Cache`, satu `Dispatcher`, satu `ConnectionPool` untuk semua client
   yang pernah dibangun dalam proses ini.

## Non-goals

- Notifikasi sistem, tile, atau background sync.
- Perbaikan komputasi client untuk `Komga` (sengaja `Dns.SYSTEM`; DoH tidak pernah berpengaruh).
- Deteksi "DoH ternyata tidak menolong" (butuh measure, bukan A2).
- Menerjemahkan string baru ke locale selain `base` (en) dan `in` (id) — konvensi repo, Weblate.

## Anti-goals

- Membunuh atau me-restart proses aplikasi sendiri (A1 sudah membuktikan `exitProcess` membuat aplikasi
  hilang dan tidak kembali — Android menolak start activity dari proses mati).
- Mengubah pref diam-diam tanpa konfirmasi.
- Membangun client baru untuk setiap request.
- Menutup/membuang `Cache`, `Dispatcher`, atau `ConnectionPool` milik client lama.

## Approaches yang dipertimbangkan

**A. Observasi pref di `NetworkHelper`, ganti `by lazy` dengan field volatile.** Dipilih.
Satu pemilik kebenaran: client selalu sesuai pref. Semua konsumen yang mengambil lewat getter ikut
otomatis (6 dari 8 jalur sudah getter/per-call). Biaya: `Cache` dan pool harus diangkat keluar
dari `clientBuilder()` supaya tidak dobel — justru itu yang membuat refactor ini benar.

**B. `enableSecureDns()` memanggil `networkHelper.rebuildClient()` eksplisit.** Ditolak: dua pintu
ubah pref (dialog layar sumber + ListPreference di Settings) jadi harus ingat dua-duanya, dan
setiap pintu baru di masa depan lupa. Kebenaran jadi tersebar.

**C. `client` menjadi `get() = clientBuilder().build()`.** Ditolak: satu `Cache` baru di direktori
yang sama per akses (undefined behavior di OkHttp) plus `Dispatcher`/`ConnectionPool` baru per
akses = kebocoran thread, dan setiap request membangun ulang interceptor chain.

**D. Menandai DoH "butuh restart" selamanya dan memperbaikinya lewat dokumentasi.** Ditolak:
yang dijual A1 adalah satu tap; syarat buka ulang mematahkan janji itu.

## Design yang dipilih

Tiga bagian baru di `:core:common`, semuanya tanpa `android.content.Context`:

```kotlin
class NetworkResources(cacheDir: java.io.File) {
    val cache: okhttp3.Cache            // satu, maxSize 5 MiB
    val dispatcher: okhttp3.Dispatcher  // satu
    val connectionPool: okhttp3.ConnectionPool // satu
}

data class ClientSignature(
    val dohProvider: Int,
    val verboseLogging: Boolean,
    val redactSensitiveHeaders: Boolean,
)

class NetworkClientHost(
    scope: kotlinx.coroutines.CoroutineScope,
    preferences: NetworkPreferences,
    resources: NetworkResources,
    build: (NetworkResources, ClientSignature) -> OkHttpClient,
) {
    val client: OkHttpClient   // getter, volatile di dalam
}
```

- `NetworkClientHost` membangun client sekali di konstruktor (signature saat ini), lalu
  `scope.launch { merge(3 changes flow).collect { rebuildIfChanged() } }`.
- `rebuildIfChanged()` membaca `ClientSignature` dari pref dan **hanya** membangun ulang kalau
  berbeda dari yang dipakai. Inilah yang membuat emisi `onStart { ignition }` dari
  `AndroidPreference.changes()` tidak membangun client kedua saat app start.
- `build` diberikan sebagai fungsi supaya host teruji tanpa `Context`, dan `NetworkHelper` tetap
  satu-satunya tempat interceptor chain dibangun.
- `NetworkHelper`: `clientBuilder()` tidak lagi membuat `Cache`; memakai `resources.cache`,
  `.dispatcher(resources.dispatcher)`, `.connectionPool(resources.connectionPool)`; `val client
  get() = host.client`.

Perubahan konsumen:
- `App.kt`: `lazy { ... }` → provider lambda `{ networkHelper.value.client }`;
  `MangaCoverFetcher` ganti param `callFactoryLazy: Lazy<Call.Factory>` menjadi
  `callFactory: () -> Call.Factory` (3 tempat: ctor, `MangaFactory`, `MangaCoverFactory`) dan
  `callFactoryLazy.value` → `callFactory()`.
- `ExtensionInstaller.kt`: `by lazy` → `get() = networkHelper.client`.

Perubahan copy/UX:
- `BrowseSourceScreen.kt`: snackbar tidak lagi menyuruh buka ulang; kunci string baru
  `secure_dns_enabled` (base: `Secure DNS is on — try again now.`; in: `DNS aman aktif — coba lagi
  sekarang.`), kunci lama `secure_dns_enabled_restart` dihapus.
- `SettingsAdvancedScreen.kt`: toast `requires_app_restart` dihapus dari baris DoH **dan** dari
  `verbose_logging` + `redact_sensitive_headers` (keduanya kini live). Toast untuk
  `default_user_agent` dan `tablet_ui_mode` tetap — jalur itu tidak diubah.

Dialog konfirmasi tetap: pengaktifan DNS selalu lewat "Pakai DNS aman?" → "Oke". Tidak ada perubahan
diam-diam.

## What's left to the builder

- Urutan `merge(...)` dan penamaan internal (`rebuildIfChanged`), selama tes di atas hijau.
- Bentuk fake `Preference` di source tes (yang penting: `changes()`-nya flow panas dan `set()`
  memicu emisi).
- Nama variabel internal di `NetworkHelper` selama spotless/ktlint hijau.

## Verifikasi

- Unit (`:core:common`): client dibangun ulang saat dohProvider berubah; tidak dibangun ulang untuk
  emisi tanpa perubahan; dibangun ulang untuk perubahan verboseLogging/redact; `NetworkResources`
  memakai instance cache/dispatcher/pool yang sama lintas build; `ClientSignature` memuat tiga
  field yang benar.
- Suite penuh + `:app:assembleDebug` + spotless.
- Perangkat (MuMu, tanpa membuka ulang aplikasi, pid diamati): tekan "Pakai DNS aman" → "Oke" →
  snackbar baru → "Coba lagi" → daftar judul termuat; `logcat -s OkHttp` menunjukkan
  `cloudflare-dns.com`; `pidof` sama sebelum dan sesudah; sampul gambar ikut termuat.

## Risiko dan residu

- Client lama masih memegang request yang sedang berjalan. Itu disengaja dan aman: call in-flight
  selesai dengan konfigurasi lama; request baru memakai yang baru.
- `DnsOverHttps` bootstrap client dibuat lewat `build()` di dalam `dohCloudflare()` — tidak berbagi
  pool. Perilaku upstream, dibiarkan; biayanya satu pool kecil saat DoH aktif.
- Membangun client bukan operasi murah tapi mikro-detik; rebuild terjadi hanya atas perubahan pref,
  bukan per request.
- Tidak ada seam Compose/Robolectric di repo ini, jadi jalur kolektor di `NetworkHelper`
  (`merge(...)`) dibuktikan di perangkat, bukan oleh unit test.
