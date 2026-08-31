package com.nutomic.syncthingandroid.esdesync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EsdeCollectionCodecTest {
    @get:Rule val temporary = TemporaryFolder()
    private val codec = EsdeCollectionCodec()

    @Test fun parsesSupportedExampleCollections() {
        val examples = mapOf(
            "2 Player" to "<players>2</players>",
            "Achievements" to "<cheevos>TRUE</cheevos>",
            "Favorites" to "<favorites>TRUE</favorites>",
            "Top" to "<ratings>5 STARS</ratings>",
        )
        examples.forEach { (name, filter) ->
            val parsed = codec.read(collection(name, "<filter name=\"$name\">$filter</filter>"))
            assertEquals(name, parsed.name)
            assertEquals(1, parsed.filters.size)
        }
    }

    @Test fun rejectsMalformedMultipleRootsDoctypeTraversalOversizeAndMismatchedName() {
        listOf(
            "Broken" to "<filter",
            "Roots" to "<filter name=\"Roots\"><players>2</players></filter><filter name=\"Other\"><players>2</players></filter>",
            "Dtd" to "<!DOCTYPE filter [<!ENTITY x SYSTEM \"file:///invalid\">]><filter name=\"Dtd\"><players>&x;</players></filter>",
            "Mismatch" to "<filter name=\"Other\"><players>2</players></filter>",
        ).forEach { (name, xml) -> assertThrows(Exception::class.java) { codec.read(collection(name, xml)) } }
        assertThrows(Exception::class.java) { codec.read(collection("..", "<filter name=\"..\"><players>2</players></filter>")) }
        val huge = File(temporary.root, "Huge.xcc").apply { writeBytes(ByteArray(EsdeCollectionCodec.MAX_BYTES.toInt() + 1)) }
        assertThrows(Exception::class.java) { codec.read(huge) }
    }

    @Test fun rejectsUnsupportedAndImplausibleFilters() {
        assertThrows(Exception::class.java) { codec.read(collection("Bad", "<filter name=\"Bad\"><path>x</path></filter>")) }
        assertThrows(Exception::class.java) { codec.read(collection("BadPlayers", "<filter name=\"BadPlayers\"><players>0</players></filter>")) }
        assertThrows(Exception::class.java) { codec.read(collection("BadRating", "<filter name=\"BadRating\"><ratings>6 STARS</ratings></filter>")) }
    }

    private fun collection(name: String, xml: String) = File(temporary.root, "$name.xcc").apply {
        writeText("<?xml version=\"1.0\"?>$xml")
    }
}
