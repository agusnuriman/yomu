# A1 - Bantuan Blokir DNS di UI Sumber (design doc)

Tanggal: 2026-11-25. Status: menunggu persetujuan. Basis: Mihon v0.21.3 di D:\Projects\Yomu.

## Niat dan kenapa

Pengguna Indonesia yang memasang build ini melihat sumber mana pun yang diblokir
Kominfo tampak rusak total: layar kosong berisi "Failed to connect to
api.mangadex.org/202.169.44.80:443". Penyebabnya bukan bug app: resolver ISP
menjawab domain dengan address sinkhole (RPZ), dan app tidak menawarkan jalan keluar.
Yang diinginkan: pengguna bisa memulihkan sumber itu dalam beberapa tap tanpa VPN dan
tanpa harus tahu istilah DNS.

## Bukti terverifikasi (M2.1, 25 Nov 2026)

- Device: `ping api.mangadex.org` -> `rpz.biznet (202.169.44.80)`; `ping 8.8.8.8` normal.
- Host: `Resolve-DnsName` menunjukkan alias `rpz.biznet` / `trustpositifkominfo`.
- DoH (`dns.google/resolve`) menjawab `dxlb.mangadex.org` -> `45.129.229.1/2`; TCP 443 ke IP itu terbuka.
  Artinya blokirnya level DNS, bukan IP/SNI.
- Setelah `doh_provider=1` ditulis ke shared_prefs dan app direstart: Popular MangaDex
  termuat, masuk library, download Ch.1 jadi CBZ 21.916.101 byte, Reader tampil.
- Logcat: `--> GET https://cloudflare-dns.com/dns-query?dns=... h2` `<-- 200 (29ms)`.
- 4473 baris logcat, tidak ada FATAL pada proses app.

## Fakta kode yang membatasi desain

- `app/core/common/.../network/NetworkHelper.kt:72` - `client` dibangun `by lazy`;
  mengganti DoH tidak bisa panas. Butuh restart proses.
- `app/core/common/.../network/DohProviders.kt:25-40` - tiap provider pakai
  `bootstrapDnsHosts` dengan IP literal, jadi bootstrap DoH tidak ikut diracuni.
- `app/core/common/.../network/NetworkPreferences.kt:27` - `doh_provider`, default `-1` (mati).
- `app/app/.../presentation/util/ExceptionFormatter.kt:17-23` - satu-satunya tempat
  error DNS diberi pesan; hari ini tidak menawarkan apa pun.
- `app/app/.../presentation/browse/BrowseSourceScreen.kt:80-117` - layar error sumber
  sudah punya daftar aksi (`EmptyScreenAction`: Retry, Open in WebView, Help).
  Ini permukaan termurah untuk aksi baru.
- `app/app/.../presentation/more/settings/screen/SettingsAdvancedScreen.kt:246-268` -
  ListPreference DoH sudah ada, tapi di MuMu barisnya tidak pernah muncul di dump UI
  (terlalu dalam), jadi tidak bisa diandalkan sebagai satu-satunya jalan.
- Konvensi app untuk perubahan pref jaringan: toast `requires_app_restart`, user restart manual.
- `app/core/common` sudah punya `testImplementation(libs.bundles.test)` tapi belum ada srcset `test`.

## Goals

- Sumber yang diblokir DNS bisa dipulihkan dari layar error itu juga, tanpa VPN, tanpa manual edit pref.
- App tidak pernah mengklaim "diblokir" tanpa bukti; tawaran ditulis sebagai aksi, bukan vonis.
- Deteksi bisa diuji dengan unit test murni, tanpa emulator.

## Non-goals

- Tidak menyentuh onboarding di A1 (lihat Approaches).
- Tidak menambah provider DNS baru; Cloudflare dipakai sebagai nilai default.
- Tidak mengubah perilaku source-local dan tidak menyentuh downloader/reader.
- Tidak membuat auto-restart.

## Anti-goals (gagal kalau ini terjadi)

- Dialog muncul setiap beberapa detik pada jaringan yang memang mati -> loop.
- Teks mengklaim "provider kamu sensor" tanpa bukti.
- Pengguna non-Indonesia terganggu tawaran yang tidak relevan.
- Preferensi berubah diam-diam tanpa satu tap konfirmasi.

## Approaches

**A. Reaktif di layar error - DIPILIH.** Aksi "Use secure DNS" muncul bersama Retry;
tap membuka dialog konfirmasi; menerima menulis `doh_provider=1` lalu snackbar minta
buka ulang. Sentuh permukaan kecil, reuse kerangka `EmptyScreenAction` yang sudah ada,
terjadi tepat di saat pengguna butuh.

**B. Proaktif di onboarding - ditolak.** Menambah langkah sebelum pengguna sempat
memakai app, bertentangan dengan permintaan "konfigurasi dipermudah", dan tidak
menolong yang sudah melewati onboarding.

**C. Pintasan manual saja - ditolak.** Memindahkan baris DoH ke tempat terlihat;
murah, tapi pengguna harus menyimpulkan sendiri bahwa masalahnya DNS, dan hari ini
tidak ada yang memberi petunjuk itu.

## Desain

### 1. Detektor (baru, `app/core/common/src/main/kotlin/eu/kanade/tachiyomi/network/DnsBlockage.kt`)

```kotlin
enum class DnsOffer { None, PoisonSuspected, ResolutionFailed }

fun evaluateDnsFailure(
    message: String?,          // pesan Throwable mentah dari LoadState.Error
    host: String?,             // null jika exception tidak menyebut host
    dohEnabled: Boolean,
    controlMatchesRpz: Boolean, // hasil probe kontrol, false saat probe belum/bisa tidak jalan
): DnsOffer
```

Aturan, berurutan:
- `dohEnabled` true -> `None`.
- online false -> `None` (panggil di call site, bukan di dalam fungsi).
- `host` null dan message tidak mengandung `/IP:` -> `None`.
- `ResolutionFailed`: message diawali `Unable to resolve host`.
- `PoisonSuspected`: message cocok `Failed to connect to <host>/<ipv4>:<port>` **dan**
  ipv4 itu ada di prefix sinkhole.
- Selain itu -> `None`.

Prefix sinkhole sebagai konstanta yang bisa bertambah, bukan gerbang tunggal:
`202.169.44.` dan `103.52.52.` terverifikasi dari bukti; daftar dipakai sebagai
pemicu probe, bukan sebagai klaim.

### 2. Probe kontrol (`DnsProbe`, `core:common`)

` suspend fun hostLooksPoisoned(client: OkHttpClient, host: String): Boolean`
Resolve `host` via DoH Google (bootstrap IP literal) dengan client terpisah.
- DoH menjawab dan address berbeda dari yang dicoba sistem -> true.
- DoH gagal / timeout 5 detik / sama -> false. Probe tidak pernah menampilkan error.
Ini yang mengubah tebakan jadi bukti, dan satu-satunya biaya tambahan: satu request
saat pengguna mengetuk aksi, bukan saat error muncul.

### 3. UI (`BrowseSourceScreen.kt` + `presentation/browse/BrowseSourceScreen.kt`)

- Saat `LoadState.Error`, call site menghitung `DnsOffer`.
- `PoisonSuspected` atau `ResolutionFailed` dan online: tambah `EmptyScreenAction`
  "Use secure DNS" (ikon `MaterialSymbols.Rounded.Public` yang sudah dipakai file itu).
- Tap -> `AlertDialog` (pola sama dengan `ExtensionsTab.kt:116`):
  judul "Secure DNS?", isi menjelaskan dua kemungkinan (diblokir provider, atau domain
  tidak bisa diterjemahkan) dan bahwa app akan memakai Cloudflare DNS; tombol
  Cancel / Enable.
- Enable: `networkPreferences.dohProvider.set(PREF_DOH_CLOUDFLARE)` lalu
  `snackbarHostState.showSnackbar(stringResource(MR.strings.dns_secure_restart_message))`.
- Sekali per sesi: flag di ViewModel, di-reset saat refresh manual.
- `onWebViewClick` tetap ada; aksi baru tidak menghapus yang lama.

Parameter baru `onSecureDnsClick: (() -> Unit)?` nullable, default tidak ditampilkan,
sehingga call site kedua (`ui/browse/migration/search/MigrateSourceSearchScreen.kt:105`)
tidak berubah perilaku.

### 4. Teks baru (base `app/i18n/src/commonResources/baseLocalization.xml`)

- `action_use_secure_dns` = "Use secure DNS"
- `secure_dns_dialog_title` = "Secure DNS?"
- `secure_dns_dialog_message` = "This source could not be reached. Your internet provider may be redirecting its address, or the address could not be resolved. Yomu can look it up through Cloudflare DNS instead. Restart the app after enabling."
- `secure_dns_enabled` = "Secure DNS enabled. Restart the app to apply."
Bahasa lain menyusul; fallback Inggris diterima untuk A1.

## Test plan

Unit (baru, srcset `test` di `core:common`):
- `evaluateDnsFailure` atas string error asli: `"Failed to connect to api.mangadex.org/202.169.44.80:443"` -> `PoisonSuspected`.
- `"Failed to connect to api.mangadex.org/45.129.229.1:443"` -> `None` (IP asli, bukan sinkhole).
- `"Unable to resolve host api.mangadex.org: no information"` -> `ResolutionFailed`.
- `dohEnabled = true` -> `None` untuk semua input.
- message null / host null / IP IPv6 -> `None`.
- `202.169.45.x` (di luar prefix) -> `None`.

Manual di MuMu (adb, sama seperti M2.1):
- Reset pref app, matikan `doh_provider`, buka MangaDex, screenshot layar error.
- Tap aksi, screenshot dialog, Enable, screenshot snackbar.
- Restart app, screenshot Popular MangaDex termuat.
- Ulang dengan jaringan emulator dimatikan -> tidak boleh ada tawaran.

## Keputusan yang saya ambil (bukan dari kamu)

- Aksi + dialog, bukan dialog otomatis: dialog spontan di atas error sudah terasa buruk.
- Restart manual dengan pesan, bukan `exitProcess`: mengikuti konvensi pref jaringan yang ada.
- Aksi hanya di layar sumber untuk A1; `MangaScreen` dan pencarian migrasi menyusul kalau diperlukan.
- Detektor ditaruh di `core:common` dekat `network/`, sehingga srcset `test` harus dibuat.
- Probe kontrol dijalankan saat tap, bukan saat error, agar tidak ada traffic di belakang layar.

## Masih terbuka untukmu

- Nama tampilan untuk pengguna Indonesia: "DNS aman" atau "Use secure DNS"?
- Apakah A1 boleh sekaligus memindahkan baris DoH ke Settings yang lebih mudah dicari?
- Cloudflare sebagai default sudah dipakai di percobaan kemarin; Google sebagai bootstrap probe. Oke?