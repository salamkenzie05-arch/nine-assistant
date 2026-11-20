# Nine: asisten pribadi offline untuk Android

Nine adalah asisten suara seperti Siri yang berjalan **sepenuhnya di perangkat**. Kepribadiannya tenang, sangat sopan, dan sedikit jenaka, seperti kepala pelayan yang juga insinyur jenius. Nine berbicara dalam **Bahasa Indonesia** dan **English** (aksen Inggris-UK).

- **Perintah perangkat** (cepat, berbasis aturan): alarm, timer, pengingat, buka aplikasi dan pengaturan, senter, volume, baterai/jam/tanggal, catatan, telepon, SMS.
- **Otak AI: Gemma 4 E2B** (Google, Apache-2.0) lewat LiteRT-LM, memakai GPU HP dan otomatis pindah ke CPU bila GPU gagal. Untuk obrolan dan pertanyaan bebas, jawabannya mulai diucapkan per kalimat sebelum seluruh jawaban selesai dibuat.
- **UI ala Siri**: orb bercahaya yang bereaksi pada suara, cahaya di tepi layar, tema terang/gelap/ikut sistem.

## Pasang di HP (Galaxy S23)

1. Buka `https://github.com/<akun>/<repo>/releases/latest/download/Nine.apk` di HP, lalu unduh.
2. Buka file-nya. Bila diminta, izinkan "Pasang aplikasi tak dikenal" untuk browser/My Files.
3. Buka **Nine**, lalu ketuk **Unduh Gemma 4 (2,6 GB)** di kartu atas (sekali saja, sebaiknya lewat Wi-Fi).
4. Ketuk orb dan bicara. Untuk memanggil Nine dengan tombol samping, buka Pengaturan Nine → **Pilih Nine sebagai asisten digital**. Setelah itu atur di **Pengaturan → Fitur lanjutan → Tombol samping → Tekan dan tahan → Aktifkan asisten digital**.

Setiap push ke `main` otomatis mem-build APK baru (GitHub Actions) dan menerbitkannya sebagai rilis terbaru. Versi baru bisa langsung dipasang menimpa versi lama.

## Supaya benar-benar offline

| Komponen | Sumber | Persiapan sekali |
|---|---|---|
| Pengenalan suara | Recognizer on-device Android (Google) | Aplikasi Google → Pengaturan → Suara → Pengenalan ucapan offline → unduh Indonesia + English (US) |
| Suara Nine | TTS offline | Pasang *Speech Services by Google*, unduh suara Inggris (UK) & Indonesia; pilih suara di Pengaturan Nine |
| Otak AI | Gemma 4 E2B `.litertlm` (±2,6 GB) | Unduh di aplikasi, atau impor file |

Internet hanya dipakai untuk mengunduh model. Percakapan, suara, dan catatan tidak pernah dikirim keluar.

**Jika unduhan model ditolak (butuh login):** buka halaman model di browser, setujui lisensinya, buat token *Read* di huggingface.co/settings/tokens, lalu tempel di Pengaturan Nine. Cara lain: unduh `gemma-4-E2B-it.litertlm` dari browser lalu pakai **Impor file model**.

## Catatan jujur

- Suara Nine memakai suara TTS pria berbahasa Inggris-UK dengan nada sedikit lebih rendah. Ini **bukan tiruan suara aktor mana pun**.
- Android tidak mengizinkan aplikasi biasa menyalakan atau mematikan Wi-Fi/Bluetooth/mode pesawat. Nine membuka panel pengaturannya.
- Pengingat lebih dari 24 jam dibuka di Kalender, karena alarm Android hanya berlaku 24 jam ke depan.
- Kunci tanda tangan `app/nine-sideload.jks` sengaja ada di repo agar setiap build CI bisa memperbarui aplikasi. Kunci itu bukan rahasia; jangan pakai untuk Play Store.
- Belum ada: wake word ("Hai Nine" yang selalu mendengarkan) dan tindakan perangkat yang diputuskan oleh Gemma (function calling).

## Struktur kode

| Folder | Isi |
|---|---|
| `nlu/` | Parser perintah ID+EN (Kotlin murni, diuji di `src/test`) |
| `llm/` | `ModelManager` (unduh/impor), `LlmBrain` (Gemma via LiteRT-LM), `TextClean` |
| `core/` | `AssistantController` (alur dengar → pahami → jalankan/tanya Gemma → ucapkan), `Persona`, `Prefs` |
| `skills/` | Eksekusi perintah perangkat, kontak, catatan |
| `speech/` | Pengenal suara on-device & TTS bertahap |
| `ui/` | `NineOrbView`, `EdgeGlowView`, daftar pesan |
| `service/` | Peran asisten digital default |

Build lokal: Android Studio (AGP 8.13, Kotlin 2.3, JDK 17) → `./gradlew assembleRelease`.
