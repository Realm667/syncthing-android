package com.nutomic.syncthingandroid.esdesync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
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

    @Test fun supportsEsdeSettingsFragmentsWithMultipleRootElements() {
        val file = temporary.newFile("es_settings.xml").apply {
            writeText(
                """<?xml version="1.0"?>
                    <string name="ROMDirectory" value="/storage/emulated/0/ROMs" />
                    <bool name="LegacyGamelistFileLocation" value="false" />
                    <int name="MaxVRAM" value="512" />
                """.trimIndent()
            )
        }

        val editor = EsdeSettingsEditor()
        assertTrue(editor.enableLegacyGamelistLocation(file))
        assertTrue(editor.isLegacyGamelistLocationEnabled(file))
        assertTrue(file.readText().contains("ROMDirectory"))
        assertTrue(file.readText().contains("MaxVRAM"))
        assertEquals(1, Regex("<\\?xml").findAll(file.readText()).count())
    }

    @Test fun rejectsDoctype() {
        val file = temporary.newFile("es_settings.xml").apply {
            writeText("<!DOCTYPE settings [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]><settings/>")
        }

        assertThrows(SAXException::class.java) {
            EsdeSettingsEditor().enableLegacyGamelistLocation(file)
        }
    }

    @Test fun configuratorBacksUpSettingsBeforeEnablingLegacyLocation() {
        val appFiles = temporary.newFolder("app-files")
        val esde = temporary.newFolder("ES-DE")
        val settingsDirectory = java.io.File(esde, "settings").apply { mkdirs() }
        val file = java.io.File(settingsDirectory, "es_settings.xml").apply {
            writeText("<settings><bool name=\"LegacyGamelistFileLocation\" value=\"false\"/></settings>")
        }

        ensureLegacyGamelistLocationBlocking(appFiles, esde.path, true)

        assertTrue(EsdeSettingsEditor().isLegacyGamelistLocationEnabled(file))
        val backup = java.io.File(appFiles, "esde-sync/backups/settings/es_settings.xml")
        assertTrue(backup.isFile)
        assertTrue(backup.readText().contains("value=\"false\""))
        assertEquals(
            "LegacyGamelistFileLocation is already enabled",
            ensureLegacyGamelistLocationBlocking(appFiles, esde.path, true),
        )
    }
}
