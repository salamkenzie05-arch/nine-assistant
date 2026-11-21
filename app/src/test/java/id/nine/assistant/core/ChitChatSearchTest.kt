package id.nine.assistant.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Jawaban AI yang sudah menyebut Google/pencarian tidak perlu pengantar kedua sebelum Google dibuka. */
class ChitChatSearchTest {

    @Test
    fun answersThatAlreadyMentionSearchingNeedNoSecondNotice() {
        assertTrue(ChitChat.mentionsSearch("I can't check current information directly, Sir, but I have opened a Google search for the current cinema listings."))
        assertTrue(ChitChat.mentionsSearch("Saya tidak bisa memeriksa informasi terkini; saya bukakan pencarian Google ya."))
        assertTrue(ChitChat.mentionsSearch("Let me look it up for you."))
        assertTrue(ChitChat.mentionsSearch("Silakan cari di internet."))
    }

    @Test
    fun answersThatDoNotMentionItStillGetTheNotice() {
        assertFalse(ChitChat.mentionsSearch("I'm not certain about that, Sir."))
        assertFalse(ChitChat.mentionsSearch("Saya tidak tahu jawabannya."))
        // satu kata "search" di dalam kata lain tidak dihitung
        assertFalse(ChitChat.mentionsSearch("Researchers disagree."))
    }

    @Test
    fun theSecondNoticeStaysTheSameShortSentence() {
        assertTrue(ChitChat.searchNotice(AppLang.EN, "").contains("Google"))
        assertTrue(ChitChat.searchNotice(AppLang.ID, "").contains("Google"))
    }
}
