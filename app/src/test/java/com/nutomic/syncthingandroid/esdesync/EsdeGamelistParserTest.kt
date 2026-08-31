package com.nutomic.syncthingandroid.esdesync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EsdeGamelistParserTest {
    @get:Rule val temporary = TemporaryFolder()
    private val parser = EsdeGamelistParser()

    @Test fun parsesAllFieldsUnicodeNestedAndM3u() {
        val file = gamelist("""
            <gameList>
              <game><path>./RPG/Chrono &amp; Trigger.m3u</path><favorite>false</favorite>
                <completed>true</completed><playcount>0</playcount><playtime>922337203685477580</playtime>
                <lastplayed>20260831T094218</lastplayed><altemulator>RETROARCH:MÜGBA</altemulator></game>
            </gameList>
        """)
        val value = parser.parse(file)["./RPG/Chrono & Trigger.m3u"]!!
        assertEquals(false, value.favorite)
        assertEquals(true, value.completed)
        assertEquals(0L, value.playcount)
        assertEquals(922337203685477580L, value.playtime)
        assertEquals("RETROARCH:MÜGBA", value.altemulator)
    }

    @Test fun missingSidecarFieldsDoNotResetXmlAndUnknownTagsSurvive() {
        val file = gamelist("""
            <gameList><game custom="yes"><path>./Zelda.sfc</path><name>Zelda &amp; Link</name>
              <favorite>false</favorite><playtime>30000</playtime><future><nested>keep</nested></future>
            </game></gameList>
        """)
        val result = parser.apply(file, mapOf("./Zelda.sfc" to EsdeMetadata(favorite = true)))
        assertEquals(1, result.changed)
        val parsed = parser.parse(file)["./Zelda.sfc"]!!
        assertEquals(true, parsed.favorite)
        assertEquals(30000L, parsed.playtime)
        val xml = file.readText()
        assertTrue(xml.contains("custom=\"yes\""))
        assertTrue(xml.contains("<name>Zelda &amp; Link</name>"))
        assertTrue(xml.contains("<future>"))
        assertTrue(xml.contains("<nested>keep</nested>"))
    }

    @Test fun unknownGameIsPendingAndNoEntryIsCreated() {
        val file = gamelist("<gameList><game><path>./Known.sfc</path></game></gameList>")
        val result = parser.apply(file, mapOf("./Missing.sfc" to EsdeMetadata(completed = true)))
        assertEquals(0, result.matched)
        assertEquals(1, result.unmatched)
        assertFalse(file.readText().contains("Missing.sfc"))
    }

    @Test fun indexesTenThousandGamesAndUpdatesOne() {
        val body = buildString {
            append("<gameList>")
            repeat(10_000) { append("<game><path>./Game-$it.rom</path><playcount>$it</playcount></game>") }
            append("</gameList>")
        }
        val file = gamelist(body)
        assertEquals(10_000, parser.parse(file).size)
        val result = parser.apply(file, mapOf("./Game-9999.rom" to EsdeMetadata(playcount = 10_000)))
        assertEquals(1, result.changed)
        assertEquals(10_000L, parser.parse(file)["./Game-9999.rom"]?.playcount)
    }

    @Test fun rejectsDoctypeAndExternalEntityInput() {
        val file = gamelist("""<!DOCTYPE gameList [<!ENTITY xxe SYSTEM "file:///secret">]><gameList><game><path>&xxe;</path></game></gameList>""")
        org.junit.Assert.assertThrows(Exception::class.java) { parser.parse(file) }
    }

    @Test fun acceptsEsdeAlternativeEmulatorSiblingAndPreservesItOnWrite() {
        val file = gamelist("""
            <?xml version="1.0"?>
            <alternativeEmulator><label>bsnes-hd</label></alternativeEmulator>
            <gameList><game><path>./Chrono Trigger.sfc</path><favorite>false</favorite></game></gameList>
        """)
        assertEquals(false, parser.parse(file)["./Chrono Trigger.sfc"]?.favorite)

        val result = parser.apply(file, mapOf("./Chrono Trigger.sfc" to EsdeMetadata(favorite = true)))

        assertEquals(1, result.changed)
        assertEquals(true, parser.parse(file)["./Chrono Trigger.sfc"]?.favorite)
        val xml = file.readText()
        assertTrue(xml.contains("<alternativeEmulator>"))
        assertTrue(xml.contains("<label>bsnes-hd</label>"))
        assertFalse(xml.contains("<$FRAGMENT_ROOT_FOR_ASSERTION>"))
    }

    @Test fun rejectsUnknownOrRepeatedFragmentRoots() {
        val file = gamelist("<other/><gameList/>")
        org.junit.Assert.assertThrows(Exception::class.java) {
            parser.parse(file)
        }
        file.writeText("<gameList/><gameList/>")
        org.junit.Assert.assertThrows(Exception::class.java) {
            parser.parse(file)
        }
    }

    private fun gamelist(xml: String): File = temporary.newFile("gamelist.xml").also { it.writeText(xml.trimIndent()) }

    companion object { private const val FRAGMENT_ROOT_FOR_ASSERTION = "esdeSyncDocument" }
}
