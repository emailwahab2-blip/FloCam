# FloCam

Kamera floating untuk Android yang tampil di atas aplikasi lain — cocok untuk presentasi, livestream, video call, atau rekaman layar.

![Android](https://img.shields.io/badge/Android-6.0%2B-brightgreen?logo=android) ![Kotlin](https://img.shields.io/badge/Kotlin-1.9-blue?logo=kotlin) ![CameraX](https://img.shields.io/badge/CameraX-1.3.1-orange) ![ML Kit](https://img.shields.io/badge/ML%20Kit-Segmentation-red?logo=google)

---

## Fitur

| Fitur | Keterangan |
|---|---|
| **Floating camera** | Overlay kamera di atas semua aplikasi, bisa dipindah dengan drag |
| **Flip kamera** | Ganti antara kamera depan dan belakang |
| **Mode layar penuh** | Bentangkan kamera ke seluruh layar lalu kembali ke overlay kapan saja |
| **Bentuk kamera** | Lingkaran, kotak, atau **persegi panjang lanskap dengan sudut membulat** yang radiusnya bisa diatur |
| **Ukuran kamera** | Slider 100–400 dp, bisa diatur di Settings |
| **Blur latar** | ML Kit memisahkan orang dari background — background diblur, wajah tetap tajam |
| **Ganti latar** | Pilih gambar dari galeri sebagai virtual background |
| **Kualitas segmentasi** | Pilih Normal (ringan) atau Halus (tepi lebih natural) |
| **Kecerahan wajah** | Cerahkan hanya wajah/orang memakai mask segmentasi (saat mode latar aktif) |
| **Kontras** | Atur kontras gambar saat mode latar aktif |
| **Notifikasi persistent** | Tombol stop langsung dari notification bar |

### Kontrol Floating Camera

Ketuk sekali pada overlay kamera untuk memunculkan panel kontrol (hilang otomatis setelah 3 detik):

- **Balik** — flip kamera depan/belakang
- **Pengaturan** — buka Settings
- **Tutup** — hentikan kamera floating
- **Layar Penuh** — bentangkan kamera ke seluruh layar / kembali ke overlay

---

## Screenshot

> _Tambahkan screenshot di sini_

---

## Cara Pakai

1. Buka aplikasi FloCam
2. Ketuk **Aktifkan Kamera** — izinkan kamera dan overlay jika diminta
3. Overlay kamera muncul di layar — drag untuk memindahkan posisi
4. Ketuk overlay untuk melihat tombol kontrol
5. Buka **Pengaturan** untuk mengatur bentuk, ukuran, dan efek latar belakang

### Bentuk & Ukuran

Di menu Pengaturan → bagian **Bentuk Kamera**:

- **Lingkaran** — overlay bundar
- **Kotak** — overlay persegi
- **Persegi Panjang** — overlay lanskap (rasio 16:9) dengan sudut membulat; muncul slider **Kelengkungan Sudut** untuk mengatur radius pojok (0–100 dp)

Slider **Ukuran Kamera** mengatur besar overlay (100–400 dp).

### Efek Latar Belakang

Di menu Pengaturan → bagian **Latar Belakang Kamera**:

- **Mati (Normal)** — kamera tanpa efek
- **Blur Latar** — background otomatis diblur menggunakan ML Kit Selfie Segmentation
- **Ganti Latar** — ketuk *Pilih Gambar dari Galeri*, pilih foto, lalu Terapkan

Saat mode latar aktif, muncul pengaturan tambahan:

- **Kualitas Segmentasi** — *Normal* (ringan) atau *Halus* (tepi lebih natural, lebih berat)
- **Penyesuaian Gambar** — slider **Kecerahan Wajah** (mencerahkan hanya orang/wajah lewat mask segmentasi) dan **Kontras** (seluruh frame)

> Catatan: Kecerahan Wajah & Kontras hanya bekerja saat mode latar Blur/Ganti aktif, karena bergantung pada mask segmentasi.

---

## Persyaratan

- Android **6.0 (API 23)** atau lebih baru
- Izin **Kamera**
- Izin **Tampil di atas aplikasi lain** (overlay)
- Izin **Notifikasi** (Android 13+)
- Izin **Baca Gambar** — hanya untuk fitur Ganti Latar

---

## Build dari Source

### Prasyarat

- Android Studio Hedgehog atau lebih baru
- JDK 8+
- Android SDK dengan `compileSdk 34`

### Langkah

```bash
git clone https://github.com/emailwahab2-blip/FloCam.git
cd FloCam
./gradlew assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`

---

## Struktur Proyek

```
FloCam/
├── app/src/main/
│   ├── java/com/flocam/app/
│   │   ├── MainActivity.kt          # Entry point, permission handling
│   │   ├── FloatingCameraService.kt # Foreground service + ML Kit pipeline
│   │   └── SettingsActivity.kt      # Pengaturan bentuk, ukuran, latar
│   └── res/
│       ├── layout/
│       │   ├── floating_camera.xml  # Layout overlay kamera
│       │   ├── activity_main.xml
│       │   └── activity_settings.xml
│       └── drawable/ ...
```

---

## Stack Teknologi

| Komponen | Library |
|---|---|
| Kamera | [CameraX 1.3.1](https://developer.android.com/training/camerax) |
| Segmentasi AI | [ML Kit Selfie Segmentation 16.0.0-beta6](https://developers.google.com/ml-kit/vision/selfie-segmentation) |
| Bahasa | Kotlin |
| Min SDK | API 23 (Android 6.0) |
| Target SDK | API 34 (Android 14) |

### Cara Kerja Segmentasi

```
Frame kamera (RGBA_8888)
       │
       ▼
ML Kit SelfieSegmenter (STREAM_MODE)
       │
       ▼
SegmentationMask — confidence per pixel (0.0 background, 1.0 foreground)
       │
       ├─ Blur Latar  → frame asli + frame diblur (RenderScript) → composite
       └─ Ganti Latar → frame asli + gambar galeri → composite
       │
       ▼
Kecerahan wajah (foreground) + kontras global diterapkan
       │
       ▼
Ditampilkan di ImageView overlay (rotasi & mirror otomatis)
```

---

## Izin Aplikasi

| Izin | Alasan |
|---|---|
| `CAMERA` | Mengakses kamera perangkat |
| `SYSTEM_ALERT_WINDOW` | Menampilkan overlay di atas aplikasi lain |
| `FOREGROUND_SERVICE` | Menjalankan service kamera di foreground |
| `FOREGROUND_SERVICE_CAMERA` | Tipe foreground service kamera (API 29+) |
| `POST_NOTIFICATIONS` | Notifikasi status kamera (API 33+) |
| `READ_MEDIA_IMAGES` | Membaca gambar galeri untuk virtual background |

---

## Pengembang

Dikembangkan oleh **[Abdul Wahab Ahmad](https://www.facebook.com/share/18y6KTqeF6/)**.

---

## Lisensi

```
MIT License — bebas digunakan, dimodifikasi, dan didistribusikan.
```
