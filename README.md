# XCamera (XCAM V1)
Aplikasi kamera Android (Kotlin + CameraX). Package: `com.xcam.camera`.

Status: Tahap 7
- Tahap 1-2: UI utama, preview internal, ganti kamera, flash, tap-to-focus, foto.
- Tahap 3: zoom pinch / slider (dihaluskan per frame) / preset, maksimum 10x, lensa ultra-wide (jika diberikan Android ke app).
- Tahap 4: perekaman video (CameraX VideoCapture), timer + indikator rekam, pilihan kualitas (4K/1080p/720p/480p sesuai kemampuan HP), torch, simpan ke Movies/XCamera, putar ulang di dalam app.

- Tahap 5: galeri internal (grid per tanggal, penampil besar, putar video, bagikan, hapus dengan konfirmasi). Video punya toggle EIS bila didukung HP.


- Tahap 6 (dirombak): Live Photo gaya iPhone. Di mode LIVE kamera merekam ke buffer sementara yang bergulir; saat shutter, foto diambil dan klip dipotong ~1,5 dtk sebelum shutter. Hasil: satu file Motion Photo (JPEG + XMP + MP4 tertanam, nama *_MP.jpg) sesuai spesifikasi Google. Pre-roll bisa lebih pendek tepat setelah buffer berputar (tiap 5 dtk).
- Tahap 7: halaman Settings (kamera awal, flash awal, resolusi foto, cermin selfie, kualitas video, durasi Live, kehalusan zoom, grid, info penyimpanan), ikon baru, Live Photo + flash diambil dulu fotonya.

- Transisi lensa 0.5x ke 1x: frame beku ikut diperbesar mengikuti zoom lalu dipudarkan ke gambar langsung (jeda hardware tetap ada). Tekan lama logo XCAM juga menampilkan info kamera bersamaan.

- Tahap 8: tombol Loop di penampil galeri untuk Live Photo. Hasilnya satu file Live Photo (Motion Photo JPG) yang isinya video loop: video, transisi ke foto, foto diam (perbandingan video:foto = 3:2), transisi ke video (durasi dan cara sama dengan video ke foto), ulang, sampai 15/30/60 detik atau durasi yang diketik sendiri (5-180 detik). Render GPU (decoder-OpenGL-encoder) agar tajam.

Tips: tekan lama logo "XCAM" di layar kamera untuk melihat info lensa yang terdeteksi.

- Tahap 8f: pengaturan kualitas foto Live (8e) dibatalkan karena hasil foto jadi hitam. Info Lensa menampilkan ukuran foto yang dipakai.

- Tahap 8g: flash tetap bisa dipakai di lensa ultra-wide 0,5x (lampu dipinjam dari kamera utama lewat torch) untuk foto, Live Photo, dan senter video.
- Tahap 8h: EIS bisa diaktifkan dari mode Video maupun LIVE. Stabilisasi diterapkan pada VideoCapture yang juga merekam klip video di dalam Live Photo; saat pengaturan diganti, buffer dihentikan dan dimulai ulang dengan aman. Indikator menampilkan status EIS yang benar-benar aktif.

- Tahap 9: EIS software buatan sendiri (SoftEis.kt). Untuk HP yang tidak mengizinkan EIS hardware di app pihak ketiga. Gyroscope dibaca terus, orientasi kamera dihaluskan (low-pass 2 tahap), lalu selisihnya dikoreksi di GPU sebagai CameraEffect pada VideoCapture, jadi berlaku untuk mode Video dan klip video di Live Photo. EIS hardware tetap dicoba lebih dulu; software hanya bekerja jika kamera tidak melaporkan EIS hardware menyala. Settings: Mode EIS (otomatis / software saja / hardware saja) dan Kekuatan EIS software (normal / kuat). Info lensa (tekan lama logo XCAM) menampilkan status EIS software.
