package id.nine.assistant.llm

import id.nine.assistant.core.AppLang
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LangGuardTest {

    @Test
    fun flagsMixedIndonesianReply() {
        val mixed = "Tentu Tuan, saya sudah mengecek jadwal Anda. You have a meeting at three and the weather is fine today."
        assertTrue(LangGuard.needsRewrite(mixed, AppLang.ID))
    }

    @Test
    fun flagsMixedEnglishReply() {
        val mixed = "Certainly, sir. Hari ini Anda tidak punya acara dan cuacanya sangat baik untuk berjalan kaki."
        assertTrue(LangGuard.needsRewrite(mixed, AppLang.EN))
    }

    @Test
    fun acceptsCleanReplies() {
        assertFalse(LangGuard.needsRewrite("Tentu, Tuan. Hari ini Anda punya dua acara dan satu tugas yang belum selesai.", AppLang.ID))
        assertFalse(LangGuard.needsRewrite("Certainly, sir. You have two events today and one task that is still open.", AppLang.EN))
    }

    @Test
    fun leavesQuotesTitlesAndShortReplies() {
        assertFalse(LangGuard.needsRewrite("Memutar \"Is This the Way to Amarillo and the rest of it\" untuk Anda, Tuan.", AppLang.ID))
        assertFalse(LangGuard.needsRewrite("Baik, Tuan.", AppLang.ID))
    }

    @Test
    fun allowsOtherLanguageWhenAskedToTranslate() {
        val reply = "Dalam bahasa Inggris: the cat is sleeping on the sofa and you are watching it."
        assertFalse(LangGuard.needsRewrite(reply, AppLang.ID, question = "terjemahkan kucing sedang tidur ke bahasa Inggris"))
    }
}
