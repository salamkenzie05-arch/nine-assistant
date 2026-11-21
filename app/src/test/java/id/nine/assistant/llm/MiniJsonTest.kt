package id.nine.assistant.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MiniJsonTest {

    @Test
    fun parsesNestedStructures() {
        val v = MiniJson.parse("""{"a":[1,2.5,"x",true,false,null],"b":{"c":"d"}}""")
        assertEquals(1L, MiniJson.get(v, "a", 0))
        assertEquals(2.5, MiniJson.get(v, "a", 1))
        assertEquals("x", MiniJson.str(v, "a", 2))
        assertEquals(true, MiniJson.get(v, "a", 3))
        assertEquals(false, MiniJson.get(v, "a", 4))
        assertNull(MiniJson.get(v, "a", 5))
        assertEquals("d", MiniJson.str(v, "b", "c"))
        assertNull(MiniJson.get(v, "b", "zzz"))
        assertNull(MiniJson.get(v, "a", 99))
        assertNull(MiniJson.get(v, "b", 0))
    }

    @Test
    fun parsesStringEscapes() {
        val v = MiniJson.parse("""["a\"b\\c\/d\n\t\u0041\u00e9\ud83d\ude00"]""")
        assertEquals("a\"b\\c/d\n\tAé\uD83D\uDE00", MiniJson.str(v, 0))
    }

    @Test
    fun parsesNumbers() {
        assertEquals(-12L, MiniJson.parse("-12"))
        assertEquals(1500.0, MiniJson.parse("1.5e3"))
        assertEquals(0.25, MiniJson.parse("0.25"))
        assertEquals(12L, MiniJson.num(MiniJson.parse("""{"n":"12"}"""), "n"))
        assertEquals(429L, MiniJson.num(MiniJson.parse("""{"n":429.0}"""), "n"))
        assertNull(MiniJson.num(MiniJson.parse("""{"n":4.5}"""), "n"))
    }

    @Test
    fun toleratesWhitespaceAndBom() {
        val v = MiniJson.parse("\uFEFF \n {\"a\" : [ 1 , 2 ] }\n")
        assertEquals(2L, MiniJson.get(v, "a", 1))
    }

    @Test
    fun rejectsBrokenJson() {
        for (bad in listOf("", "{", "[1,", "{\"a\"}", "{\"a\":}", "[1 2]", "\"abc", "tru", "{\"a\":1} x", "[\"\\q\"]", "01x", "-", "1.", "1e")) {
            try {
                MiniJson.parse(bad)
                fail("harus gagal: $bad")
            } catch (e: MiniJson.ParseException) {
                // ok
            }
        }
        assertNull(MiniJson.parseOrNull("{oops"))
    }

    @Test
    fun rejectsExcessiveNesting() {
        val deep = "[".repeat(200) + "]".repeat(200)
        assertNull(MiniJson.parseOrNull(deep))
        val ok = "[".repeat(30) + "]".repeat(30)
        assertTrue(MiniJson.parseOrNull(ok) is List<*>)
    }

    @Test
    fun writesAndRoundTrips() {
        val src = mapOf(
            "s" to "ba\"ris\n\ttab \\ \u0001 é \uD83D\uDE00 \u2028",
            "n" to 5,
            "d" to 1.5,
            "b" to true,
            "z" to null,
            "l" to listOf(1, "a", mapOf("k" to listOf<Any?>())),
            "m" to emptyMap<String, Any?>()
        )
        val json = MiniJson.write(src)
        val back = MiniJson.parse(json) as Map<*, *>
        assertEquals(src["s"], back["s"])
        assertEquals(5L, back["n"])
        assertEquals(1.5, back["d"])
        assertEquals(true, back["b"])
        assertTrue(back.containsKey("z") && back["z"] == null)
        assertEquals("a", MiniJson.str(back, "l", 1))
        assertTrue(MiniJson.arr(back, "l", 2, "k")!!.isEmpty())
        assertTrue(MiniJson.obj(back, "m")!!.isEmpty())
    }

    @Test
    fun nonFiniteNumbersBecomeNull() {
        assertEquals("[null,null]", MiniJson.write(listOf(Double.NaN, Double.POSITIVE_INFINITY)))
    }
}
