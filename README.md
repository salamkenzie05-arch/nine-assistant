# Nine: asisten pribadi offline untuk Android

> **Versi BETA (3.1).** Nine masih dalam pengujian. Bagian yang memakai layanan aksesibilitas (Wi-Fi/Bluetooth otomatis, putar lagu otomatis di YouTube Music) belum teruji di semua HP; bila gagal, Nine menampilkan kode galat singkat "(beta, kode: …)" supaya mudah dilaporkan.

Nine adalah asisten suara seperti Siri yang berjalan **sepenuhnya di perangkat**. Kepribadiannya tenang, sangat sopan, dan sedikit jenaka, seperti kepala pelayan yang juga insinyur jenius. Nine mengerti dan menjawab dalam **Bahasa Indonesia** dan **English**, dan bersuara seperti droid kecil (bip, kicau, dan siulan elektronik).

- **Perintah perangkat** (cepat, berbasis aturan): alarm, timer, pengingat, **acara kalender** (tambah, pindah, hapus & baca agenda), **daftar tugas harian**, buka aplikasi dan pengaturan, YouTube Music (bawaan untuk "putar lagu"; Spotify tetap bisa bila disebut, mis. "putar … di Spotify")/YouTube/Maps/WhatsApp/Google, kamera, perekam suara, senter, volume, baterai/jam/tanggal, catatan, telepon, SMS.
- **Ingatan Nine**: katakan "ingat bahwa ulang tahun ibu tanggal 3 Mei", "apa yang kamu ingat tentang aku", "lupakan ulang tahun ibu", atau "lupakan semua ingatan". Hal tetap yang kamu sebut sendiri ("namaku Budi", "aku alergi kacang") dicatat otomatis (bisa dimatikan). Semuanya disimpan di HP, dilihat dan dihapus di Pengaturan → Memori Nine, lalu disisipkan ke prompt Nine setiap kali menjawab. Ini ingatan, **bukan melatih ulang model**.
- **Alarm Nine**: berbunyi tepat waktu walau HP tidur, tampil di layar kunci, bisa berulang (setiap hari, hari kerja, akhir pekan, hari tertentu), dan bisa dinyalakan, dimatikan, atau dihapus lewat suara ("matikan alarm jam 6", "hapus alarm gym"). Bisa dialihkan ke aplikasi Jam di Pengaturan.
- **Mode internet (opsional)**: Gemini + Google Search untuk pertanyaan terkini (cuaca, berita, kurs). Butuh API key gratis dari Google AI Studio; otomatis kembali ke Gemma saat offline.
- **Wake word** "hey nine" (bisa diubah), dideteksi offline dengan Vosk.
- **Widget layar utama**: orb Nine berbentuk bola 3D (tepi tanpa garis, tanpa bayangan di bawahnya), warnanya **mengikuti tema Nine di aplikasi** (Terang/Gelap/Ikuti sistem); tiap ukuran (2×2, 4×4, Hari ini) juga punya **versi putih** yang selalu putih dan ekspresinya ikut keadaan (dicas, baterai lemah, malam, baru diajak bicara). Ukuran kecil dan **4x4**, serta "Hari ini" (tugas yang belum selesai). Gerak mata memakai frame 70 ms yang diperhalus, jadi melirik dan berkedipnya mulus.
- **Otak AI: Gemma 4 E2B** (Google, Apache-2.0) lewat LiteRT-LM, berjalan di CPU secara default (GPU bisa dipilih di Pengaturan; otomatis kembali ke CPU bila GPU gagal). Untuk obrolan dan pertanyaan bebas, jawabannya mulai diucapkan per kalimat sebelum seluruh jawaban selesai dibuat.
- **Wajah Nine**: dua mata ekspresif ala robot meja yang berkedip, melirik, mengikuti jari, senang saat dielus, kesal bila diketuk berkali-kali, kaget lalu senang saat dicas, lesu saat baterai lemah, mengantuk saat lama diam, dan bereaksi pada percakapan (mendengar, berpikir, berbicara, senang, bingung, sedih). Bilah atas hanya "NINE"; ketuk untuk menu (alarm, tugas, pengaturan, bahasa, tema).

### Baru di 3.3 (beta), putaran 2

- **Tombol menu di kanan orb** (menggantikan pintasan tugas): Laporan keuangan, Tugas hari ini, Ruang obrolan, dan Nine.
- **Ruang obrolan** (seperti "projects"): tiap ruang punya nama, instruksi opsional untuk Nine, dan riwayat obrolannya sendiri; membukanya tidak bercampur dengan obrolan utama. Disimpan di penyimpanan pribadi aplikasi (**tidak dienkripsi**, tidak ikut cadangan); maksimal 20 ruang, 120 pesan per ruang. Nama dan instruksi ruang ikut dikirim ke AI selama kamu mengobrol di ruang itu (ke Gemini bila mode internet dipakai). Setiap kali aplikasi dimulai ulang, yang terbuka adalah obrolan utama.
- **Layar Nine** (gaya halaman streak): hari beruntun, level, Nine dengan gelembung ucapan, bar XP, dan daftar cara menumbuhkan kedekatan. Tangan dan kaki Nine berupa **empat bola melayang** (dua tangan, dua kaki) yang bergerak pelan dan melompat saat Nine diketuk. Tombol **Sifat, Pakaian, Suara** hanya tempat kosong untuk nanti (menampilkan "Segera hadir").
- **Layar Keuangan** (gaya rekap bank): tab Rekap dan Riwayat, pilihan periode (hari ini, minggu, bulan, semua), ringkasan pemasukan, pengeluaran, dan selisih, grafik batang per jenis, dan kategori dengan persen (dibulatkan agar total tepat 100%).
- **Kejutan harian (side quest)**: paling banyak satu per hari, muncul sebagai ajakan santai dari Nine (misalnya menawarkan bernyanyi), bukan sebagai tugas. Memakai fitur itu, lewat ajakan atau sendiri, menambah kedekatan sedikit; menolak tidak apa-apa. Berhenti muncul setelah semua fitur ditemukan.
- **Tanya info HP**: sisa penyimpanan, RAM, tipe HP, versi Android, status Wi-Fi/Bluetooth/jaringan, kesehatan/suhu baterai, uptime, volume, layar, prosesor. Tidak membaca nomor IMEI, serial, MAC, atau nama jaringan. Status Wi-Fi/Bluetooth bergantung pada pengaturan sistem; bila tidak bisa dipastikan, Nine bilang begitu.
- **"Ke layar utama"** membawa ke layar utama HP.
- **Warna biru dihapus**: semua kontrol memakai warna emas Nine. Pengaturan lebih ringkas (tanpa isian sapaan, penjelasan dipersingkat).
- **Campur bahasa dikurangi**: penjaga bahasa diperketat, dan riwayat yang bahasanya beda tidak lagi dikirim ke AI. Ini mengurangi, bukan menjamin, karena model kecil kadang tetap terpeleset.

> Catatan jujur: semua ini baru dikompilasi dan diuji logikanya di CI, **belum dicoba di perangkat sungguhan**.

### Baru di 3.3 (beta)

- **Kalkulator** untuk hitungan sampai rumit (kurung, pangkat, akar, persen, fungsi, "dua ratus lima puluh ribu dikali tiga"); kalimat seperti tanggal atau nomor telepon tidak dianggap hitungan.
- **Catatan keuangan**: "catat pengeluaran makan 25 ribu", "gajian 5 juta", laporan harian/mingguan/bulanan per kategori, urungkan, dan layar Keuangan. Hanya Rupiah. Data berupa JSON di penyimpanan pribadi aplikasi (tanpa jaringan, tidak ikut cadangan), **tidak dienkripsi** di disk.
- **Google Maps otomatis**: tujuan, moda (mobil/motor/jalan/sepeda/transit), "hindari tol", dan titik awal; rute langsung dimulai.
- **Tutup semua aplikasi**: mematikan proses latar aplikasi lain yang diizinkan Android, dan di **Nine-Full.apk** juga mengosongkan layar Recents; "termasuk Nine" menutup Nine juga. Android tidak membolehkan aplikasi biasa menutup aplikasi yang sedang di layar.
- **Profil pengguna** saat pertama dibuka (nama, panggilan, tanggal lahir, pasangan opsional), bisa diubah di Pengaturan → Profil. Ulang tahun diucapkan sekali di harinya. Nama pasangan dan tanggal lahir **tidak** dikirim ke mode internet.
- **Sapaan beragam** menurut waktu, suasana hati, dan level kedekatan Nine; "status affinity" menampilkan level, persen progres, dan hari pemakaian.
- **Gombal, pantun, kata bijak**, serta lagu bar droid (melodi karangan sendiri bergaya bar luar angkasa; **bukan** melodi Cantina Band yang berhak cipta).
- **Kirim gambar**: tombol **+** di kolom ketik. Gambar dikecilkan, metadata (termasuk lokasi) dibuang, dibaca model AI offline, lalu dihapus. Hanya jalan bila model terpasang mampu membaca gambar; bila tidak, Nine mengatakannya.
- **Kalimat berantakan** (urutan kata acak, kata pengisi, salah ketik kecil) dicoba dipahami lewat parser cadangan.
- **Lebih ringan**: AI default di CPU (GPU bisa dinyalakan di Pengaturan), animasi dibatasi ±30 fps saat AI bekerja, aliran teks dibatasi, dan memori dilepas saat sistem kekurangan. Ini perbaikan terbaik-yang-bisa, **belum diukur di HP**.
- **Assistive Touch dihapus**.

### Baru di 3.1 (beta)

- **Layar tombol power lebih rapi**: latarnya diredam rata (satu warna, tanpa gradasi) di atas vinyet emas Nine, kartu jawaban hampir solid, dan riwayatnya hanya menampilkan tanya-jawab terakhir dengan tinggi mengikuti isi (maks. ±45% layar), jadi gelembung tidak lagi terpotong di tengah kalimat.
- **Reaksi musik lebih terasa**: saat ada musik, mata Nine melonjak (melebar, naik) pada setiap ketukan dan bergoyang mengikuti bass; cahaya tepi layar menyala menurut bass, berdenyut dan berputar menyentak pada tiap ketukan.
- **Wi-Fi, Bluetooth, mode pesawat (edisi Full)**: Nine memeriksa keadaan sebenarnya dari sistem sebelum dan sesudah mengetuk, jadi tidak mengetuk bila sudah sesuai dan tahu bila belum berhasil. Urutannya: ketuk tombol Panel Cepat, lalu sentuhan nyata bila klik biasa gagal, lalu halaman pengaturannya sebagai cadangan.
- **Putar lagu di YouTube Music (edisi Full)**: setelah hasil pencarian terbuka, Nine memilih tab Lagu dan mengetuk hasil pertama (klik, lalu sentuhan nyata), dan **mendengarkan apakah musik benar-benar berbunyi**; bila belum, mencoba baris kedua dan perintah "putar dari pencarian". Bila semuanya gagal, muncul pemberitahuan singkat agar kamu mengetuk lagunya sendiri.
- **Label BETA** di samping judul NINE dan pada nomor versi.

> Catatan jujur: perubahan otomasi aksesibilitas ini **belum bisa diuji di perangkat sungguhan** oleh pembuatnya (hanya dikompilasi dan diuji logikanya di CI). Tampilan Samsung One UI dan YouTube Music berubah-ubah; laporkan kode galat bila masih gagal. Setelah memperbarui ke Nine-Full ini, bila layanan aksesibilitas Nine mati sendiri, nyalakan lagi di Pengaturan > Aksesibilitas (izin "sentuhan" ditambahkan).

### Baru di 2.7

- **Musik**: "putar <judul>" memutar lagu pilihan di YouTube Music (butuh layanan aksesibilitas Nine, lihat bawah); "jeda", "lanjutkan", "lagu berikutnya", "lagu sebelumnya", "naikkan/turunkan volume" mengendalikan pemutar yang sedang aktif.
- **Obrolan ala SimSimi**: pertanyaan "kenapa/mengapa" dan obrolan acak dijawab santai. Bila Nine tidak tahu atau butuh pencarian, ia membuka Google dengan pertanyaannya sudah terketik.
- **Usap orb 5 detik** → hati bermuncratan seperti di live IG (maksimal 3 kali sehari).
- **Ekspresi**: sedih/takut saat dihina atau dimarahi; "buat ekspresi sedih/takut/senang/kaget/bingung/kedip/tertawa…" membuat Nine memperagakannya.
- **Nyanyi**: "nyanyikan lagu …" atau "nyanyi dong" (melodi dengan suara droid, tanpa lirik; ada beberapa lagu anak/klasik, sisanya karangan sendiri).
- **Tuner gitar 6 senar**: "tuning gitar" lalu petik senar mana saja (dikenali otomatis), atau "stem senar 3", "bunyikan senar 5" untuk nada acuan. Tampil di panel tuner dengan jarum cent.
- **Reaksi musik**: saat layar Nine terbuka, wajah ikut bergoyang mengikuti ketukan, bass, dan nada musik yang terdengar lewat mikrofon (tidak direkam; bisa dimatikan di Pengaturan atau "matikan reaksi musik").
- **Telepon masuk**: "angkat telepon" / "tolak telepon".
- **Pengaturan lewat suara**: "ganti tema ke gelap", "ganti bahasa ke English", "matikan/nyalakan wake phrase", "keluar dari aplikasi", serta Wi-Fi, Bluetooth, hotspot, mode pesawat.
- **Kedekatan** baru mencapai level tertinggi setelah **90 hari pemakaian** (hari berbeda saat kamu bicara dengan Nine), seberapa banyak pun XP-nya.
- **Tombol power**: layar Nine kini transparan, hanya dengan vinyet emas Nine di tepi dan cahaya tepi berwarna Nine (dominan emas, sedikit aksen biru-kehijauan); jawaban Nine beralas kartu semi-solid agar terbaca tanpa selubung gradasi, dan tidak lagi membuat layar "nyangkut" setelah Nine membuka aplikasi lain.

## Dua edisi: pilih yang mana?

| | **Nine.apk** (disarankan) | **Nine-Full.apk** |
|---|---|---|
| Pemasangan | Langsung, tanpa trik | Bisa diblokir Google Play Protect (lihat bawah) |
| Layanan aksesibilitas | Tidak ada | Ada |
| Pilih & putar lagu otomatis di YouTube Music | Hanya membuka hasil pencarian | Ada (beta) |
| Wi-Fi/Bluetooth/hotspot/mode pesawat lewat suara | Membuka panel pengaturannya saja | Ada (beta; mengetuk ubin Panel Cepat, cadangan halaman pengaturan) |
| Semua fitur lain (suara, alarm, ingatan, tuner, telepon, widget, dll.) | Ada | Ada |

Kedua edisi memakai tanda tangan yang sama, jadi **Full bisa menimpa Nine** (dan sebaliknya) tanpa kehilangan data.

**Kenapa ada dua?** Google Play Protect (fitur "perlindungan penipuan tingkat lanjut") memblokir aplikasi yang dipasang dari luar Play Store bila meminta layanan aksesibilitas, karena izin itu sering disalahgunakan malware. Nine.apk tidak memintanya, sehingga pemasangannya normal. Nine-Full.apk tetap meminta, jadi bisa muncul dialog "Aplikasi diblokir untuk melindungi perangkat". Satu-satunya jalan yang saya tahu: matikan sementara **Play Store → profil → Play Protect → ⚙ → Pindai aplikasi dengan Play Protect**, pasang, lalu nyalakan lagi (belum saya uji di HP; caranya bisa berbeda di versi One UI lain). Jangan mematikannya kalau kamu tidak butuh fitur Full.

## Pasang di HP (Galaxy S23)

1. Buka `https://github.com/<akun>/<repo>/releases/latest/download/Nine.apk` di HP, lalu unduh. (Ingin fitur Full? Pakai `.../Nine-Full.apk`.)
2. Buka file-nya. Bila diminta, izinkan "Pasang aplikasi tak dikenal" untuk browser/My Files.
3. Buka **Nine**, lalu ketuk **Unduh Gemma 4 (2,6 GB)** di kartu atas (sekali saja, sebaiknya lewat Wi-Fi).
4. Ketuk orb dan bicara. Untuk memanggil Nine dengan tombol samping, buka Pengaturan Nine → **Pilih Nine sebagai asisten digital**. Setelah itu atur di **Pengaturan → Fitur lanjutan → Tombol samping → Tekan dan tahan → Aktifkan asisten digital**.

Setiap push ke `main` otomatis mem-build kedua APK (GitHub Actions) dan menerbitkannya sebagai rilis terbaru. Versi baru bisa langsung dipasang menimpa versi lama.

### Ringan di HP

- **Model Gemma (±2,5 GB RAM) tidak dimuat saat aplikasi atau prosesnya mulai.** Ia baru dimuat ketika ada pertanyaan yang benar-benar butuh AI (perintah seperti alarm, musik, volume, dll. tidak memerlukannya), lalu **dilepas otomatis**: 2 menit setelah terakhir dipakai saat layar Nine terbuka, 30 detik bila Nine di latar belakang. Akibatnya jawaban AI pertama setelah lama diam butuh beberapa detik untuk memuat model ("Membangunkan otak AI…"); percakapan terakhir disisipkan lagi supaya Nine tetap nyambung.
- Thread AI berjalan dengan prioritas sedikit di bawah UI, jadi gambar dan sentuhan di layar didahulukan saat model dimuat atau menjawab.
- Wajah Nine dan cahaya tepi layar menggambar tanpa membuat objek baru di setiap frame (gradien dipakai ulang), sehingga animasi tetap 60 fps dan tampilannya tidak berubah.
- Widget tidak lagi dibangun ulang setiap aplikasi dibuka (hanya bila ekspresinya berubah atau tema diganti).

## Supaya benar-benar offline

| Komponen | Sumber | Persiapan sekali |
|---|---|---|
| Pengenalan suara | Recognizer on-device Android (Google) | Aplikasi Google → Pengaturan → Suara → Pengenalan ucapan offline → unduh Indonesia + English (US) |
| Suara Nine | Disintesis sendiri di aplikasi | Tidak perlu apa-apa (tanpa TTS, tanpa unduhan) |
| Otak AI | Gemma 4 E2B `.litertlm` (±2,6 GB) | Unduh di aplikasi, atau impor file |

Internet hanya dipakai untuk mengunduh model. Percakapan, suara, dan catatan tidak pernah dikirim keluar.

**Jika unduhan model ditolak (butuh login):** buka halaman model di browser, setujui lisensinya, buat token *Read* di huggingface.co/settings/tokens, lalu tempel di Pengaturan Nine. Cara lain: unduh `gemma-4-E2B-it.litertlm` dari browser lalu pakai **Impor file model**.

## Catatan jujur

- Suara Nine adalah "bahasa droid" yang dibuat dengan sintesis sendiri (FM, luncuran nada, getar): kata yang sama selalu berbunyi sama, "?" naik, "!" riang, "." turun. Terinspirasi gaya droid fiksi, tetapi **bukan sampel atau tiruan suara karakter mana pun**. Isi jawaban tetap tampil sebagai teks di layar.
- Ingatan Nine disisipkan ke prompt (maksimal beberapa puluh fakta), bukan fine-tuning; model Gemma tidak berubah.
- Campur bahasa ditekan dengan aturan bahasa yang ketat, suhu sampling lebih rendah, dan pengecekan jawaban: jawaban Gemma yang bercampur diminta ditulis ulang. Pengecekan ini heuristik, jadi sesekali masih bisa lolos.
- Android tidak mengizinkan aplikasi biasa menyalakan atau mematikan Wi-Fi/Bluetooth/hotspot/mode pesawat. Nine melakukannya lewat **layanan aksesibilitas** (Pengaturan Nine → "Izinkan kontrol Nine"): ia membuka Panel Cepat lalu mengetuk ubinnya, jadi hasilnya bergantung pada susunan One UI di HP-mu dan belum diuji di semua perangkat. Tanpa layanan itu (dan di **Nine.apk** yang tidak memilikinya), Nine hanya membuka panel pengaturannya. Pada Android 13+ untuk aplikasi di luar Play Store, aktifkan dulu **Info aplikasi → ⋮ → Izinkan setelan terbatas**.
- Memutar lagu pilihan di YouTube Music juga memakai layanan aksesibilitas (mengetuk hasil lagu pertama), jadi hanya di **Nine-Full.apk**; tanpa itu Nine hanya membuka hasil pencarian. Jeda/lanjut/berikutnya/sebelumnya/volume memakai tombol media sistem dan bekerja di pemutar apa pun.
- Terima/tolak telepon butuh izin **Telepon** dan hanya lewat perintah suara: Nine tidak otomatis menyebut siapa yang menelepon.
- Nyanyian adalah melodi bersuara droid, tanpa lirik. Reaksi musik memakai mikrofon hanya saat layar Nine terbuka dan Nine sedang diam; layar kunci dan aplikasi lain tidak diawasi.
- Pengingat lebih dari 24 jam dibuka di Kalender, karena alarm Android hanya berlaku 24 jam ke depan.
- Tanda tangan APK: bila rahasia CI `NINE_KEYSTORE_PASSWORD` diisi, build memakai kunci privat `app/nine-release.p12` (terenkripsi; sandinya hanya ada di rahasia CI). Bila belum diisi, build memakai kunci publik lama `app/nine-sideload.jks` (sandi ada di repo). Catatan rilis menyebut kunci mana yang dipakai. Berpindah kunci memaksa **hapus lalu pasang ulang** sekali (data dan model Gemma ikut hilang), karena Android menolak pembaruan dengan tanda tangan berbeda. Jangan pakai untuk Play Store.
- Wake word memakai model bahasa Inggris, jadi frasanya harus kata-kata Inggris. Layar Nine hanya bisa muncul dari latar belakang bila Nine dijadikan asisten digital default atau diberi izin "tampil di atas aplikasi lain"; selain itu muncul notifikasi untuk diketuk.
- Gemma/Gemini belum memutuskan tindakan HP lewat function calling; mereka menulis perintah yang lalu dijalankan parser. Tindakan berisiko (telepon, WhatsApp, membuka situs, merekam, menghapus) yang diusulkan AI selalu dikonfirmasi dulu.
- Alarm yang dibuat di aplikasi Jam tidak bisa dinyalakan/dimatikan/dihapus oleh aplikasi lain; itu sebabnya Nine punya alarm sendiri.
- Widget hanya berganti ekspresi saat proses Nine hidup atau setiap ±30 menit, jadi reaksi "dicas" di widget bisa sedikit tertunda.
- Font logo & wordmark: Inter Display Light (SIL Open Font License).

## Struktur kode

| Folder | Isi |
|---|---|
| `nlu/` | Parser perintah ID+EN (Kotlin murni, diuji di `src/test`) |
| `llm/` | `ModelManager` (unduh/impor), `LlmBrain` (Gemma via LiteRT-LM), `TextClean`, `LangGuard` (deteksi bahasa campur) |
| `core/` | `AssistantController` (alur dengar → pahami → jalankan/tanya Gemma → ucapkan), `Persona`, `Prefs`, `MemoryStore` + `FactExtractor` (ingatan jangka panjang) |
| `skills/` | Eksekusi perintah perangkat, kontak, catatan |
| `speech/` | Pengenal suara on-device, `DroidVoice` (suara droid sintetis), `DroidSinger` (nyanyian) |
| `audio/` | Deteksi nada (YIN) + tuner gitar, analisis ketukan musik, daftar lagu |
| `a11y/` | Layanan aksesibilitas: ubin pengaturan cepat, YouTube Music, tutup semua aplikasi. Kode aslinya ada di `src/full/`; `src/lite/` berisi stub kosong dengan API sama (`AVAILABLE = false`) |
| `call/` | Terima/tolak telepon masuk |
| `ui/` | `NineOrbView`, `EdgeGlowView`, `SphereView` + `NineBuddyView` (Nine dengan tangan dan kaki bola), `FinBarsView` (grafik batang), daftar pesan |
| `widget/` | Widget orb (kecil dan 4x4) dan "Hari ini"; frame wajah dibuat `tools/face_sphere.py` |
| `service/` | Peran asisten digital default |

Build lokal: Android Studio (AGP 8.13, Kotlin 2.3, JDK 17) → `./gradlew assembleRelease`.
