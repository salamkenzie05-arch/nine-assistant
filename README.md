# Nine: asisten pribadi offline untuk Android

> **Nine BETA 1.0.** Nine masih dalam pengujian. Bagian yang memakai layanan aksesibilitas (Wi-Fi/Bluetooth otomatis, putar lagu otomatis di YouTube Music) belum teruji di semua HP; bila gagal, Nine menampilkan kode galat singkat "(beta, kode: …)" supaya mudah dilaporkan.

Nine adalah asisten suara seperti Siri yang perintah dasarnya berjalan **di perangkat** (obrolan bebas memakai Gemma offline, atau Gemini bila Mode internet dinyalakan). Kepribadiannya tenang, sangat sopan, dan sedikit jenaka, seperti kepala pelayan yang juga insinyur jenius. Nine mengerti dan menjawab dalam **Bahasa Indonesia** dan **English**, dengan **tiga pilihan suara**: **Droid** (bawaan; bunyi bip dan kicau kecil, tanpa internet), **Wanita**, atau **Pria** (suara Gemini bila online, suara bawaan HP bila offline). Bunyi droid juga dipakai untuk nyanyian dan sebagai cadangan terakhir.

- **Perintah perangkat** (cepat, berbasis aturan): alarm (langsung di aplikasi Jam HP), timer, pengingat, **acara kalender** (tambah, pindah, hapus & baca agenda), **daftar tugas harian**, buka aplikasi dan pengaturan, YouTube Music (bawaan untuk "putar lagu"; Spotify tetap bisa bila disebut, mis. "putar … di Spotify")/YouTube/Maps/WhatsApp/Google, kamera, perekam suara, senter, volume, baterai/jam/tanggal, catatan, telepon, SMS.
- **Ingatan Nine (otomatis)**: tidak perlu kalimat khusus. Saat kamu bercerita tentang dirimu ("aku alergi kacang", "ulang tahun ibu 3 Mei"), AI sendiri menandai hal yang layak diingat dan Nine menyimpannya, lalu memberi tahu singkat "Diingat: …". Tetap bisa manual: "ingat bahwa …", "apa yang kamu ingat tentang aku", "lupakan …". Kata sandi, PIN, OTP, nomor kartu/KTP, dan angka panjang tidak pernah disimpan. Semuanya di HP, dilihat dan dihapus di Pengaturan → Memori; sakelar "Ingat otomatis" bisa dimatikan. Ini ingatan yang disisipkan ke prompt, **bukan melatih ulang model**.
- **Alarm = alarm HP.** Nine tidak punya mesin alarm sendiri lagi: "bangunkan jam 6" langsung membuat alarm di aplikasi Jam bawaan HP. Menyalakan/mematikan/menghapus alarm dan melihat daftarnya dilakukan di aplikasi Jam (Nine membukakannya).
- **Mode internet (opsional): Gemini gratis.** Nine bisa dipakai seperti chatbot (menjelaskan, menalar, menulis, menerjemahkan, ringkas, kode, ngobrol) lewat **Google Gemini** dengan kunci API gratis milikmu sendiri (tanpa kartu kredit). Bila kuota habis atau internet putus, **Gemma offline** mengambil alih. Kuota bisa dilihat lewat tombol "Dasbor kuota" di kartu Otak AI (membuka dasbor resmi Google). Lihat "putaran 7" dan "putaran 8".
- **Wake word** "hey nine" (bisa diubah), dideteksi offline dengan Vosk.
- **Widget layar utama**: orb Nine berbentuk bola 3D (tepi tanpa garis, tanpa bayangan di bawahnya), warnanya **mengikuti tema Nine di aplikasi** (Terang/Gelap/Ikuti sistem); tiap ukuran (2×2, 4×4, Hari ini) juga punya **versi putih** yang selalu putih dan ekspresinya ikut keadaan (dicas, baterai lemah, malam, baru diajak bicara). Ukuran kecil dan **4x4**, serta "Hari ini" (tugas yang belum selesai). Gerak mata memakai frame 70 ms yang diperhalus, jadi melirik dan berkedipnya mulus.
- **Otak AI: Gemma 4 E2B** (Google, Apache-2.0) lewat LiteRT-LM, berjalan di CPU (versi "gemma fast"; varian E4B dan pilihan GPU sudah dihapus). Untuk obrolan dan pertanyaan bebas, jawabannya mulai diucapkan per kalimat sebelum seluruh jawaban selesai dibuat.
- **Wajah Nine**: dua mata ekspresif ala robot meja yang berkedip, melirik, mengikuti jari, senang saat dielus, kesal bila diketuk berkali-kali, kaget lalu senang saat dicas, lesu saat baterai lemah, mengantuk saat lama diam, dan bereaksi pada percakapan (mendengar, berpikir, berbicara, senang, bingung, sedih). Mulutnya tidak ada, jadi saat menjawab **mata ikut "berbicara"** (berdenyut mengikuti ucapan). Bilah atas hanya "NINE" di tengah; ketuk untuk menu (tugas, pengaturan, bahasa, tema).

### Baru di BETA 1.0, putaran 10

- **Teks dan suara tidak lagi berjarak:** dengan suara Wanita/Pria (Gemini, yang butuh 1-3 detik lewat jaringan), teks jawaban baru tampil saat suara mulai berbunyi; bila suara macet, teks tampil paling lama 4,5 detik setelah siap. Selama menunggu tampil "Menyiapkan suara…". Suara Droid dan suara HP tanpa Gemini tetap langsung. Mesin suara HP juga dihangatkan sejak Nine mulai berpikir.
- **Satu jawaban, satu suara:** potongan pertama menentukan mesin (Gemini atau HP) dan potongan berikutnya mengikuti; model Gemini yang terakhir berhasil dicoba lebih dulu; setelah Gemini gagal, suara HP dipakai ±1 menit supaya suara tidak bolak-balik. Gaya bicara Gemini disederhanakan jadi dua gaya halus; suara HP pria/wanita dibedakan lebih jelas lewat nada.
- **Kuota suara Gemini habis → otomatis suara Droid** (bukan suara HP). Kegagalan lain (offline, kunci salah, galat server) tetap turun ke suara HP. Droid mengucapkan paling banyak 3 kalimat; sisanya di layar. "Coba suara" menyebut alasannya.
- **Tag `[…]` tidak lagi bocor ke teks:** tag bergaya suara yang tak diminta seperti `[apologetic]`, `[sighs softly]`, `[whispers]` ikut dibuang (di awal maupun di tengah jawaban) dan dipetakan ke ekspresi terdekat bila dikenal. Hanya tag huruf kecil yang dibuang; `[Catatan]`, `[1]`, `x[i]`, dan tautan `[teks](url)` dibiarkan.
- **Jawaban tidak lagi dobel/tumpang tindih:** bila AI sudah bilang "saya tidak bisa memeriksa… saya bukakan Google", Nine hanya membuka Google tanpa menambah balasan kedua yang mengulang.
- **"Telepon polisi" (dan "call the police"):** Nine **tidak pernah langsung menelepon**. Ia bertanya dulu "Telepon polisi di 110 (Indonesia)?"; hanya jawaban "ya" singkat (mis. "ya", "ya, telepon sekarang", "yes") yang menelepon, kalimat lain yang kebetulan diawali "ya" ("ya sudah nanti saja") tidak; pertanyaan kedaluwarsa setelah 45 detik; "tidak"/"batal" membatalkan. Berlaku juga bila yang diucapkan nomor darurat langsung ("telepon 112"), lewat AI, atau saat layar terkunci (minta buka kunci dulu). Nomor mengikuti negara: jaringan seluler yang sedang dipakai, lalu lokasi terakhir (bila izin lokasi aktif dan masih baru), lalu negara SIM, lalu wilayah HP; negara yang tak dikenal memakai 112. Tabel nomor polisi (`nlu/EmergencyNumbers.kt`) disusun dari pengetahuan umum, bukan dari sumber resmi: **periksa nomor untuk negaramu**. Dengan izin Telepon, panggilan langsung (cadangan: aplikasi telepon dengan nomor terisi); tanpa izin, aplikasi telepon dibuka dengan nomor terisi (Nine tidak meminta izin di saat darurat), lalu tekan tombol telepon.

**Jujur soal putaran 10 (belum diuji di HP):** logika (tag, nomor negara, konfirmasi ketat, kedaluwarsa, pemilihan suara) diuji unit test dan dikompilasi di CI, tetapi **tidak pernah dicoba di HP**: sinkronisasi teks-suara dan lamanya jeda suara Gemini sebenarnya, perilaku Android saat `ACTION_CALL` ke nomor darurat (sebagian versi Android/operator bisa membuka aplikasi telepon alih-alih langsung menelepon), ketepatan negara dari jaringan/lokasi, dan apakah nomor polisi di negaramu benar. **Jangan mengandalkan Nine untuk keadaan darurat sungguhan**; tombol Panggilan Darurat bawaan HP lebih dapat diandalkan.

### Baru di BETA 1.0, putaran 9

- **Tiga suara, Droid bawaan:** Pengaturan → Suara kini Droid / Wanita / Pria. **Droid** (default) memakai bunyi droid lama, tanpa internet dan tanpa mengirim teks ke mana pun, dan hanya mengucapkan sampai 3 kalimat pertama per jawaban; Wanita/Pria memakai urutan Gemini TTS → suara bawaan HP → droid seperti putaran 8. **Pengguna build sebelumnya yang memilih Wanita/Pria kembali ke Droid** sekali (kunci pengaturannya diganti); pilih ulang di Pengaturan.
- **"Lanjutan" dihapus dari Pengaturan:** nama model Gemini dan pencarian Google tidak bisa diatur lagi (Nine memakai `gemini-flash-latest` lalu `gemini-flash-lite-latest`, tanpa pencarian Google). Nilai lama yang tersimpan dibuang saat aplikasi mulai.
- **Kolom kota dihapus** (juga dari Lokasi & kalender): cuaca dan konteks memakai lokasi otomatis saat izin lokasi diberikan.
- **Wajah berbicara:** saat Nine menjawab (dengan suara, atau diam bila suara mati), mata berdenyut kecil mengikuti ucapan, seperti orang yang sedang berbicara.
- **Menu tanpa "Alarm"** (alarm sudah di aplikasi Jam HP sejak putaran 8). **"NINE" di bilah atas kini tepat di tengah**, dengan lencana BETA kecil. Kartu Otak AI dirapikan: "Dapatkan kunci" dan "Dasbor kuota" sebaris, "Tes koneksi" selebar kartu.
- **Widget lebih hidup:** tujuh ekspresi baru (kedip sebelah, kaget, penasaran, dua frame cinta, dua frame tertawa) dihasilkan `tools/face_sphere.py` dan dimasukkan ke urutan animasi widget (frame tetap 70 ms), untuk tema terang, gelap, dan ikuti-sistem. Mode hemat daya/panas tetap memakai satu gambar diam.
- **Rilis ditandai BETA 1.0** (nama versi `1.0.N-beta`, judul rilis "Nine BETA 1.0 (build N)").

**Jujur soal putaran 9 (belum diuji di HP):** semua perubahan di atas hanya diuji lewat unit test (logika suara, bingkai widget) dan kompilasi CI; **tampilan** Pengaturan yang dirapikan, posisi "NINE" di tengah, gerak "berbicara" pada wajah, dan **kehidupan animasi widget** (di layar utama sungguhan, sepanjang waktu) belum pernah dilihat di HP. Suara Wanita/Pria tetap belum dicoba dengan kunci Gemini asli.

### Baru di 3.3 (beta), putaran 8 (suara diperbarui di putaran 9)

- **Alarm Nine dihapus, alarm langsung ke aplikasi Jam HP** (`AlarmClock.ACTION_SET_ALARM`). Layar alarm, layanan dering, penerima boot, dan izin alarm tepat/layar penuh ikut dihapus. **Yang hilang:** menyalakan/mematikan/menghapus alarm dan menghentikan alarm yang sedang berdering lewat suara (Nine kini membukakan aplikasi Jam), dan **alarm buatan Nine di versi sebelumnya ikut hilang** setelah pembaruan (datanya dibuang); buat ulang di aplikasi Jam.
- **Pelacak pemakaian dihapus.** Gantinya satu tombol "Dasbor kuota ↗" di kartu Otak AI yang membuka dasbor Google AI Studio (angka resmi ada di sana).
- **Tombol "Hapus model offline" dihapus** dari Pengaturan.
- **Teks Pengaturan dipangkas banyak**; sakelar dan tombolnya sama, penjelasannya diringkas.
- **Memori otomatis tanpa prompt khusus**: AI menaruh tag `[ingat: …]` di awal jawaban (setelah tag ekspresi, maksimal 3 fakta per jawaban); tag dibuang dari teks/riwayat/suara dan faktanya disimpan (`llm/Expression.kt`, `core/FactExtractor.looksSensitive` memblokir data sensitif). Jalur regex lama tetap ada sebagai cadangan. Bergantung pada kepatuhan model: tidak ada jaminan AI selalu menandai.
- **Jawaban Gemini lebih cepat**: model Gemini 3 diminta memakai tingkat berpikir minimal (`thinkingLevel: MINIMAL`); bila API menolaknya, permintaan diulang tanpa itu dan model diingat. Batas baca 25 dtk.
- **Suara wanita dan pria** (Pengaturan → Suara; sejak putaran 9 ditambah pilihan Droid sebagai bawaan). Urutan: **Gemini TTS** (model `gemini-3.8-flash-tts`, lalu `gemini-3.1-flash-tts-preview`; suara Kore = wanita, Charon = pria; nada bicara mengikuti ekspresi Nine) bila Mode internet menyala, kunci ada, dan online; bila gagal atau offline, **suara bawaan HP** (pria/wanita dipilih dari nama suara, atau dua suara berbeda dengan nada digeser); bila itu juga tidak ada, bunyi droid. Per jawaban paling banyak 2 permintaan suara (kalimat pertama langsung, sisanya digabung) dan teks dibatasi ±600 huruf; sisanya hanya tampil di layar. Sintesis potongan kedua berjalan selagi potongan pertama diputar. Tombol "Coba suara" menampilkan suara apa yang dipakai dan alasan bila turun ke cadangan.

**Jujur soal putaran 8 (belum diuji di HP):** klien Gemini TTS, pengurai WAV/PCM, pemilih suara, pemotong teks, dan seluruh logika AI diuji unit test (termasuk server HTTP lokal), tetapi **tidak ada yang dicoba di HP, dan tidak dengan kunci Gemini asli**. Yang belum bisa dipastikan: apakah kunci gratismu boleh memakai model TTS dan berapa kuotanya; seberapa natural suara Kore/Charon dalam Bahasa Indonesia; jeda sebelum suara mulai; dan **gender suara bawaan HP hanya perkiraan dari nama suara** (Android tidak mengekspos gender). Saat suara Gemini dipakai, **teks jawaban yang diucapkan dikirim ke Google**. Nama model TTS dan bentuk API Interactions diambil dari dokumentasi Google dan bisa berubah; bila gagal Nine otomatis turun ke suara HP.

### Baru di 3.3 (beta), putaran 7 (sebagian diganti putaran 8)

- ~~**Pelacak pemakaian Gemini**~~ (dihapus di putaran 8; diganti tombol dasbor) (Pengaturan → Otak AI → kartu "Pemakaian Gemini hari ini"): jumlah permintaan per model, token, berapa kali kena batas, perkiraan sisa kuota, model yang sedang istirahat, dan jam reset kuota harian (tengah malam waktu Pasifik, ±15.00 WIB di musim dingin AS, ±14.00 WIB di musim panas). Tombol ke dasbor resmi Google AI Studio dan tombol setel ulang hitungan. **Ini perkiraan:** Nine hanya menghitung permintaannya sendiri (pemakaian aplikasi lain dengan kunci yang sama tidak terlihat), batas harian baru diketahui setelah Google melaporkannya di galat kuota (sebelum itu "sisa" tidak ditampilkan), dan angka resminya ada di dasbor Google. Logika (`llm/Usage.kt`, `UsageText.kt`) diuji unit test.
- **Hanya Gemini dan Gemma yang tersisa.** Groq, Cerebras, OpenRouter, pemilih model (`ModelPicker`) dan `MultiBrain` dihapus; kunci lama mereka dibuang dari HP saat aplikasi dibuka. Gemma kini hanya **"gemma fast" (E2B, ±2,6 GB)** dan menjadi bagian bawaan: tidak ada lagi bagian Gemma di Pengaturan (unduh, progres, impor, dan coba lagi ada di kartu model layar utama; file E4B lama dihapus otomatis begitu E2B siap). Pilihan GPU dihapus (selalu CPU).
- **Fitness, ahli gizi, InBody, teman curhat, dan deteksi krisis dihapus seluruhnya** (`fit/`, `support/`, OCR ML Kit). Berkas `fit_data.json` lama dihapus saat aplikasi pertama dibuka. Prompt AI hanya menyisakan satu kalimat umum: bila pengguna tampak sangat sedih, tanggapi hangat dan sarankan bicara dengan orang terdekat atau tenaga profesional. **Tidak ada lagi kode pendeteksi krisis**; Nine bukan pengganti bantuan profesional.
- **Pengaturan dirapikan:** bagian berurutan Otak AI (sakelar Mode internet, satu kolom kunci Gemini + "Dapatkan kunci" + "Tes koneksi"; bagian "Lanjutan" dihapus di putaran 9), Bahasa & suara, Memori, Memanggil Nine, Lokasi & kalender, Kendali ponsel (hanya edisi Full), Tampilan, Privasi.
- **Ekspresi Nine ikut jawaban.** AI diminta mengawali jawaban dengan satu tag seperti `[happy]` (12 ekspresi: neutral, happy, love, surprised, confused, sad, annoyed, sleepy, curious, scared, wink, laugh). Tag dibuang dari teks, riwayat, dan suara (`llm/Expression.kt`), lalu wajah Nine memperagakannya saat jawaban mulai tampil dan lagi di akhir. Bila AI lupa memberi tag (model kecil sering lupa), ekspresi ditebak dari isi jawaban (minta maaf → sedih, pujian → senang, tertawa → tawa, pertanyaan balik → penasaran). Ini bergantung pada kepatuhan model: tidak ada jaminan tag selalu muncul atau cocok.
- **Jujur soal pengujian:** semua logika murni (mesin, pelacak, tag ekspresi, teks pemakaian) diuji unit test dan lawan server HTTP lokal; **belum dicoba di HP dan belum dengan kunci Gemini asli**.

### Baru di 3.3 (beta), putaran 6 (sebagian diganti putaran 7)

- **Nine jadi chatbot (AI online gratis).** Penyedia lain (Groq, Cerebras, OpenRouter) **dihapus di putaran 7**; kini hanya Gemini. Mesin cadangan antarmodel dan jeda kuota tetap dipakai (lihat putaran 7).
- **Prompt chatbot baru** (`Persona.onlinePrompt`): serba bisa, jujur (tidak mengarang fakta), memahami riwayat obrolan (hingga 24 giliran / ±8.000 karakter), jawaban singkat untuk obrolan dan lengkap bila diminta penjelasan/langkah/tulisan/kode; daftar tampil rapi ("• " dan "1. "), tidak dibacakan penandanya. Penyedia gratis tidak punya akses web, jadi untuk info terkini Nine berkata terus terang lalu membukakan pencarian Google. Aksi HP tetap lewat baris `CMD:` yang dijalankan parser (yang berisiko tetap minta konfirmasi).
- Pencarian Google lewat Gemini kini **sakelar opsional (mati)**: kunci gratis umumnya tidak mendukungnya, dan permintaan yang ditolak diulang tanpa alat pencarian.
- Dihapus: `GeminiClient`, dan parameter `temperature` yang dulu ikut terkirim.


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
- **Lebih ringan**: AI berjalan di CPU, animasi dibatasi ±30 fps saat AI bekerja, aliran teks dibatasi, dan memori dilepas saat sistem kekurangan. Ini perbaikan terbaik-yang-bisa, **belum diukur di HP**.
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
| Suara Nine | Online: Gemini TTS. Offline: Text-to-Speech bawaan HP (cadangan: bunyi droid) | Offline butuh data suara Indonesia/English di mesin TTS HP (Pengaturan → Bahasa → Keluaran text-to-speech) |
| Otak AI | Gemma 4 E2B `.litertlm` (±2,6 GB) | Unduh di aplikasi, atau impor file |

Tanpa Mode internet, internet hanya dipakai untuk mengunduh model dan percakapan tidak dikirim keluar. Bila Mode internet menyala, pertanyaan dan (untuk suara Gemini) teks jawaban yang diucapkan dikirim ke Google Gemini.

**Jika unduhan model ditolak (butuh login):** buka halaman model di browser, setujui lisensinya, buat token *Read* di huggingface.co/settings/tokens, lalu tempel di Pengaturan Nine. Cara lain: unduh `gemma-4-E2B-it.litertlm` dari browser lalu pakai **Impor file model**.

## Catatan jujur

- Suara jawaban: lihat "Jujur soal putaran 8" dan "putaran 9". Bunyi droid (sintesis sendiri: FM, luncuran nada, getar; "?" naik, "!" riang, "." turun) adalah suara bawaan (Droid), nyanyian, dan cadangan terakhir bila suara Wanita/Pria gagal; ia terinspirasi gaya droid fiksi tetapi **bukan sampel atau tiruan suara karakter mana pun**. Isi jawaban selalu tampil sebagai teks di layar.
- Ingatan Nine disisipkan ke prompt (maksimal beberapa puluh fakta), bukan fine-tuning; model Gemma tidak berubah.
- Campur bahasa ditekan dengan aturan bahasa yang ketat, suhu sampling lebih rendah, dan pengecekan jawaban: jawaban Gemma yang bercampur diminta ditulis ulang. Pengecekan ini heuristik, jadi sesekali masih bisa lolos.
- Android tidak mengizinkan aplikasi biasa menyalakan atau mematikan Wi-Fi/Bluetooth/hotspot/mode pesawat. Nine melakukannya lewat **layanan aksesibilitas** (Pengaturan Nine → "Izinkan kontrol Nine"): ia membuka Panel Cepat lalu mengetuk ubinnya, jadi hasilnya bergantung pada susunan One UI di HP-mu dan belum diuji di semua perangkat. Tanpa layanan itu (dan di **Nine.apk** yang tidak memilikinya), Nine hanya membuka panel pengaturannya. Pada Android 13+ untuk aplikasi di luar Play Store, aktifkan dulu **Info aplikasi → ⋮ → Izinkan setelan terbatas**.
- Memutar lagu pilihan di YouTube Music juga memakai layanan aksesibilitas (mengetuk hasil lagu pertama), jadi hanya di **Nine-Full.apk**; tanpa itu Nine hanya membuka hasil pencarian. Jeda/lanjut/berikutnya/sebelumnya/volume memakai tombol media sistem dan bekerja di pemutar apa pun.
- Terima/tolak telepon butuh izin **Telepon** dan hanya lewat perintah suara: Nine tidak otomatis menyebut siapa yang menelepon.
- Nyanyian adalah melodi bersuara droid, tanpa lirik. Reaksi musik memakai mikrofon hanya saat layar Nine terbuka dan Nine sedang diam; layar kunci dan aplikasi lain tidak diawasi.
- Pengingat lebih dari 24 jam dibuka di Kalender, karena alarm Android hanya berlaku 24 jam ke depan. Alarm dibuat di aplikasi Jam HP: Nine tidak bisa mematikan atau menghapusnya.
- Tanda tangan APK: bila rahasia CI `NINE_KEYSTORE_PASSWORD` diisi, build memakai kunci privat `app/nine-release.p12` (terenkripsi; sandinya hanya ada di rahasia CI). Bila belum diisi, build memakai kunci publik lama `app/nine-sideload.jks` (sandi ada di repo). Catatan rilis menyebut kunci mana yang dipakai. Berpindah kunci memaksa **hapus lalu pasang ulang** sekali (data dan model Gemma ikut hilang), karena Android menolak pembaruan dengan tanda tangan berbeda. Jangan pakai untuk Play Store.
- Penghematan baterai/panas di atas (gerbang suara, laju gambar adaptif, widget diam, jeda saat panas) **belum diukur di HP**: logikanya diuji di unit test dan dikompilasi di CI, tetapi akurasi kata pemanggil dengan gerbang suara di berbagai ruangan dan perilaku saat HP benar-benar panas belum diuji di perangkat. Bila "hey nine" terasa kurang peka, laporkan; ambangnya mudah dilonggarkan.
- Wake word memakai model bahasa Inggris, jadi frasanya harus kata-kata Inggris. Layar Nine hanya bisa muncul dari latar belakang bila Nine dijadikan asisten digital default atau diberi izin "tampil di atas aplikasi lain"; selain itu muncul notifikasi untuk diketuk.
- **AI online (Gemini) belum diuji di HP dan belum dicoba dengan kunci asli.** Logikanya (cadangan antarmodel, jeda kuota, pembacaan aliran, pembatalan, tag ekspresi, tag ingatan, suara Gemini) diuji unit test, termasuk lawan server HTTP lokal yang meniru format Gemini; tetapi respons nyata Google (terutama pesan galat kuota dan angka token) belum terlihat langsung, jadi tombol "Tes koneksi" adalah cara memastikannya di HP kamu. Batas harian yang Google laporkan di galat kuota belum diverifikasi.
- Tingkat gratis Gemini punya batas harian/menit yang berubah-ubah dan **bisa memakai percakapanmu untuk meningkatkan produk Google**; jangan menyebut data sensitif ke Mode internet. Pertanyaan umum yang bukan perintah dikirim **bersama ringkasan konteks** (ingatan Nine, tugas dan acara hari ini, lokasi bila diizinkan, nama panggilan).
- AI online gratis tidak punya akses web; untuk info terkini Nine membukakan Google. Kualitas jawaban bergantung model yang sedang tersedia dan bisa berubah tanpa pemberitahuan.
- Gemma/Gemini belum memutuskan tindakan HP lewat function calling; mereka menulis perintah yang lalu dijalankan parser. Tindakan berisiko (telepon, WhatsApp, membuka situs, merekam, menghapus) yang diusulkan AI selalu dikonfirmasi dulu.
- Widget hanya berganti ekspresi saat proses Nine hidup atau setiap ±30 menit, jadi reaksi "dicas" di widget bisa sedikit tertunda.
- Font logo & wordmark: Inter Display Light (SIL Open Font License).

## Struktur kode

| Folder | Isi |
|---|---|
| `nlu/` | Parser perintah ID+EN (Kotlin murni, diuji di `src/test`) |
| `coach/` | Mentor bahasa Inggris offline (Kotlin murni, diuji di `src/test`): `GrammarCheck` (aturan koreksi), `WordChoice` (kata yang lebih bagus), `CoachIntent` (deteksi perintah), `CoachSession` (alur latihan), `CoachFeedback` (susunan balasan) |
| `llm/` | `ModelManager` (unduh/impor Gemma E2B), `LlmBrain` (Gemma via LiteRT-LM), `TextClean`, `LangGuard` (deteksi bahasa campur). Gemini (Kotlin murni kecuali `GeminiBrain`, diuji di `src/test` termasuk dengan server HTTP lokal): `BrainEngine` (cadangan antarmodel, jeda kuota), `Backends` (klien Gemini, SSE), `Wire`, `Failures`, `Expression` (tag ekspresi dan ingatan), `AiHistory`, `MiniJson`, `GeminiBrain` (sambungan ke aplikasi) |
| `core/` | `AssistantController` (alur dengar → pahami → jalankan/tanya Gemma → ucapkan), `Persona`, `Prefs`, `MemoryStore` + `FactExtractor` (ingatan jangka panjang) |
| `skills/` | Eksekusi perintah perangkat, kontak, catatan |
| `speech/` | Pengenal suara on-device; keluaran suara: `SpeechOutput` (antrean + cadangan), `GeminiTts` (klien Gemini TTS), `Pcm` (pengurai WAV/PCM), `VoicePicker` (pilihan suara pria/wanita), `SpeechText` (pemotong teks, gaya dari ekspresi), `SystemTts` (suara bawaan HP), `DroidVoice` + `DroidSinger` (bunyi droid, nyanyian) |
| `audio/` | Deteksi nada (YIN) + tuner gitar, analisis ketukan musik, daftar lagu |
| `a11y/` | Layanan aksesibilitas: ubin pengaturan cepat, YouTube Music, tutup semua aplikasi. Kode aslinya ada di `src/full/`; `src/lite/` berisi stub kosong dengan API sama (`AVAILABLE = false`) |
| `call/` | Terima/tolak telepon masuk |
| `ui/` | `NineOrbView`, `EdgeGlowView`, `SphereView` + `NineBuddyView` + `LimbMotion` (Nine dengan tangan dan kaki bola, gerak pegas), `FinBarsView` (grafik batang), daftar pesan |
| `widget/` | Widget orb (kecil dan 4x4) dan "Hari ini"; frame wajah dibuat `tools/face_sphere.py` |
| `service/` | Peran asisten digital default |

Build lokal: Android Studio (AGP 8.13, Kotlin 2.3, JDK 17) → `./gradlew assembleRelease`.
