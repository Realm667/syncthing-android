package com.nutomic.syncthingandroid.esdesync

import com.google.gson.JsonParseException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EsdeSidecarStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val store = EsdeSidecarStore()

    @Test fun roundTripsNestedGameAndDoesNotRewriteUnchangedMetadata() {
        val system = temporary.newFolder("snes")
        val metadata = EsdeMetadata(true, false, 14, 38_742, "20260831T094218", "RETROARCH:MGBA")
        assertTrue(store.write(system, "./RPG/Chrono Trigger.sfc", metadata))
        val file = File(system, ".esde-sync/RPG/Chrono Trigger.sfc.esde.json")
        assertEquals(metadata, store.read(file).metadata())
        val modified = file.lastModified()
        assertFalse(store.write(system, "./RPG/Chrono Trigger.sfc", metadata))
        assertEquals(modified, file.lastModified())
    }

    @Test fun ignoresUnknownFieldsButRejectsUnknownSchema() {
        val system = temporary.newFolder("gba")
        val root = File(system, ".esde-sync").apply { mkdirs() }
        val valid = File(root, "Pokémon.gba.esde.json")
        valid.writeText("""{"schemaVersion":1,"game":"./Pokémon.gba","favorite":true,"future":"ok"}""")
        assertEquals(true, store.read(valid).favorite)
        valid.writeText("""{"schemaVersion":2,"game":"./Pokémon.gba"}""")
        org.junit.Assert.assertThrows(JsonParseException::class.java) { store.read(valid) }
    }

    @Test fun remainsCompatibleWithOptionalPlayersAndRating() {
        val system = temporary.newFolder("optional-fields")
        val root = File(system, ".esde-sync").apply { mkdirs() }
        val old = File(root, "Old.rom.esde.json").apply {
            writeText("""{"schemaVersion":1,"game":"./Old.rom","favorite":true}""")
        }
        assertEquals(EsdeMetadata(favorite = true), store.read(old).metadata())

        val metadata = EsdeMetadata(players = "1-2", rating = 1.0)
        assertTrue(store.write(system, "./New.rom", metadata))
        assertEquals(metadata, store.read(File(root, "New.rom.esde.json")).metadata())

        val invalid = File(root, "Bad.rom.esde.json").apply {
            writeText("""{"schemaVersion":1,"game":"./Bad.rom","players":"0-2","rating":2.0}""")
        }
        org.junit.Assert.assertThrows(JsonParseException::class.java) { store.read(invalid) }
    }

    @Test fun rejectsTraversalAbsoluteAndDrivePaths() {
        listOf("../../secret", "/absolute/game", "C:/game.rom", "./ok/../bad").forEach { path ->
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                EsdePathPolicy.normalizeGamePath(path)
            }
        }
    }

    @Test fun malformedAndOversizedJsonAreCountedInvalid() {
        val system = temporary.newFolder("psx")
        val root = File(system, ".esde-sync").apply { mkdirs() }
        File(root, "bad.esde.json").writeText("{")
        File(root, "large.esde.json").writeText("x".repeat((EsdeSidecarStore.MAX_JSON_BYTES + 1).toInt()))
        val result = store.scan(system)
        assertEquals(2, result.total)
        assertEquals(2, result.invalid)
    }
}
