# Nine: asisten pribadi offline untuk Android

> **Nine 1.0 beta.** Nine masih dalam pengujian. Bagian yang memakai layanan aksesibilitas (Wi-Fi/Bluetooth otomatis, putar lagu otomatis di YouTube Music) belum teruji di semua HP; bila gagal, Nine menampilkan kode galat singkat "(beta, kode: …)" supaya mudah dilaporkan.

Nine adalah asisten suara seperti Siri yang berjalan **sepenuhnya di perangkat**. Kepribadiannya tenang, sangat sopan, dan sedikit jenaka, seperti kepala pelayan yang juga insinyur jenius. Nine mengerti dan menjawab dalam **Bahasa Indonesia** dan **English**, dan bersuara seperti droid kecil (bip, kicau, dan siulan elektronik).

- **Perintah perangkat** (cepat, berbasis aturan): alarm, timer, pengingat, **acara kalender** (tambah, pindah, hapus & baca agenda), **daftar tugas harian**, buka aplikasi dan pengaturan, YouTube Music (bawaan untuk "putar lagu"; Spotify tetap bisa bila disebut, mis. "putar … di Spotify")/YouTube/Maps/WhatsApp/Google, kamera, perekam suara, senter, volume, baterai/jam/tanggal, catatan, telepon, SMS.
- **Ingatan Nine**: katakan "ingat bahwa ulang tahun ibu tanggal 3 Mei", "apa yang kamu ingat tentang aku", "lupakan ulang tahun ibu", atau "lupakan semua ingatan". Hal tetap yang kamu sebut sendiri ("namaku Budi", "aku alergi kacang") dicatat otomatis (bisa dimatikan). Semuanya disimpan di HP, dilihat dan dihapus di Pengaturan → Memori Nine, lalu disisipkan ke prompt Nine setiap kali menjawab. Ini ingatan, **bukan melatih ulang model**.
- **Alarm Nine**: berbunyi tepat waktu walau HP tidur, tampil di layar kunci, bisa berulang (setiap hari, hari kerja, akhir pekan, hari tertentu), dan bisa dinyalakan, dimatikan, atau dihapus lewat suara ("matikan alarm jam 6", "hapus alarm gym"). Bisa dialihkan ke aplikasi Jam di Pengaturan.
- **Mode internet (opsional): AI percakapan gratis.** Nine bisa dipakai seperti chatbot (menjelaskan, menalar, menulis, menerjemahkan, ringkas, kode, ngobrol) lewat penyedia gratis **Gemini, Groq, Cerebras, OpenRouter** dengan kunci API milikmu sendiri (gratis, tanpa kartu kredit), dengan cadangan otomatis antarpenyedia; kembali ke Gemma bila semuanya gagal atau offline. Lihat "putaran 6".
- **Wake word** "hey nine" (bisa diubah), dideteksi offline dengan Vosk.
- **Widget layar utama**: orb Nine berbentuk bola 3D (tepi tanpa garis, tanpa bayangan di bawahnya), warnanya **mengikuti tema Nine di aplikasi** (Terang/Gelap/Ikuti sistem); tiap ukuran (2×2, 4×4, Hari ini) juga punya **versi putih** yang selalu putih dan ekspresinya ikut keadaan (dicas, baterai lemah, malam, baru diajak bicara). Ukuran kecil dan **4x4**, serta "Hari ini" (tugas yang belum selesai). Gerak mata memakai frame 70 ms yang diperhalus, jadi melirik dan berkedipnya mulus.
- **Otak AI: Gemma 4 E2B** (Google, Apache-2.0) lewat LiteRT-LM, berjalan di CPU secara default (GPU bisa dipilih di Pengaturan; otomatis kembali ke CPU bila GPU gagal). Untuk obrolan dan pertanyaan bebas, jawabannya mulai diucapkan per kalimat sebelum seluruh jawaban selesai dibuat.
- **Wajah Nine**: dua mata ekspresif ala robot meja yang berkedip, melirik, mengikuti jari, senang saat dielus, kesal bila diketuk berkali-kali, kaget lalu senang saat dicas, lesu saat baterai lemah, mengantuk saat lama diam, dan bereaksi pada percakapan (mendengar, berpikir, berbicara, senang, bingung, sedih). Bilah atas hanya "NINE"; ketuk untuk menu (alarm, tugas, pengaturan, bahasa, tema).

### Baru di 3.3 (beta), putaran 6

- **Nine jadi chatbot (AI online gratis, cadangan otomatis).** Di Pengaturan → Mode internet, isi satu atau beberapa kunci API gratis. Nine mencoba berurutan **Gemini → Groq → Cerebras → OpenRouter** (hanya yang kuncinya terisi). Tiap penyedia dicoba dengan model terbaiknya, dan bila kena batas kuota (HTTP 429), server sibuk, atau kunci ditolak, penyedia/model itu **diistirahatkan sementara** dan pertanyaan langsung beralih ke berikutnya; kuota harian yang habis diistirahatkan ±30 menit, batas per menit mengikuti `Retry-After`. Bila semua gagal, Nine menyebut alasannya per penyedia dan, bila Gemma terpasang, beralih ke Gemma. Kunci disimpan terenkripsi (Android Keystore); kunci Gemini yang sudah ada tetap dipakai.
- **Nama model tidak dikunci.** Daftar model gratis sering berganti (mis. Groq menghentikan `llama-3.3-70b-versatile` pada 16 Agu 2026), jadi untuk Groq/Cerebras/OpenRouter Nine membaca daftar model dari penyedia (disimpan 12 jam) lalu memilih yang terbaik menurut urutan preferensi (`llm/ModelPicker`), dan membuang model non-obrolan (suara, gambar, penjaga/guard, embedding). Gemini memakai alias `gemini-flash-latest` lalu `gemini-flash-lite-latest` (atau model pilihanmu). Tombol **Tes koneksi** menampilkan model yang dipilih tiap penyedia atau penyebab gagalnya.
- **Prompt chatbot baru** (`Persona.onlinePrompt`): serba bisa, jujur (tidak mengarang fakta), memahami riwayat obrolan (hingga 24 giliran / ±8.000 karakter), jawaban singkat untuk obrolan dan lengkap bila diminta penjelasan/langkah/tulisan/kode; daftar tampil rapi ("• " dan "1. "), tidak dibacakan penandanya. Penyedia gratis tidak punya akses web, jadi untuk info terkini Nine berkata terus terang lalu membukakan pencarian Google. Aksi HP tetap lewat baris `CMD:` yang dijalankan parser (yang berisiko tetap minta konfirmasi).
- Pencarian Google lewat Gemini kini **sakelar opsional (mati)**: kunci gratis umumnya tidak mendukungnya, dan permintaan yang ditolak diulang tanpa alat pencarian.
- Dihapus: `GeminiClient`, dan parameter `temperature` yang dulu ikut terkirim.

### Baru di 3.3 (beta), putaran 5

- **Curhat tanpa perintah.** Kamu tidak perlu lagi bilang "aku mau curhat": bila ceritamu jelas curhat ("aku capek banget akhir-akhir ini, rasanya semua salahku"), Nine masuk mode teman curhat sendiri, memberi satu kalimat pemberitahuan (cara keluar: "selesai"), lalu menyimak. Penilaiannya **aturan buatan sendiri yang sengaja konservatif** (`support/SupportEntry`): perintah, pertanyaan, permintaan informasi, cerita tentang orang lain, dan konteks olahraga ("capek habis gym") tidak dibajak. Lebih baik terlewat (kamu masih bisa bilang "aku mau curhat") daripada salah masuk. Bisa dimatikan di **Pengaturan → Curhat otomatis**. Tidak aktif di ruang obrolan, saat HP terkunci, atau saat mentor bahasa Inggris berjalan. Semua aturan privasi dan keamanan krisis dari putaran 4 tetap berlaku.
- **Pelatih fitness + ahli gizi (offline, tanpa AI)** (`fit/`). Cukup bicara/ketik biasa:
  - **Latihan:** "bench press 60 kg 3x8, squat 80 kg 5x5", "lari 5 km 30 menit", "push up 3x20", "kemarin deadlift 100 kg 3x5". Nine menghitung volume, memperkirakan 1RM, mendeteksi **rekor baru (PR)**, dan menyebut hari latihan beruntun. Tanya: "progres bench press", "saran beban squat" (progres bertahap berdasarkan catatanmu), "riwayat latihan", "ringkasan mingguan". "Program latihan 4 hari" memberi templat full body / upper-lower / push-pull-legs.
  - **Berat badan:** "berat badan 72,5" lalu "tren berat badan" (perubahan per minggu).
  - **Menu & gizi:** "tadi makan nasi goreng dan telur", "sarapan 2 telur + roti", "makan nasi padang 700 kalori" (angka sendiri menang atas perkiraan), "menu hari ini" (total kalori/protein/karbo/lemak dan sisa target), "kandungan gizi tempe". Basis data sekitar 100 makanan dan masakan Indonesia dengan satuan porsi.
  - **Target:** "tinggi 170 berat 70 umur 28 pria", "tujuanku cutting" / "aku mau bulking" / "aku sedang cutting", "berapa target kalori dan protein saya?" (Mifflin-St Jeor × tingkat aktivitas, protein per kg).
  - **Ilmu gizi:** "berapa protein yang aku butuhkan", "sumber protein", "apa itu karbohidrat", "lemak sehat itu apa", "berapa serat sehari", "tips makan di luar": ringkas, dengan angka dari panduan yang lazim, selalu diakhiri catatan bukan nasihat medis.
  - **Analisis InBody dari foto:** bilang "analisis InBody", lalu kirim fotonya lewat tombol **+** di sebelah kolom ketik (atau kirim foto dengan keterangan "inbody"). Foto dibaca **OCR ML Kit di perangkat**, angkanya diurai berdasarkan **label** di lembar (berat, massa otot rangka, massa lemak, PBF, BMI, visceral fat, BMR, total body water, skor, serta massa otot per bagian tubuh dan rentang normal yang tercetak). **Nine selalu menampilkan angka yang terbaca dan meminta konfirmasi** ("ya", "batal", atau koreksi seperti "lemak 14") sebelum menyimpan atau menganalisis. Lalu keluar analisis komposisi tubuh, otot per bagian (keseimbangan kiri-kanan), dan perbandingan dengan pengukuran sebelumnya ("bandingkan inbody"). Bisa juga mengetik angkanya: "inbody berat 70 otot 32 lemak 14 pbf 20 visceral 7 bmr 1600". Pembaca foto bisa dimatikan di **Pengaturan → Baca foto InBody**.
  - **Pagar pengaman:** tidak ada target kalori untuk di bawah 18 tahun atau untuk berat badan kurang yang diminta cutting; lantai kalori 1500 (pria) / 1200 (wanita); tidak mendiagnosis sarkopenia atau penyakit apa pun. Bila ada tanda gangguan makan ("aku muntahkan makananku biar kurus"), Nine **tidak memberi angka**, menyarankan bicara dengan tenaga profesional, menawarkan "aku mau curhat", dan menyebut 112 / Healing119 (119 lalu tekan 8).
  - **Privasi:** semua pesan fitness/gizi/InBody ditandai pribadi: tidak masuk konteks AI mana pun, tidak tersimpan di ruang obrolan, dan tidak dipakai Gemma. Datanya satu berkas JSON di penyimpanan pribadi aplikasi (`fit_data.json`, tidak ikut cadangan cloud atau pindah perangkat, ditulis lewat berkas sementara + rename supaya tidak setengah tertulis). Perintah ini diblokir saat HP terkunci. "hapus makan/latihan/berat/inbody terakhir" menghapus entri terakhir.
  - **Catatan ML Kit (jujur):** gambar dan hasil bacaan diproses **di perangkat dan tidak dikirim ke Google**, model teks dibundel di aplikasi (menambah ukuran APK beberapa MB). Namun menurut ketentuan Google, SDK ML Kit **mengirim metrik performa/penggunaan** (info perangkat, nama paket aplikasi, latensi, jenis kejadian) ke Google bila HP online. Isi fotomu tidak ikut. Bila tidak mau itu, matikan **Baca foto InBody** dan ketik angkanya.
  - **Batas yang jujur:**
    - **Belum diuji di HP sungguhan.** Parser dan mesin diuji unit test (119 tes baru, 387 tes total) dan dikompilasi di CI. Bagian OCR, tombol foto, dan alur layar hanya bisa dipastikan di perangkat.
    - **Akurasi OCR bergantung pada foto** (terang, tegak lurus, tanpa pantulan, seluruh lembar terlihat) dan **model/tata letak InBody**; pembacaan berbasis label belum dicoba pada semua seri InBody. Karena itu angkanya selalu dikonfirmasi, dan kurang dari 3 angka terbaca = tidak dianalisis.
    - **Angka gizi makanan adalah perkiraan** (kira-kira ±20-30%, tergantung porsi dan cara masak). Target kalori/protein hanya perkiraan awal. Parser bahasa sehari-hari bisa salah menangkap kalimat yang tidak lazim; periksa ringkasan yang Nine tampilkan.
    - Hasil BIA (InBody) dipengaruhi hidrasi, makan, olahraga, dan waktu pengukuran; bandingkan pengukuran dalam kondisi serupa. Ini **bukan nasihat medis**; untuk kondisi kesehatan, hamil, atau di bawah 18 tahun, bicaralah dengan dokter atau ahli gizi.
    - Curhat otomatis bisa sesekali salah masuk atau terlewat (aturan, bukan AI); ada saklar di Pengaturan.

### Baru di 3.3 (beta), putaran 4

- **Teman curhat (Nine mendengarkan)**: bilang "aku mau curhat" (atau "I need to vent") untuk memulai; "selesai" / "stop curhat" untuk keluar. Nine menyimak, memantulkan perasaan yang kamu sebut, menanyakan satu hal yang terbuka, merangkum cerita yang panjang, dan menawarkan langkah kecil bila kamu minta saran. Latihan singkat juga bisa dipanggil langsung: "latihan napas", "grounding 5 4 3 2 1", "tulis perasaan", "self compassion".
  - **Selalu offline.** Mesin aturan buatan sendiri (`support/`) selalu jalan, tanpa model AI dan tanpa internet. **Bila Gemma sudah terpasang**, balasan emosional ditulis Gemma supaya lebih alami; tiap balasannya diperiksa dulu (`SupportSafety`: tanpa diagnosis, tanpa saran obat, tanpa daftar, tanpa perintah "CMD:") dan bila ditolak, gagal, atau lewat 45 detik, dipakai balasan mesin aturan. **AI online (Gemini) tidak pernah dipakai untuk curhat.**
  - **Krisis diperiksa paling awal, di mode apa pun**, tanpa AI. Bila ada tanda keinginan mengakhiri hidup atau menyakiti diri, Nine berhenti mengobrol biasa, menanyakan apakah kamu aman, dan menyebut bantuan manusia: **112** (darurat) dan **Healing119 (119 lalu tekan 8, healing119.id, gratis 24 jam)**. Nomor ini dicek dari web pada 21 Nov 2026 dan bisa berubah; semuanya ada di satu tempat (`CrisisResources`).
  - **Privasi:** isi curhat tampil di layar tetapi tidak disimpan ke ruang obrolan, tidak masuk riwayat/konteks AI, tidak masuk ingatan jangka panjang, dan hilang saat aplikasi ditutup. Konteks Gemma dibuang saat sesi selesai. Mode curhat tidak bisa dimulai di dalam ruang obrolan (karena ruang tersimpan) atau saat HP terkunci.
  - **Batas yang jujur:** Nine **bukan psikolog atau terapis**, tidak mendiagnosis, dan tidak memberi saran obat; ini teman yang mendengarkan, bukan pengganti bantuan profesional. Deteksi krisis berupa aturan (heuristik): bisa terlewat atau terlalu sensitif, dan kalimat yang ambigu dijawab dengan pertanyaan lembut. Mesin aturan mencakup perasaan dan topik yang umum sehingga balasannya bisa terasa berpola; kualitas Gemma bervariasi dan muat pertamanya lambat. Pengenal suara bisa salah menangkap kata. Suara droid tidak bisa membaca balasan; balasan dibaca di layar. Pernyataan ingin menyakiti orang lain tidak ditangani di sini. **Belum diuji di HP sungguhan**: logikanya diuji unit test (100 tes khusus) dan dikompilasi di CI.

### Baru di 3.3 (beta), putaran 3

- **Mentor bahasa Inggris (offline, tanpa AI)**: bilang "jadi mentor bahasa Inggris saya" atau "latihan bahasa Inggris" untuk memulai sesi. Nine memberi pertanyaan pancingan, lalu setiap kalimat Inggrismu dikoreksi (maksimal 3 perbaikan sekaligus, dengan alasan singkat dalam dua bahasa) dan diberi saran kata yang lebih bagus ("very good" → "excellent"), lalu dilanjutkan dengan pertanyaan berikutnya. Selama sesi: "topik lain" untuk ganti topik, "progres" untuk hitungan latihan, "berhenti" untuk keluar; mikrofon mendengarkan bahasa Inggris. Di luar sesi juga bisa: "koreksi kalimat saya: i goes to school", "apakah ini benar: she don't like it", "kata lain dari good".
  - Semuanya **aturan tata bahasa buatan sendiri** (`coach/`: kala lampau tak beraturan, be/do/have, kesesuaian subjek-kata kerja, artikel a/an, jamak, perbandingan, preposisi umum, urutan kata tanya, dll.). Tidak memanggil model AI sama sekali, jadi jalan walau model belum diunduh dan tidak menguras baterai.
  - **Batas yang jujur:** aturan hanya mencakup kesalahan pemula-menengah yang umum dan sengaja berhati-hati supaya tidak "mengoreksi" kalimat yang sebenarnya benar, jadi banyak kesalahan lain akan lolos. Ini bukan pengganti guru. Pengenal suara Android kadang sudah membetulkan tata bahasamu sebelum teksnya sampai ke Nine (ketikan tidak). Suara droid tidak bisa mengucapkan kata Inggris; umpan balik dibaca di layar.
- **Kedekatan kini 9 level**: Stranger, Aware, Acquainted, Interested, Familiar, Connected, Trusted, Loyal, Devoted. Tanyakan langsung ke Nine: "level kita apa?", "status affinity", atau "daftar level". Level tertinggi tetap butuh 90 hari pemakaian.
- **Grafik keuangan**: kini perbandingan **pemasukan vs pengeluaran** pada periode yang dipilih (dua batang dengan nilai di atasnya); tombol Pengeluaran/Pemasukan pindah ke kartu Kategori dan hanya mengatur daftar kategori.
- **Menu di kanan orb**: entri "Nine" dihapus (tersisa Laporan keuangan, Tugas hari ini, Ruang obrolan). Kode layar Nine masih ada di repo tetapi tidak bisa dibuka dari aplikasi.

### Baru di 3.3 (beta), putaran 2

- **Tombol menu di kanan orb** (menggantikan pintasan tugas): Laporan keuangan, Tugas hari ini, dan Ruang obrolan (entri "Nine" dihapus di putaran 3).
- **Ruang obrolan** (seperti "projects"): tiap ruang punya nama, instruksi opsional untuk Nine, dan riwayat obrolannya sendiri; membukanya tidak bercampur dengan obrolan utama. Disimpan di penyimpanan pribadi aplikasi (**tidak dienkripsi**, tidak ikut cadangan); maksimal 20 ruang, 120 pesan per ruang. Nama dan instruksi ruang ikut dikirim ke AI selama kamu mengobrol di ruang itu (ke Gemini bila mode internet dipakai). Setiap kali aplikasi dimulai ulang, yang terbuka adalah obrolan utama.
- **Layar Nine** (gaya halaman streak; sejak putaran 3 tidak lagi dibuka dari menu): hari beruntun, level, Nine dengan gelembung ucapan, bar XP, dan daftar cara menumbuhkan kedekatan. Tangan dan kaki Nine berupa **empat bola melayang** (dua tangan, dua kaki) bertekstur sama dengan orb (gradien, tepi, bayangan, cahaya emas tipis). Geraknya berbasis pegas (`LimbMotion`): berayun pelan dengan periode berbeda-beda, kadang melambai, merentang, bersorak, mengetuk, menggeser, atau menendang, ikut suasana hati Nine, dan melompat saat Nine diketuk.
- **Layar Keuangan** (gaya rekap bank): tab Rekap dan Riwayat, pilihan periode (hari ini, minggu, bulan, semua), ringkasan pemasukan, pengeluaran, dan selisih, grafik batang perbandingan pemasukan vs pengeluaran, dan kategori dengan persen (dibulatkan agar total tepat 100%).
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
- **Sapaan beragam** menurut waktu, suasana hati, dan level kedekatan Nine; "status affinity" menampilkan level (dari 9), persen progres, dan hari pemakaian.
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
- Wajah Nine dan cahaya tepi layar menggambar tanpa membuat objek baru di setiap frame (gradien dipakai ulang), sehingga tampilannya tidak berubah.
- **Laju gambar adaptif** (`core/Perf.kt`): wajah, tangan/kaki, dan cahaya tepi hanya 60 fps saat ada yang bergerak cepat (bicara, sentuhan, ekspresi, lompatan, musik); saat hanya "bernapas" ±30 fps, dan saat mengantuk atau baterai lemah ±20 fps. Di layar 120 Hz tetap 60 fps. Laju diturunkan lagi saat AI bekerja, mode Hemat Daya menyala, atau HP hangat (thermal MODERATE → ±30 fps, SEVERE ke atas → ±20 fps). Gerak dihitung dari waktu, jadi tetap mulus.
- **Pendengar "hey nine" tidur saat hening** (`wake/VoiceGate.kt`): mikrofon dibaca langsung dan audio hanya diteruskan ke Vosk saat ada suara (ambang mengikuti derau ruangan, awal kata ikut disertakan 400 ms sebelumnya, dan 800 ms hening sesudahnya). Di ruangan sangat bising gerbang terus terbuka, jadi tidak lebih boros dari sebelumnya. Bila HP sangat panas (thermal SEVERE ke atas), pendengar dijeda sendiri dan lanjut saat suhu turun.
- **Widget diam saat Hemat Daya atau HP hangat**: satu bingkai tanpa animasi, jadi launcher tidak menggambar ulang belasan kali per detik. Kembali beranimasi otomatis saat mode itu mati.
- Pemutar suara droid menunggu dengan jeda yang menyesuaikan sisa durasi (bukan polling 10 ms).
- Widget tidak lagi dibangun ulang setiap aplikasi dibuka (hanya bila ekspresinya berubah atau tema diganti).

## Bagikan ke teman

Bisa. Repo ini publik, jadi tautan yang sama berlaku untuk siapa pun: `https://github.com/<akun>/<repo>/releases/latest/download/Nine.apk`. Beri tahu temanmu:

- Pakai **Nine.apk** (bukan Nine-Full) kecuali ia memang butuh fitur aksesibilitas; Full bisa diperingatkan Play Protect.
- Butuh Android 8.0 atau lebih baru, ±80 MB, dan izin "Pasang aplikasi tak dikenal" untuk browser/My Files.
- Perintah dasar jalan tanpa model AI. Model Gemma (±2,5 GB) opsional dan diunduh terpisah di aplikasi (kadang butuh token Hugging Face, lihat di atas).
- Kunci tanda tangan sideload ada di repo publik (lihat Catatan jujur), jadi APK ini tidak cocok dibagikan sebagai "resmi" ke orang yang tidak kamu kenal. Repo ini juga belum punya berkas lisensi.

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
- Penghematan baterai/panas di atas (gerbang suara, laju gambar adaptif, widget diam, jeda saat panas) **belum diukur di HP**: logikanya diuji di unit test dan dikompilasi di CI, tetapi akurasi kata pemanggil dengan gerbang suara di berbagai ruangan dan perilaku saat HP benar-benar panas belum diuji di perangkat. Bila "hey nine" terasa kurang peka, laporkan; ambangnya mudah dilonggarkan.
- Wake word memakai model bahasa Inggris, jadi frasanya harus kata-kata Inggris. Layar Nine hanya bisa muncul dari latar belakang bila Nine dijadikan asisten digital default atau diberi izin "tampil di atas aplikasi lain"; selain itu muncul notifikasi untuk diketuk.
- **AI online (putaran 6) belum diuji di HP dan belum dicoba dengan kunci asli.** Logikanya (urutan, cadangan, jeda kuota, pembacaan aliran, pemilihan model, penyaring `<think>`, pembatalan) diuji di unit test, termasuk lawan server HTTP lokal yang meniru format Gemini/OpenAI; tetapi format respons nyata keempat penyedia (terutama pesan galat, daftar model, model penalaran) belum terlihat langsung, jadi tombol "Tes koneksi" adalah cara memastikannya di HP kamu.
- Penyedia gratis punya batas harian/menit yang berubah-ubah dan bisa memakai percakapanmu untuk meningkatkan produk mereka (Gemini gratis secara eksplisit begitu). Karena itu jangan menyebut data sensitif ke Mode internet. Pesan curhat dan data kebugaran Nine tidak dikirim, tetapi pertanyaan umum yang bukan perintah dikirim ke penyedia **bersama ringkasan konteks** (ingatan Nine, tugas dan acara hari ini, lokasi bila diizinkan, nama panggilan) seperti pada Gemini sebelumnya; kini bisa sampai ke beberapa penyedia (termasuk OpenRouter, yang meneruskan ke penyedia model pihak ketiga).
- AI online gratis tidak punya akses web; untuk info terkini Nine membukakan Google. Kualitas jawaban bergantung model yang sedang tersedia dan bisa berubah tanpa pemberitahuan.
- Gemma/Gemini belum memutuskan tindakan HP lewat function calling; mereka menulis perintah yang lalu dijalankan parser. Tindakan berisiko (telepon, WhatsApp, membuka situs, merekam, menghapus) yang diusulkan AI selalu dikonfirmasi dulu.
- Alarm yang dibuat di aplikasi Jam tidak bisa dinyalakan/dimatikan/dihapus oleh aplikasi lain; itu sebabnya Nine punya alarm sendiri.
- Widget hanya berganti ekspresi saat proses Nine hidup atau setiap ±30 menit, jadi reaksi "dicas" di widget bisa sedikit tertunda.
- Font logo & wordmark: Inter Display Light (SIL Open Font License).

## Struktur kode

| Folder | Isi |
|---|---|
| `nlu/` | Parser perintah ID+EN (Kotlin murni, diuji di `src/test`) |
| `coach/` | Mentor bahasa Inggris offline (Kotlin murni, diuji di `src/test`): `GrammarCheck` (aturan koreksi), `WordChoice` (kata yang lebih bagus), `CoachIntent` (deteksi perintah), `CoachSession` (alur latihan), `CoachFeedback` (susunan balasan) |
| `support/` | Teman curhat offline (Kotlin murni, diuji di `src/test`): `CrisisCheck` (deteksi krisis), `CrisisReplies` (balasan + nomor bantuan), `Feelings` (membaca perasaan/topik), `SupportIntent` (mulai/berhenti/latihan), `SupportSession` (alur sesi), `SupportPrompt` + `SupportSafety` (jalur Gemma dan pemeriksaan keluarannya) |
| `fit/` | Pelatih fitness + ahli gizi (Kotlin murni, diuji di `src/test`): `HealthIntent` (pengenal ucapan → `HealthRequest`), `WorkoutParser` + `Exercises`, `MealParser` + `Foods`, `Targets`, `Progress`, `NutritionKnowledge`, `Program`, `InBodyParser` (urai baris OCR berbasis label) + `InBodyAnalysis`, `HealthEngine` (menjalankan permintaan + alur konfirmasi InBody), `FitCodec` (JSON). Penyimpanan (`skills/FitFileStore`) dan OCR ML Kit (`skills/InBodyOcr`) satu-satunya bagian yang memakai Android |
| `llm/` | `ModelManager` (unduh/impor), `LlmBrain` (Gemma via LiteRT-LM), `TextClean`, `LangGuard` (deteksi bahasa campur). AI online (Kotlin murni kecuali `MultiBrain`, diuji di `src/test` termasuk dengan server HTTP lokal): `BrainEngine` (urutan penyedia, cadangan, jeda kuota), `Backends` (klien Gemini + OpenAI-compatible, SSE), `Wire` (format permintaan/aliran, penyaring `<think>`), `ModelPicker`, `Failures`, `AiHistory`, `MiniJson`, `MultiBrain` (sambungan ke aplikasi) |
| `core/` | `AssistantController` (alur dengar → pahami → jalankan/tanya Gemma → ucapkan), `Persona`, `Prefs`, `MemoryStore` + `FactExtractor` (ingatan jangka panjang) |
| `skills/` | Eksekusi perintah perangkat, kontak, catatan |
| `speech/` | Pengenal suara on-device, `DroidVoice` (suara droid sintetis), `DroidSinger` (nyanyian) |
| `audio/` | Deteksi nada (YIN) + tuner gitar, analisis ketukan musik, daftar lagu |
| `a11y/` | Layanan aksesibilitas: ubin pengaturan cepat, YouTube Music, tutup semua aplikasi. Kode aslinya ada di `src/full/`; `src/lite/` berisi stub kosong dengan API sama (`AVAILABLE = false`) |
| `call/` | Terima/tolak telepon masuk |
| `ui/` | `NineOrbView`, `EdgeGlowView`, `SphereView` + `NineBuddyView` + `LimbMotion` (Nine dengan tangan dan kaki bola, gerak pegas), `FinBarsView` (grafik batang), daftar pesan |
| `widget/` | Widget orb (kecil dan 4x4) dan "Hari ini"; frame wajah dibuat `tools/face_sphere.py` |
| `service/` | Peran asisten digital default |

Build lokal: Android Studio (AGP 8.13, Kotlin 2.3, JDK 17) → `./gradlew assembleRelease`.
