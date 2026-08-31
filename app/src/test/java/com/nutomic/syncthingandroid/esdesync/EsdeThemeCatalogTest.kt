package com.nutomic.syncthingandroid.esdesync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EsdeThemeCatalogTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun resolvesDirectoryDisplayNameVariantIdAndLabel() {
        val root = temporary.newFolder("ES-DE")
        val theme = File(root, "themes/art-book-next-es-de").apply { mkdirs() }
        File(theme, "capabilities.xml").writeText("""
            <themeCapabilities>
              <themeName>Art Book Next</themeName>
              <variant name="gamelist-list-metadata-cover"><label>List: Metadata &amp; Boxart</label></variant>
            </themeCapabilities>
        """.trimIndent())

        val catalog = EsdeThemeCatalog(root)
        assertEquals("Art Book Next", catalog.find("art-book-next-es-de")?.displayName)
        val byDisplayName = catalog.find("Art Book Next")
        assertTrue(byDisplayName?.hasVariant("gamelist-list-metadata-cover") == true)
        assertTrue(byDisplayName?.hasVariant("List: Metadata & Boxart") == true)
    }
}
