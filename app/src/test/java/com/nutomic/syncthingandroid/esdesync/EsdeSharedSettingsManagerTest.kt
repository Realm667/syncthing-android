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

    @Test fun categoriesCoverAllowlistAndExpandAsAtomicSelections() {
        assertEquals(EsdeSharedSettingsCatalog.specs.toSet(), EsdeSharedSettingsCatalog.categories.flatMap { it.specs }.toSet())
        assertEquals(EsdeSharedSettingsCatalog.categories.size, EsdeSharedSettingsCatalog.categoryById.size)
        assertTrue(EsdeSharedSettingsCatalog.categories.all { it.summary.isNotBlank() })

        val navigation = EsdeSharedSettingsCatalog.categoryById.getValue("navigation_ui")
        val selectedNames = EsdeSharedSettingsCatalog.namesForCategories(setOf(navigation.id))
        assertEquals(navigation.specs.mapTo(mutableSetOf()) { it.name }, selectedNames)
        assertTrue("DisplayClock" in selectedNames)
        assertFalse("ScraperUsernameScreenScraper" in selectedNames)
        assertFalse("CollectionSystemsCustom" in EsdeSharedSettingsCatalog.specs.map { it.name })
        assertTrue("CollectionSystemsCustom" in EsdeSharedSettingsCatalog.byName)
    }

    @Test fun customCollectionOwnershipNeverConflictsWithSharedSettings() {
        val fixture = fixture("""
            <string name="CollectionSystemsCustom" value="Favorites,Top" />
            <bool name="DisplayClock" value="false" />
        """.trimIndent())
        fixture.profile.writeText("""{"schemaVersion":1,"settings":{"CollectionSystemsCustom":{"type":"string","value":"Achievements"}}}""")

        val imported = fixture.manager.importSelected(setOf("CollectionSystemsCustom"))
        assertTrue(imported.successful)
        assertEquals(1, imported.skipped)
        assertEquals(
            "Favorites,Top",
            EsdeSettingsEditor().read(fixture.settings, setOf("CollectionSystemsCustom"))["CollectionSystemsCustom"]?.value,
        )

        val published = fixture.manager.publish(setOf("CollectionSystemsCustom"), allowInitialize = true)
        assertTrue(published.successful)
        assertEquals(1, published.skipped)
        assertTrue(fixture.profile.readText().contains("Achievements"))
    }

    @Test fun legacySelectionsMigrateToContainingCategories() {
        assertEquals(
            setOf("navigation_ui", "theme"),
            EsdeSharedSettingsCatalog.categoriesForSettingNames(setOf("DisplayClock", "Theme")),
        )
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
        val result = fixture.manager.publish(setOf("DisplayClock", "ROMDirectory"), allowInitialize = true)
        assertFalse(result.successful)
        val profile = fixture.profile.readText()
        assertTrue(profile.contains("DisplayClock"))
        assertFalse(profile.contains("ROMDirectory"))
        assertFalse(profile.contains("/not-shared"))
    }

    @Test fun rejectsWrongTypeButUnavailableThemeIsNonBlocking() {
        val fixture = fixture("<string name=\"Theme\" value=\"local-theme\" />")
        fixture.profile.writeText("""{"schemaVersion":1,"settings":{"DisplayClock":{"type":"bool","value":"true"}}}""")
        assertFalse(fixture.manager.importSelected(setOf("DisplayClock")).successful)

        fixture.profile.writeText("""{"schemaVersion":1,"settings":{"Theme":{"type":"string","value":"not-installed"}}}""")
        val result = fixture.manager.importSelected(setOf("Theme"))
        assertTrue(result.successful)
        assertEquals(1, result.warnings.size)
        assertEquals("local-theme", EsdeSettingsEditor().read(fixture.settings, setOf("Theme"))["Theme"]?.value)
    }

    @Test fun activeThemeAndVariantAreAcceptedWithoutFilesystemFalsePositive() {
        val fixture = fixture("""
            <string name="Theme" value="Art Book Next" />
            <string name="ThemeVariant" value="List: Metadata &amp; Boxart" />
        """.trimIndent())
        fixture.profile.writeText("""{"schemaVersion":1,"settings":{"Theme":{"type":"string","value":"Art Book Next"},"ThemeVariant":{"type":"string","value":"List: Metadata & Boxart"}}}""")
        val result = fixture.manager.importSelected(setOf("Theme", "ThemeVariant"))
        assertTrue(result.successful)
        assertTrue(result.warnings.isEmpty())
        assertEquals(2, result.skipped)
    }

    @Test fun firstTimeImportAdoptsExistingSharedProfileInsteadOfDeviceDefaults() {
        val fixture = fixture("<bool name=\"DisplayClock\" value=\"false\" />")
        fixture.profile.writeText("""{"schemaVersion":1,"settings":{"DisplayClock":{"type":"bool","value":true}}}""")
        val result = fixture.manager.importSelected(setOf("DisplayClock"))
        assertTrue(result.successful)
        assertEquals(1, result.applied)
        assertEquals("true", EsdeSettingsEditor().read(fixture.settings, setOf("DisplayClock"))["DisplayClock"]?.value)
        assertTrue(fixture.backups.walkTopDown().any { it.isFile })
    }

    @Test fun automaticPublishCannotSeedMissingProfileFromFreshDefaults() {
        val fixture = fixture("<bool name=\"DisplayClock\" value=\"false\" />")
        assertFalse(fixture.profile.exists())

        val automatic = fixture.manager.publish(setOf("DisplayClock"))
        assertFalse(automatic.successful)
        assertFalse(fixture.profile.exists())

        val explicit = fixture.manager.publish(setOf("DisplayClock"), allowInitialize = true)
        assertTrue(explicit.successful)
        assertTrue(fixture.profile.isFile)
    }

    @Test fun automaticPublishDoesNotPromoteLocalDefaultsForMissingSharedFields() {
        val fixture = fixture("<bool name=\"DisplayClock\" value=\"false\" />")
        fixture.profile.writeText("""{"schemaVersion":1,"settings":{}}""")

        val automatic = fixture.manager.publish(setOf("DisplayClock"))
        assertTrue(automatic.successful)
        assertEquals(1, automatic.skipped)
        assertFalse(fixture.profile.readText().contains("DisplayClock"))

        val explicit = fixture.manager.publish(setOf("DisplayClock"), allowInitialize = true)
        assertTrue(explicit.successful)
        assertTrue(fixture.profile.readText().contains("DisplayClock"))
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
