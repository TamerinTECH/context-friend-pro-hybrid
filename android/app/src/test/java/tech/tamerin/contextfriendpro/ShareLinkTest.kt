package tech.tamerin.contextfriendpro

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareLinkTest {
    private val id = "AAAAAAAAAAAAAAAAAAAAAA"          // 16 bytes, base64url without padding
    private val key = ByteArray(32) { it.toByte() }

    @Test fun roundTrip() {
        val encoded = ShareLink(id, key).encode()
        assertTrue(encoded.startsWith("CFS1:$id:"))
        val parsed = ShareLink.parse(encoded)
        assertEquals(id, parsed.id)
        assertArrayEquals(key, parsed.key)
    }

    @Test fun findsTheLinkInsideSharedText() {
        val parsed = ShareLink.parse("Meeting notes: " + ShareLink(id, key).encode() + "\n")
        assertArrayEquals(key, parsed.key)
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsShortKey() { ShareLink.parse("CFS1:$id:AAAA") }

    @Test(expected = IllegalArgumentException::class) fun rejectsOtherFormats() { ShareLink.parse("CFI1:something") }
}
