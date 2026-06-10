# FloCam

Kamera floating untuk Android yang tampil di atas aplikasi lain — cocok untuk presentasi, livestream, video call, atau rekaman layar.

![Android](https://img.shields.io/badge/Android-6.0%2B-brightgreen?logo=android) ![Kotlin](https://img.shields.io/badge/Kotlin-1.9-blue?logo=kotlin) ![CameraX](https://img.shields.io/badge/CameraX-1.3.1-orange) ![ML Kit](https://img.shields.io/badge/ML%20Kit-Segmentation-red?logo=google)

---

## Fitur

| Fitur | Keterangan |
|---|---|
| **Floating camera** | Overlay kamera di atas semua aplikasi, bisa dipindah dengan drag |
| **Flip kamera** | Ganti antara kamera depan dan belakang |
| **Bentuk kamera** | Lingkaran atau kotak, bisa diatur di Settings |
| **Ukuran kamera** | Slider 100–400 dp, bisa diatur di Settings |
| **Blur latar** | ML Kit memisahkan orang dari background — background diblur, wajah tetap tajam |
| **Ganti latar** | Pilih gambar dari galeri sebagai virtual background |
| **Notifikasi persistent** | Tombol stop langsung dari notification bar |

### Kontrol Floating Camera

Ketuk sekali pada overlay kamera untuk memunculkan panel kontrol (hilang otomatis setelah 3 detik):

- **Balik** — flip kamera depan/belakang
- **Pengaturan** — buka Settings
- **Tutup** — hentikan kamera floating

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

### Efek Latar Belakang

Di menu Pengaturan → bagian **Latar Belakang Kamera**:

- **Mati (Normal)** — kamera tanpa efek
- **Blur Latar** — background otomatis diblur menggunakan ML Kit Selfie Segmentation
- **Ganti Latar** — ketuk *Pilih Gambar dari Galeri*, pilih foto, lalu Terapkan

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

## Lisensi

```
MIT License — bebas digunakan, dimodifikasi, dan didistribusikan.
```
