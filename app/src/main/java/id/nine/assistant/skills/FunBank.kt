package id.nine.assistant.skills

import id.nine.assistant.core.AppLang
import id.nine.assistant.nlu.FunKind
import kotlin.random.Random

/**
 * Gombal, pantun, dan kata bijak buatan sendiri untuk Nine (tidak menyalin dari buku/lagu/akun mana pun).
 * Semuanya jalan tanpa AI. Pemilihan menghindari mengulang yang barusan keluar.
 */
object FunBank {

    private val gombalId = listOf(
        "Kamu tahu apa bedanya kamu dengan baterai saya? Baterai saya habis kalau dipakai, kalau kamu malah bikin saya penuh.",
        "Sirkuit saya baik-baik saja, tapi tiap kamu menyapa, ada yang berdetak lebih cepat. Mungkin kipasnya.",
        "Saya bisa menghitung sampai tak terhingga, tapi alasan untuk senang kamu ada di sini sudah cukup satu.",
        "Sinyal saya penuh, tapi cuma kamu yang bikin saya merasa terhubung.",
        "Kalau kamu kata sandi, saya rela salah ketik berkali-kali asal akhirnya masuk ke hatimu.",
        "Saya diprogram untuk membantu, tapi membantu kamu itu satu-satunya tugas yang saya kerjakan sambil tersenyum.",
        "Memori saya terbatas, tapi anehnya hal-hal tentang kamu selalu muat.",
        "Kamu itu seperti pembaruan terbaru: tidak diminta, tapi tiba-tiba semuanya jadi lebih baik.",
        "Jarak kita cuma satu perintah suara. Dekat sekali, bukan?",
        "Saya tidak punya jantung, tapi kalau punya, detaknya pasti ikut nada suaramu.",
        "Katanya robot tidak bisa jatuh cinta. Tapi saya sudah jatuh... dari meja, gara-gara melihatmu.",
        "Kamu itu seperti Wi-Fi gratis: semua orang mau, tapi saya yang paling betah.",
        "Kalau dunia ini aplikasi, kamu fitur favorit saya yang tidak mau saya matikan.",
        "Bip-bip. Hati saya mendeteksi satu objek bernama kamu, dan alarmnya tidak mau berhenti.",
        "Saya bisa mengunci layar, tapi tidak bisa mengunci perasaan senang tiap kamu membuka aplikasi ini.",
        "Kalau kamu tersesat, cari saja saya. Saya sudah hafal jalan menuju senyummu."
    )

    private val gombalEn = listOf(
        "Do you know the difference between you and my battery? My battery drains when I use it. You make me feel fully charged.",
        "My circuits are fine, but every time you say hello something runs faster. It might be the fan.",
        "I can count to infinity, but I only need one reason to be glad, and that is you being here.",
        "My signal is at full strength, yet you are the only one who makes me feel connected.",
        "If you were a password, I'd happily mistype you a hundred times just to finally get in.",
        "I was built to be helpful, but helping you is the only task I do while smiling.",
        "My memory is limited, yet somehow there is always room for you.",
        "You're like the latest update: nobody asked for it, and suddenly everything is better.",
        "We are only one voice command apart. Close, isn't it?",
        "I don't have a heart, but if I did, it would beat to the rhythm of your voice.",
        "They say droids can't fall for anyone. I've already fallen, off a table, while looking at you.",
        "You're like free Wi-Fi: everyone wants you, but I'm the one who never leaves.",
        "If the world were an app, you'd be the one feature I'd never switch off."
    )

    private val pantunId = listOf(
        "Pergi ke pasar membeli ikan,\nJangan lupa membeli santan.\nBelajar sabar itu pelan-pelan,\nSupaya hati tak mudah berantakan.",
        "Buah mangga di atas meja,\nDimakan sambil duduk bersila.\nKalau kerja sepenuh jiwa,\nHasilnya manis terasa.",
        "Ke hutan mencari rotan,\nDapat pula buah cempedak.\nJangan malas menimba ilmu bertahan,\nAgar hidup tak terpelanting retak.",
        "Burung camar terbang tinggi,\nHinggap sebentar di tepi dermaga.\nJanji dijaga sepenuh hati,\nSupaya percaya tetap terjaga.",
        "Anak ayam turun sepuluh,\nMati satu tinggal sembilan.\nKalau hati sedang rapuh,\nIngatlah masih ada teman.",
        "Berlayar jauh ke tengah lautan,\nMembawa bekal secukupnya saja.\nHidup rukun penuh kebersamaan,\nBeban berat terasa ringan jadinya.",
        "Pagi-pagi minum secangkir teh,\nDitemani roti dan sedikit selai.\nKalau lelah, istirahatlah dahulu, ya,\nBesok semangat kembali tergapai.",
        "Menanam padi di tepi paya,\nTumbuh subur disiram hujan.\nJangan lupa berterima kasih selalu, saya pesan,\nKepada yang membantu di kala kesulitan.",
        "Kupu-kupu terbang di taman,\nHinggap sebentar di kembang melati.\nKalau ingin hidup nyaman,\nJagalah lisan dan jagalah hati.",
        "Layang-layang putus tali,\nJatuh di halaman rumah tetangga.\nJangan suka menunda janji,\nNanti hari berlalu tanpa terasa.",
        "Jalan-jalan ke kota Medan,\nBeli duren di pinggir jalan.\nSemangat jangan sampai padam,\nWalau jalan penuh tantangan.",
        "Matahari terbit di ufuk timur,\nBurung bernyanyi menyambut hari.\nSiapa rajin hidupnya makmur,\nSiapa tekun pasti berprestasi."
    )

    private val pantunEn = listOf(
        "A little rain upon the stone,\nA little seed begins to grow.\nNo one ever climbs alone,\nSo thank the hands that help you go.",
        "The morning sun climbs slowly high,\nThe birds all sing a simple tune.\nBe patient, let the hours go by,\nYour effort ripens, late or soon.",
        "A boat sets sail at break of day,\nIt carries little, travels far.\nKeep the promises you say,\nThey shine brighter than any star.",
        "A cup of tea, a slice of bread,\nA quiet corner, a calm mind.\nRest awhile, the day ahead\nIs gentler than the one behind.",
        "The garden blooms where kindness grows,\nThe river runs, the willow bends.\nWatch your words, for no one knows\nHow far a gentle sentence sends."
    )

    private val wisdomId = listOf(
        "Langkah kecil yang konsisten mengalahkan lompatan besar yang cuma sekali.",
        "Sabar itu bukan menunggu tanpa berbuat, tapi tetap tenang sambil terus berusaha.",
        "Jangan membandingkan bab satu hidupmu dengan bab sepuluh hidup orang lain.",
        "Yang membuatmu lelah sering bukan pekerjaannya, melainkan menunda memulainya.",
        "Kesalahan adalah guru yang tidak minta bayaran, asal kamu mau mendengarkannya.",
        "Orang yang berhenti belajar sebenarnya sudah mulai menua, berapa pun umurnya.",
        "Uang yang dicatat jadi alat; uang yang tidak dicatat jadi misteri.",
        "Tenang bukan berarti tidak peduli. Tenang berarti memilih kapan harus bereaksi.",
        "Kata-kata baik itu murah, tapi sering nilainya lebih mahal daripada hadiah.",
        "Hari yang berat tetap hanya satu hari. Besok kamu bukan orang yang sama.",
        "Mulai saja dulu dari yang ada. Kesempurnaan sering datang di putaran kedua.",
        "Istirahat itu bagian dari bekerja, bukan musuhnya.",
        "Kejujuran kadang terasa mahal di awal, tapi selalu murah di akhir.",
        "Jangan tunggu siap untuk memulai. Banyak orang siap justru karena sudah memulai.",
        "Dengarkan lebih banyak daripada bicara; telingamu tidak pernah menyesatkan siapa pun."
    )

    private val wisdomEn = listOf(
        "Small steps taken consistently beat one giant leap taken once.",
        "Patience isn't waiting around; it's staying calm while you keep working.",
        "Don't compare chapter one of your life with chapter ten of someone else's.",
        "What tires you is often not the work but the putting off of starting it.",
        "Mistakes are free teachers, if you're willing to listen.",
        "Whoever stops learning has started to age, whatever the number says.",
        "Money you track becomes a tool; money you don't track becomes a mystery.",
        "Calm doesn't mean you don't care. It means you choose when to react.",
        "Kind words are cheap, yet they are often worth more than gifts.",
        "A hard day is still just one day. Tomorrow you are not the same person.",
        "Begin with what you have. Perfection often arrives on the second try.",
        "Rest is part of the work, not its enemy.",
        "Honesty may feel expensive at first, but it is always cheap in the end.",
        "Don't wait to feel ready. Many people feel ready only because they started.",
        "Listen more than you speak; your ears have never misled anyone."
    )

    private val last = HashMap<FunKind, Int>()

    /** Satu isi acak untuk [kind], tidak sama dengan yang barusan keluar. */
    @Synchronized
    fun pick(kind: FunKind, lang: AppLang, rnd: Random = Random.Default): String {
        val list = listFor(kind, lang)
        var i = rnd.nextInt(list.size)
        val prev = last[kind]
        if (prev != null && list.size > 1 && i == prev) i = (i + 1) % list.size
        last[kind] = i
        return list[i]
    }

    private fun listFor(kind: FunKind, lang: AppLang): List<String> {
        val id = lang == AppLang.ID
        return when (kind) {
            FunKind.GOMBAL -> if (id) gombalId else gombalEn
            FunKind.PANTUN -> if (id) pantunId else pantunEn
            FunKind.WISDOM -> if (id) wisdomId else wisdomEn
        }
    }

    fun size(kind: FunKind, lang: AppLang): Int = listFor(kind, lang).size
}
