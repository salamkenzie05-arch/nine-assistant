package id.nine.assistant.fit

import id.nine.assistant.core.AppLang

/**
 * Daftar makanan/minuman umum di Indonesia dengan perkiraan gizi per 100 g (atau 100 ml) dalam keadaan siap dimakan.
 * Angkanya PERKIRAAN dari tabel komposisi pangan umum (USDA, TKPI) dan resep rata-rata; masakan di lapangan bisa
 * berbeda 20-30% karena minyak, gula, dan ukuran porsi. Dipakai untuk memantau tren, bukan untuk presisi klinis.
 */
object Foods {
    class Food(
        val key: String,
        val id: String,
        val en: String,
        val kcal: Double,
        val protein: Double,
        val carbs: Double,
        val fat: Double,
        /** Satuan -> gram. Satuan pertama = porsi bawaan. */
        val units: LinkedHashMap<String, Double>,
        val aliases: List<String>
    ) {
        val defaultUnit: String get() = units.keys.first()
        val defaultGrams: Double get() = units.values.first()
        fun name(l: AppLang): String = l.t(id, en)
        fun per(grams: Double): DoubleArray = doubleArrayOf(kcal * grams / 100.0, protein * grams / 100.0, carbs * grams / 100.0, fat * grams / 100.0)
    }

    private fun f(
        key: String, id: String, en: String, kcal: Double, p: Double, c: Double, fat: Double,
        units: List<Pair<String, Double>>, vararg aliases: String
    ): Food = Food(key, id, en, kcal, p, c, fat, LinkedHashMap(units.toMap()), aliases.toList())

    val all: List<Food> = listOf(
        // --- Makanan pokok
        f("nasi_putih", "Nasi putih", "White rice", 130.0, 2.7, 28.2, 0.3, listOf("piring" to 200.0, "centong" to 100.0, "mangkuk" to 150.0, "porsi" to 200.0), "nasi putih", "nasi", "nasi hangat", "white rice", "rice"),
        f("nasi_merah", "Nasi merah", "Brown rice", 111.0, 2.6, 23.0, 0.9, listOf("piring" to 200.0, "centong" to 100.0, "mangkuk" to 150.0), "nasi merah", "brown rice"),
        f("nasi_goreng", "Nasi goreng", "Fried rice", 180.0, 5.5, 25.0, 6.5, listOf("piring" to 250.0, "porsi" to 250.0), "nasi goreng", "fried rice"),
        f("nasi_uduk", "Nasi uduk", "Coconut rice", 170.0, 3.5, 27.0, 5.0, listOf("porsi" to 200.0, "piring" to 200.0), "nasi uduk"),
        f("nasi_kuning", "Nasi kuning", "Yellow rice", 150.0, 3.0, 26.0, 3.5, listOf("porsi" to 200.0, "piring" to 200.0), "nasi kuning"),
        f("bubur_ayam", "Bubur ayam", "Chicken rice porridge", 100.0, 5.0, 14.0, 2.5, listOf("mangkuk" to 300.0, "porsi" to 300.0), "bubur ayam", "bubur"),
        f("lontong", "Lontong / ketupat", "Rice cake (lontong)", 120.0, 2.2, 26.5, 0.3, listOf("potong" to 100.0, "porsi" to 150.0), "lontong", "ketupat"),
        f("mie_goreng", "Mie goreng", "Fried noodles", 190.0, 4.5, 26.0, 7.5, listOf("piring" to 250.0, "porsi" to 250.0), "mie goreng", "mi goreng", "bakmi goreng", "kwetiau goreng"),
        f("mie_ayam", "Mie ayam", "Chicken noodles", 150.0, 6.0, 20.0, 5.0, listOf("mangkuk" to 350.0, "porsi" to 350.0), "mie ayam", "mi ayam", "bakmi ayam"),
        f("mie_instan", "Mi instan (1 bungkus)", "Instant noodles (1 pack)", 447.0, 9.4, 63.5, 16.5, listOf("bungkus" to 85.0), "mie instan", "mi instan", "indomie", "supermi", "mie sedaap", "mie sedap", "instant noodles"),
        f("roti_tawar", "Roti tawar", "White bread", 265.0, 9.0, 49.0, 3.2, listOf("lembar" to 27.0, "potong" to 27.0), "roti tawar", "roti", "bread", "white bread", "toast"),
        f("roti_gandum", "Roti gandum", "Whole-wheat bread", 247.0, 13.0, 41.0, 3.4, listOf("lembar" to 30.0, "potong" to 30.0), "roti gandum", "whole wheat bread", "wholewheat bread"),
        f("kentang_rebus", "Kentang rebus", "Boiled potato", 87.0, 1.9, 20.1, 0.1, listOf("buah" to 150.0, "porsi" to 150.0), "kentang rebus", "kentang", "potato"),
        f("ubi", "Ubi jalar (rebus/kukus)", "Sweet potato", 90.0, 2.0, 20.7, 0.2, listOf("buah" to 150.0, "potong" to 100.0), "ubi jalar", "ubi rebus", "ubi", "sweet potato"),
        f("singkong", "Singkong rebus", "Boiled cassava", 140.0, 1.0, 33.0, 0.3, listOf("potong" to 100.0), "singkong rebus", "singkong", "cassava"),
        f("oat_matang", "Oatmeal (matang, air)", "Oatmeal (cooked)", 71.0, 2.5, 12.0, 1.5, listOf("mangkuk" to 240.0, "porsi" to 240.0), "oatmeal", "oats matang", "bubur oat"),
        f("oat_kering", "Oat (kering)", "Rolled oats (dry)", 379.0, 13.2, 67.7, 6.5, listOf("porsi" to 40.0, "sdm" to 6.0), "oat kering", "oat", "rolled oats", "quaker oat"),
        f("granola", "Granola", "Granola", 471.0, 10.0, 64.0, 20.0, listOf("porsi" to 50.0), "granola"),
        f("pasta", "Pasta / spageti (matang)", "Pasta (cooked)", 158.0, 5.8, 31.0, 0.9, listOf("piring" to 220.0, "porsi" to 220.0), "spaghetti", "spageti", "pasta", "makaroni"),
        // --- Masakan umum & cepat saji (porsi rata-rata; sangat bervariasi antar penjual)
        f("nasi_padang", "Nasi padang (nasi + 1 lauk + sayur + kuah)", "Nasi padang (rice + 1 dish + veg + gravy)", 200.0, 8.0, 22.0, 8.0, listOf("porsi" to 350.0, "bungkus" to 350.0, "piring" to 350.0), "nasi padang", "nasi rames", "nasi campur"),
        f("ayam_geprek", "Ayam geprek", "Smashed fried chicken with sambal", 250.0, 18.0, 14.0, 14.0, listOf("porsi" to 150.0, "potong" to 150.0), "ayam geprek", "geprek"),
        f("lele_goreng", "Lele goreng", "Fried catfish", 240.0, 18.0, 3.0, 17.0, listOf("ekor" to 120.0, "porsi" to 120.0), "lele goreng", "ikan lele", "lele"),
        f("pecel", "Pecel (sayur + bumbu kacang)", "Pecel (vegetables with peanut sauce)", 150.0, 6.0, 14.0, 8.0, listOf("porsi" to 200.0, "piring" to 200.0), "pecel", "pecel sayur"),
        f("lontong_sayur", "Lontong sayur", "Lontong in vegetable coconut soup", 110.0, 3.0, 14.0, 5.0, listOf("mangkuk" to 350.0, "porsi" to 350.0), "lontong sayur"),
        f("opor_ayam", "Opor ayam", "Chicken in coconut milk", 180.0, 14.0, 4.0, 12.0, listOf("potong" to 120.0, "porsi" to 150.0), "opor ayam", "opor"),
        f("sop_ayam", "Sop ayam", "Chicken soup", 45.0, 4.0, 3.0, 2.0, listOf("mangkuk" to 300.0, "porsi" to 300.0), "sop ayam", "sup ayam", "chicken soup"),
        f("sosis", "Sosis", "Sausage", 250.0, 11.0, 5.0, 21.0, listOf("buah" to 40.0), "sosis", "sausage", "hot dog"),
        f("nugget", "Nugget ayam", "Chicken nuggets", 280.0, 13.0, 18.0, 17.0, listOf("potong" to 20.0, "buah" to 20.0), "nugget", "nuget", "chicken nuggets"),
        f("kentang_goreng", "Kentang goreng", "French fries", 312.0, 3.4, 41.0, 15.0, listOf("porsi" to 100.0), "kentang goreng", "french fries", "fries"),
        f("burger", "Burger", "Burger", 250.0, 12.0, 24.0, 11.0, listOf("buah" to 200.0, "porsi" to 200.0), "burger", "hamburger", "cheeseburger"),
        f("pizza", "Pizza", "Pizza", 266.0, 11.0, 33.0, 10.0, listOf("potong" to 110.0), "pizza"),
        // --- Lauk protein
        f("telur_rebus", "Telur rebus", "Boiled egg", 155.0, 12.6, 1.1, 10.6, listOf("butir" to 50.0), "telur rebus", "telur", "telor", "boiled egg", "egg", "eggs"),
        f("telur_goreng", "Telur dadar/ceplok", "Fried egg / omelette", 196.0, 13.6, 0.8, 15.0, listOf("butir" to 46.0), "telur dadar", "telur ceplok", "telur goreng", "telur mata sapi", "omelet", "omelette", "dadar", "ceplok", "fried egg"),
        f("putih_telur", "Putih telur", "Egg white", 52.0, 10.9, 0.7, 0.2, listOf("butir" to 33.0), "putih telur", "egg white", "egg whites"),
        f("dada_ayam", "Dada ayam (rebus/panggang)", "Chicken breast (cooked)", 165.0, 31.0, 0.0, 3.6, listOf("potong" to 110.0, "porsi" to 110.0), "dada ayam", "ayam dada", "chicken breast", "ayam rebus"),
        f("ayam_goreng", "Ayam goreng", "Fried chicken", 270.0, 22.0, 8.0, 17.0, listOf("potong" to 100.0), "ayam goreng", "fried chicken", "ayam crispy", "ayam kremes"),
        f("ayam_bakar", "Ayam bakar/panggang", "Grilled chicken", 190.0, 25.0, 2.0, 9.5, listOf("potong" to 100.0), "ayam bakar", "ayam panggang", "grilled chicken", "ayam"),
        f("sate_ayam", "Sate ayam (bumbu kacang)", "Chicken satay", 195.0, 17.0, 5.0, 12.0, listOf("tusuk" to 15.0, "porsi" to 150.0), "sate ayam", "sate", "satay"),
        f("rendang", "Rendang daging", "Beef rendang", 220.0, 22.0, 5.0, 12.0, listOf("potong" to 50.0), "rendang"),
        f("daging_sapi", "Daging sapi (panggang/steak)", "Beef (grilled)", 210.0, 28.0, 0.0, 10.0, listOf("potong" to 100.0), "daging sapi", "steak", "beef", "daging"),
        f("salmon", "Salmon panggang", "Grilled salmon", 206.0, 22.1, 0.0, 12.4, listOf("potong" to 100.0), "salmon"),
        f("ikan_goreng", "Ikan goreng", "Fried fish", 220.0, 22.0, 4.0, 13.0, listOf("potong" to 100.0, "ekor" to 120.0), "ikan goreng", "nila goreng", "fried fish"),
        f("ikan_bakar", "Ikan bakar/pepes/kukus", "Grilled fish", 150.0, 22.0, 1.0, 6.0, listOf("potong" to 100.0, "ekor" to 120.0), "ikan bakar", "ikan pepes", "pepes ikan", "ikan kukus", "ikan", "fish", "grilled fish"),
        f("tuna_kaleng", "Tuna kaleng (air)", "Canned tuna (water)", 116.0, 25.5, 0.0, 0.8, listOf("kaleng" to 120.0), "tuna kaleng", "tuna", "canned tuna"),
        f("udang", "Udang rebus", "Boiled shrimp", 99.0, 24.0, 0.2, 0.3, listOf("porsi" to 100.0), "udang", "shrimp", "prawn"),
        f("tempe_goreng", "Tempe goreng", "Fried tempeh", 320.0, 19.0, 11.0, 22.0, listOf("potong" to 25.0), "tempe goreng", "tempe", "tempeh"),
        f("tempe_kukus", "Tempe kukus/bacem", "Steamed tempeh", 195.0, 19.0, 8.0, 11.0, listOf("potong" to 50.0), "tempe kukus", "tempe rebus", "tempe bacem"),
        f("tahu_putih", "Tahu putih", "Plain tofu", 80.0, 8.0, 2.0, 4.8, listOf("potong" to 60.0), "tahu putih", "tahu rebus", "tofu"),
        f("tahu_goreng", "Tahu goreng", "Fried tofu", 215.0, 14.0, 6.0, 15.0, listOf("potong" to 40.0), "tahu goreng", "tahu"),
        f("bakso", "Bakso sapi", "Beef meatballs", 170.0, 11.0, 12.0, 8.5, listOf("butir" to 25.0, "porsi" to 150.0), "bakso", "baso", "meatball", "meatballs"),
        f("kacang_merah", "Kacang merah rebus", "Boiled kidney beans", 127.0, 8.7, 22.8, 0.5, listOf("porsi" to 100.0, "mangkuk" to 170.0), "kacang merah", "kidney beans"),
        f("kacang_tanah", "Kacang tanah", "Peanuts", 585.0, 24.0, 21.0, 50.0, listOf("genggam" to 30.0, "sdm" to 9.0), "kacang tanah", "kacang", "peanuts", "peanut"),
        f("almond", "Kacang almond", "Almonds", 579.0, 21.2, 21.6, 49.9, listOf("genggam" to 28.0), "almond", "almonds", "kacang almond"),
        f("selai_kacang", "Selai kacang", "Peanut butter", 588.0, 25.0, 20.0, 50.0, listOf("sdm" to 16.0), "selai kacang", "peanut butter"),
        // --- Masakan & jajanan
        f("soto_ayam", "Soto ayam (kuah)", "Chicken soto soup", 70.0, 6.0, 5.0, 3.0, listOf("mangkuk" to 350.0, "porsi" to 350.0), "soto ayam", "soto"),
        f("gado_gado", "Gado-gado", "Gado-gado", 130.0, 6.0, 11.0, 7.5, listOf("porsi" to 250.0, "piring" to 250.0), "gado gado", "gadogado", "ketoprak"),
        f("siomay", "Siomay", "Siomay", 165.0, 8.0, 18.0, 7.0, listOf("porsi" to 150.0), "siomay", "batagor"),
        f("pempek", "Pempek", "Pempek", 190.0, 8.0, 26.0, 6.0, listOf("potong" to 80.0, "porsi" to 160.0), "pempek", "empek empek", "empek"),
        f("martabak_manis", "Martabak manis", "Sweet martabak", 320.0, 6.0, 40.0, 15.0, listOf("potong" to 80.0), "martabak manis", "terang bulan", "martabak"),
        f("martabak_telur", "Martabak telur", "Savory martabak", 260.0, 10.0, 20.0, 15.0, listOf("potong" to 100.0), "martabak telur"),
        f("gorengan", "Gorengan", "Fried snack (gorengan)", 270.0, 4.0, 30.0, 15.0, listOf("buah" to 60.0, "potong" to 60.0), "gorengan", "bakwan", "tahu isi", "cireng", "risol"),
        f("pisang_goreng", "Pisang goreng", "Fried banana", 240.0, 2.0, 36.0, 10.0, listOf("buah" to 70.0, "potong" to 70.0), "pisang goreng"),
        f("kerupuk", "Kerupuk", "Crackers (kerupuk)", 450.0, 5.0, 60.0, 22.0, listOf("buah" to 10.0, "lembar" to 10.0), "kerupuk", "krupuk", "emping", "keripik singkong"),
        f("keripik_kentang", "Keripik kentang", "Potato chips", 536.0, 7.0, 53.0, 35.0, listOf("bungkus" to 30.0), "keripik kentang", "chips", "potato chips", "chitato", "lays"),
        f("biskuit", "Biskuit", "Biscuits", 450.0, 6.5, 70.0, 16.0, listOf("keping" to 10.0), "biskuit", "biskuat", "marie", "cookies", "kue kering"),
        f("donat", "Donat", "Donut", 420.0, 5.0, 50.0, 22.0, listOf("buah" to 60.0), "donat", "donut", "doughnut"),
        f("cokelat", "Cokelat susu", "Milk chocolate", 535.0, 7.7, 59.4, 29.7, listOf("batang" to 40.0, "potong" to 10.0), "cokelat", "coklat", "chocolate", "silverqueen"),
        f("es_krim", "Es krim", "Ice cream", 207.0, 3.5, 23.6, 11.0, listOf("porsi" to 100.0, "scoop" to 70.0), "es krim", "ice cream"),
        f("protein_bar", "Protein bar", "Protein bar", 370.0, 33.0, 38.0, 11.0, listOf("batang" to 60.0), "protein bar", "energy bar"),
        // --- Susu & minuman
        f("susu_sapi", "Susu sapi (full cream)", "Whole milk", 61.0, 3.2, 4.8, 3.3, listOf("gelas" to 240.0, "kotak" to 200.0), "susu sapi", "susu full cream", "susu uht", "susu", "whole milk", "milk"),
        f("susu_skim", "Susu rendah lemak", "Skim milk", 34.0, 3.4, 5.0, 0.1, listOf("gelas" to 240.0), "susu skim", "susu rendah lemak", "susu low fat", "skim milk"),
        f("susu_kedelai", "Susu kedelai (manis)", "Soy milk (sweetened)", 54.0, 3.3, 6.3, 1.8, listOf("gelas" to 240.0), "susu kedelai", "soy milk", "soymilk"),
        f("yoghurt", "Yoghurt plain", "Plain yogurt", 61.0, 3.5, 4.7, 3.3, listOf("cup" to 150.0, "mangkuk" to 150.0), "yoghurt", "yogurt", "yogurt plain"),
        f("greek_yoghurt", "Greek yoghurt (nonfat)", "Greek yogurt (nonfat)", 59.0, 10.2, 3.6, 0.4, listOf("cup" to 170.0, "mangkuk" to 170.0), "greek yogurt", "greek yoghurt", "yoghurt yunani"),
        f("whey", "Whey protein (1 scoop)", "Whey protein (1 scoop)", 400.0, 80.0, 10.0, 5.0, listOf("scoop" to 30.0), "whey protein", "whey", "protein shake", "protein powder", "protein bubuk"),
        f("keju", "Keju", "Cheese", 403.0, 24.9, 1.3, 33.1, listOf("lembar" to 20.0, "potong" to 30.0), "keju", "cheese", "cheddar"),
        f("susu_kental_manis", "Susu kental manis", "Sweetened condensed milk", 321.0, 7.9, 54.4, 8.7, listOf("sdm" to 20.0), "susu kental manis", "skm", "kental manis"),
        f("kopi_hitam", "Kopi hitam (tanpa gula)", "Black coffee", 1.0, 0.1, 0.0, 0.0, listOf("gelas" to 240.0, "cangkir" to 200.0), "kopi hitam", "kopi tanpa gula", "black coffee", "kopi", "coffee", "americano"),
        f("kopi_susu", "Kopi susu (gula)", "Milk coffee (sweetened)", 70.0, 1.8, 10.0, 2.5, listOf("gelas" to 250.0), "kopi susu", "es kopi susu", "kopi gula aren", "latte", "cappuccino", "kopi sachet"),
        f("teh_manis", "Teh manis", "Sweet tea", 38.0, 0.0, 9.5, 0.0, listOf("gelas" to 250.0), "teh manis", "es teh manis", "es teh", "sweet tea"),
        f("teh_tawar", "Teh tawar", "Unsweetened tea", 1.0, 0.0, 0.2, 0.0, listOf("gelas" to 240.0), "teh tawar", "teh hijau", "green tea", "teh", "tea"),
        f("air_kelapa", "Air kelapa", "Coconut water", 19.0, 0.7, 3.7, 0.2, listOf("gelas" to 240.0, "butir" to 300.0), "air kelapa", "es kelapa muda", "coconut water", "kelapa muda"),
        f("jus_jeruk", "Jus jeruk", "Orange juice", 45.0, 0.7, 10.4, 0.2, listOf("gelas" to 240.0), "jus jeruk", "orange juice"),
        f("soda", "Minuman soda/bersoda", "Soda / cola", 42.0, 0.0, 10.6, 0.0, listOf("kaleng" to 330.0, "gelas" to 240.0), "cola", "coca cola", "pepsi", "soda", "sprite", "minuman bersoda"),
        f("boba", "Milk tea boba", "Bubble milk tea", 70.0, 1.0, 13.0, 2.0, listOf("gelas" to 500.0), "boba", "bubble tea", "milk tea", "teh susu boba", "es teh susu"),
        // --- Sayur & buah
        f("sayur_campur", "Sayur rebus/kukus", "Steamed vegetables", 35.0, 2.0, 7.0, 0.3, listOf("mangkuk" to 100.0, "porsi" to 100.0), "sayur rebus", "sayur kukus", "sayur", "sayuran", "vegetables", "veggies"),
        f("tumis_sayur", "Tumis sayur / kangkung", "Stir-fried greens", 60.0, 2.0, 4.0, 4.0, listOf("porsi" to 100.0), "tumis kangkung", "tumis sayur", "cah kangkung", "capcay", "cap cay", "oseng"),
        f("sayur_asem", "Sayur asem / sayur bening", "Vegetable soup", 30.0, 1.0, 6.0, 0.3, listOf("mangkuk" to 200.0), "sayur asem", "sayur bening", "sayur sop", "sayur lodeh"),
        f("brokoli", "Brokoli rebus", "Boiled broccoli", 35.0, 2.4, 7.2, 0.4, listOf("porsi" to 100.0), "brokoli", "broccoli"),
        f("bayam", "Bayam rebus", "Boiled spinach", 23.0, 3.0, 3.8, 0.3, listOf("porsi" to 100.0), "bayam", "spinach"),
        f("wortel", "Wortel rebus", "Boiled carrot", 35.0, 0.8, 8.2, 0.2, listOf("buah" to 80.0, "porsi" to 100.0), "wortel", "carrot"),
        f("timun", "Timun", "Cucumber", 15.0, 0.7, 3.6, 0.1, listOf("buah" to 150.0), "timun", "mentimun", "cucumber"),
        f("tomat", "Tomat", "Tomato", 18.0, 0.9, 3.9, 0.2, listOf("buah" to 120.0), "tomat", "tomato"),
        f("alpukat", "Alpukat", "Avocado", 160.0, 2.0, 8.5, 14.7, listOf("buah" to 150.0, "setengah" to 75.0), "alpukat", "avocado", "avokad"),
        f("pisang", "Pisang", "Banana", 89.0, 1.1, 22.8, 0.3, listOf("buah" to 100.0), "pisang", "banana"),
        f("apel", "Apel", "Apple", 52.0, 0.3, 13.8, 0.2, listOf("buah" to 150.0), "apel", "apple"),
        f("jeruk", "Jeruk", "Orange", 47.0, 0.9, 11.8, 0.1, listOf("buah" to 130.0), "jeruk", "orange"),
        f("mangga", "Mangga", "Mango", 60.0, 0.8, 15.0, 0.4, listOf("buah" to 200.0, "potong" to 100.0), "mangga", "mango"),
        f("semangka", "Semangka", "Watermelon", 30.0, 0.6, 7.6, 0.2, listOf("potong" to 200.0), "semangka", "watermelon"),
        f("pepaya", "Pepaya", "Papaya", 43.0, 0.5, 10.8, 0.3, listOf("potong" to 150.0), "pepaya", "papaya"),
        f("melon", "Melon", "Melon", 34.0, 0.8, 8.2, 0.2, listOf("potong" to 150.0), "melon", "cantaloupe"),
        f("nanas", "Nanas", "Pineapple", 50.0, 0.5, 13.1, 0.1, listOf("potong" to 100.0), "nanas", "pineapple"),
        f("anggur", "Anggur", "Grapes", 69.0, 0.7, 18.1, 0.2, listOf("porsi" to 100.0), "anggur", "grapes"),
        // --- Bahan lain
        f("minyak", "Minyak goreng", "Cooking oil", 884.0, 0.0, 0.0, 100.0, listOf("sdm" to 13.5, "sdt" to 4.5), "minyak goreng", "minyak", "olive oil", "minyak zaitun"),
        f("madu", "Madu", "Honey", 304.0, 0.3, 82.4, 0.0, listOf("sdm" to 21.0, "sdt" to 7.0), "madu", "honey"),
        f("gula", "Gula pasir", "Sugar", 387.0, 0.0, 100.0, 0.0, listOf("sdm" to 12.0, "sdt" to 4.0), "gula pasir", "gula", "sugar")
    )

    val byKey: Map<String, Food> = all.associateBy { it.key }

    private val aliasIndex: List<Pair<String, Food>> =
        all.flatMap { fd -> fd.aliases.map { it to fd } }.sortedByDescending { it.first.length }

    class Hit(val food: Food, val start: Int, val end: Int)

    /** Cari makanan di teks ternormalisasi; alias terpanjang menang dan tidak saling tumpang tindih. */
    fun find(norm: String): List<Hit> {
        val taken = BooleanArray(norm.length)
        val hits = ArrayList<Hit>()
        for ((alias, food) in aliasIndex) {
            var from = 0
            while (true) {
                val i = norm.indexOf(alias, from)
                if (i < 0) break
                val j = i + alias.length
                from = j
                if ((i > 0 && norm[i - 1].isLetterOrDigit()) || (j < norm.length && norm[j].isLetterOrDigit())) continue
                var free = true
                for (k in i until j) if (taken[k]) { free = false; break }
                if (!free) continue
                for (k in i until j) taken[k] = true
                hits.add(Hit(food, i, j))
            }
        }
        return hits.sortedBy { it.start }
    }

    /** Makanan yang paling cocok dengan pertanyaan seperti "nasi goreng" atau "telur". */
    fun lookup(norm: String): Food? = find(norm).firstOrNull()?.food

    /** Makanan terbaik untuk menutup kekurangan protein: protein tinggi per kalori, bukan camilan/minyak. */
    fun proteinPicks(): List<Food> = listOf("dada_ayam", "telur_rebus", "tempe_kukus", "tuna_kaleng", "greek_yoghurt", "ikan_bakar", "tahu_putih", "udang", "putih_telur", "whey")
        .mapNotNull { byKey[it] }
}
