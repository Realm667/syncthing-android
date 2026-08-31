package com.nutomic.syncthingandroid.esdesync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.xml.sax.SAXException

class EsdeSettingsEditorTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun enablesLegacyLocationWithoutRemovingOtherSettings() {
        val file = temporary.newFile("es_settings.xml").apply {
            writeText(
                """<?xml version="1.0"?><settings>
                    <string name="ROMDirectory" value="/storage/emulated/0/ROMs" />
                    <bool name="LegacyGamelistFileLocation" value="false" />
                </settings>""".trimIndent()
            )
        }
        val editor = EsdeSettingsEditor()

        assertFalse(editor.isLegacyGamelistLocationEnabled(file))
        assertTrue(editor.enableLegacyGamelistLocation(file))
        assertTrue(editor.isLegacyGamelistLocationEnabled(file))
        assertTrue(file.readText().contains("ROMDirectory"))
        assertFalse(editor.enableLegacyGamelistLocation(file))
    }

    @Test fun addsMissingLegacySetting() {
        val file = temporary.newFile("es_settings.xml").apply { writeText("<settings/>") }

        assertTrue(EsdeSettingsEditor().enableLegacyGamelistLocation(file))
        assertTrue(file.readText().contains("name=\"LegacyGamelistFileLocation\""))
    }

    @Test fun rejectsDoctype() {
        val file = temporary.newFile("es_settings.xml").apply {
            writeText("<!DOCTYPE settings [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]><settings/>")
        }

        assertThrows(SAXException::class.java) {
            EsdeSettingsEditor().enableLegacyGamelistLocation(file)
        }
    }
}
