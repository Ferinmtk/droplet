package dev.droplet.app

import dev.droplet.app.mesh.Links
import dev.droplet.app.mesh.Perms
import dev.droplet.app.mesh.Transfers
import dev.droplet.app.mesh.TrustList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The parts of docs/mesh.md §9.10 that need no network, ported from the
 * reference's tests (agent/tests/test_transfers.py and test_sending.py):
 * speed and time left from byte counts, which links may travel, and names.
 */
class TransfersTest {
    @Test
    fun theListWorksOutSpeedAndTimeLeftFromByteCounts() {
        var now = 100_000L
        val t = Transfers { now }
        val id = "a".repeat(32)
        t.start(id, "out", "f".repeat(64), "pixel", "video.mp4", 10_000_000)
        assertEquals(0, t.get(id)!!.percent)
        assertNull(t.get(id)!!.rate)
        for (i in 1..4) {
            now += 1000
            t.progress(id, i * 1_000_000L)
        }
        val v = t.get(id)!!
        assertEquals(40, v.percent)
        assertEquals(1_000_000L, v.rate)
        assertEquals(6L, v.eta)
        assertEquals("40% · 6 s left · 976.6 KB/s", Transfers.progressText(v))
        now += 10_000   // nothing for a while: stalled, not "6 s left" forever
        assertNull(t.get(id)!!.rate)
        assertNull(t.get(id)!!.eta)
        t.finish(id, Transfers.CANCELLED, "pixel cancelled it")
        assertEquals("Cancelled: pixel cancelled it", Transfers.progressText(t.get(id)!!))
        now += Transfers.KEEP_MS + 1000   // finished ones go after a while
        assertTrue(t.list().isEmpty())
        assertEquals("5 s left", Transfers.etaText(5))
        assertEquals("2 min left", Transfers.etaText(125))
        assertEquals("1 h 2 min left", Transfers.etaText(3725))
    }

    @Test
    fun howATransferEndedReadsPlainly() {
        var now = 0L
        val t = Transfers { now }
        t.start("x", "in", "f", "slim", "a.bin", 100)
        assertEquals("0% · starting…", Transfers.progressText(t.get("x")!!))
        t.finish("x", Transfers.DONE)
        assertEquals("Received", Transfers.progressText(t.get("x")!!))
        t.start("y", "out", "f", "slim", "b.bin", 100)
        t.finish("y", Transfers.CANCELLED, Transfers.CANCELLED_HERE)
        assertEquals("Cancelled", Transfers.progressText(t.get("y")!!))
        t.start("z", "out", "f", "slim", "c.bin", 100)
        now += 1
        t.progress("z", 50)
        t.finish("z", Transfers.WAITING, "the link dropped")
        assertEquals("Stopped at 50%: it carries on when it can", Transfers.progressText(t.get("z")!!))
        // a resume keeps what it had
        t.start("z", "out", "f", "slim", "c.bin", 100)
        assertEquals(50, t.get("z")!!.percent)
        // active first
        assertEquals("z", t.list().first().id)
    }

    @Test
    fun onlyWebLinksTravelAndOpen() {
        val cases = mapOf(
            "https://example.com/a?b=c#d" to true, "http://192.168.1.20:8000/" to true, "  https://x.org  " to true,
            "http://[fd00::1]:8000/x" to true, "https://user@host.example/" to true,
            "javascript:alert(1)" to false, "file:///etc/passwd" to false, "data:text/html,hi" to false,
            "ftp://x.org" to false, "intent://x#Intent;end" to false, "https://" to false, "https://a b.com" to false,
            "https://x.org/‮" to false, "https://x.org/​" to false, "https://x.org/­" to false,
            "https://x.org/" + "a".repeat(3000) to false, "" to false, "content://media/1" to false,
            "droplet://open" to false, "https://x.org/\n" to true,   // trimmed, as the reference does
        )
        for ((url, ok) in cases) assertEquals(url, ok, Links.isUrl(url))
        assertFalse(Links.isUrl(null))
        assertFalse(Links.isUrl(5))
        try {
            Links.checkUrl("file:///etc/passwd")
            fail()
        } catch (e: Links.BadLink) {
            assertTrue(e.message!!.contains("only web links"))
        }
    }

    @Test
    fun aMessageThatsOnlyALinkGetsAnOpenButton() {
        assertEquals("https://x.org/a", Links.onlyUrl(" https://x.org/a "))
        assertNull(Links.onlyUrl("look: https://x.org"))
        assertNull(Links.onlyUrl("javascript:alert(1)"))
        assertNull(Links.onlyUrl(null))
    }

    @Test
    fun namesAreChecked() {
        assertEquals("Ferrin's laptop", TrustList.checkName("  Ferrin's   laptop "))
        assertEquals("", TrustList.checkName("  ", "a nickname", emptyOk = true))
        val bad = listOf("" to "empty", "   " to "empty", "a".repeat(41) to "40 characters", "bad\u0007name" to "control",
            "evil‮gnp.exe" to "control", "zero​width" to "control", 5 to "text")
        for ((v, why) in bad) {
            try {
                TrustList.checkName(v)
                fail("$v should be refused")
            } catch (e: IllegalArgumentException) {
                assertTrue("$v: ${e.message}", e.message!!.contains(why))
            }
        }
        assertEquals("", TrustList.cleanNickname("x".repeat(50)))
        assertEquals("NAS", TrustList.cleanNickname(" NAS "))
    }

    @Test
    fun cancelAndRenameAlwaysPass() {
        assertTrue("cancel" in Perms.ALWAYS && "rename" in Perms.ALWAYS)
    }
}
