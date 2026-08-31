package com.nutomic.syncthingandroid.esdesync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EsdeSharedSettingsManagerTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun catalogIsPositiveOnlyAndForbidsSecretsPathsAndPasskey() {
        assertTrue("LegacyGamelistFileLocation" in EsdeSharedSettingsCatalog.byName)
        listOf(
            "ScraperUsernameScreenScraper", "ScraperPasswordScreenScraper", "ScraperUseAccountScreenScraper",
            "UIMode_passkey", "ROMDirectory", "MediaDirectory", "ThemeDirectory", "AudioVolume",
            "MediaViewerVideoAudio", "ScreensaverVideoAudio", "ScraperConnectionTimeout",
        ).forEach { name ->
            assertFalse(name in EsdeSharedSettingsCatalog.byName)
            assertThrows(IllegalArgumentException::class.java) { EsdeSharedSettingsCatalog.requireAllowed(name) }
        }
    }

    @Test fun missingSharedValuesPreserveLocalAndUnknownKeysRejectWholeProfile() {
        val fixture = fixture("<bool name=\"DisplayClock\" value=\"false\" />")
        fixture.profile.writeText("""{"schemaVersion":1,"settings":{}}""")
        assertTrue(fixture.manager.importSelected(setOf("DisplayClock")).successful)
        assertEquals("false", EsdeSettingsEditor().read(fixture.settings, setOf("DisplayClock"))["DisplayClock"]?.value)

        fixture.profile.writeText("""{"schemaVersion":1,"settings":{"UnknownSetting":{"type":"bool","value":true}}}""")
        assertFalse(fixture.manager.importSelected(setOf("DisplayClock")).successful)
        assertEquals("false", EsdeSettingsEditor().read(fixture.settings, setOf("DisplayClock"))["DisplayClock"]?.value)
    }

    @Test fun forbiddenPathKeyCannotBePublished() {
        val fixture = fixture("""
            <bool name="DisplayClock" value="true" />
            <string name="ROMDirectory" value="/not-shared" />
        """.trimIndent())
        val result = fixture.manager.publish(setOf("DisplayClock", "ROMDirectory"))
        assertFalse(result.successful)
        val profile = fixture.profile.readText()
        assertTrue(profile.contains("DisplayClock"))
        assertFalse(profile.contains("ROMDirectory"))
        assertFalse(profile.contains("/not-shared"))
    }

    @Test fun rejectsWrongTypeAndUnavailableTheme() {
        val fixture = fixture("<string name=\"Theme\" value=\"local-theme\" />")
        fixture.profile.writeText("""{"schemaVersion":1,"settings":{"DisplayClock":{"type":"bool","value":"true"}}}""")
        assertFalse(fixture.manager.importSelected(setOf("DisplayClock")).successful)

        fixture.profile.writeText("""{"schemaVersion":1,"settings":{"Theme":{"type":"string","value":"not-installed"}}}""")
        val result = fixture.manager.importSelected(setOf("Theme"))
        assertFalse(result.successful)
        assertEquals("local-theme", EsdeSettingsEditor().read(fixture.settings, setOf("Theme"))["Theme"]?.value)
    }

    @Test fun firstTimeSettingsMismatchIsAConflict() {
        val fixture = fixture("<bool name=\"DisplayClock\" value=\"false\" />")
        fixture.profile.writeText("""{"schemaVersion":1,"settings":{"DisplayClock":{"type":"bool","value":true}}}""")
        val result = fixture.manager.importSelected(setOf("DisplayClock"))
        assertEquals(listOf("DisplayClock"), result.conflicts)
        assertEquals("false", EsdeSettingsEditor().read(fixture.settings, setOf("DisplayClock"))["DisplayClock"]?.value)
    }

    @Test fun importUsesSnapshotCreatesBackupAndWritesAtomically() {
        val fixture = fixture("<bool name=\"DisplayClock\" value=\"false\" />")
        fixture.profile.writeText("""{"schemaVersion":1,"settings":{"DisplayClock":{"type":"bool","value":false}}}""")
        assertTrue(fixture.manager.importSelected(setOf("DisplayClock")).successful)
        fixture.profile.writeText("""{"schemaVersion":1,"settings":{"DisplayClock":{"type":"bool","value":true}}}""")
        val result = fixture.manager.importSelected(setOf("DisplayClock"))
        assertTrue(result.successful)
        assertEquals(1, result.applied)
        assertEquals("true", EsdeSettingsEditor().read(fixture.settings, setOf("DisplayClock"))["DisplayClock"]?.value)
        assertTrue(fixture.backups.walkTopDown().any { it.isFile })
        assertFalse(fixture.settings.parentFile.listFiles().orEmpty().any { it.name.endsWith(".tmp") })
    }

    @Test fun globalResultBlocksSafeLaunchOnConflictOrValidationError() {
        assertTrue(EsdeGlobalImportResult().successful)
        assertFalse(EsdeGlobalImportResult(collections = EsdeSharedOperationResult(conflicts = listOf("Top"))).successful)
        assertFalse(EsdeGlobalImportResult(settings = EsdeSharedOperationResult(errors = listOf("Theme"))).successful)
    }

    private fun fixture(xml: String): Fixture {
        val gamelists = temporary.newFolder("roms-${System.nanoTime()}")
        val esde = temporary.newFolder("esde-${System.nanoTime()}")
        val settings = File(File(esde, "settings").apply { mkdirs() }, "es_settings.xml").apply { writeText(xml) }
        val profile = File(File(File(gamelists, EsdeGlobalLayout.DIRECTORY), "settings").apply { mkdirs() }, EsdeGlobalLayout.SETTINGS_FILE)
        val backups = temporary.newFolder("backups-${System.nanoTime()}")
        return Fixture(
            EsdeSharedSettingsManager(
                gamelists, esde,
                EsdeSharedSnapshotStore(temporary.newFolder("snapshots-${System.nanoTime()}")),
                EsdePrivateFileBackup(backups),
            ), settings, profile, backups,
        )
    }

    private data class Fixture(
        val manager: EsdeSharedSettingsManager,
        val settings: File,
        val profile: File,
        val backups: File,
    )
}
