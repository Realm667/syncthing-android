package com.nutomic.syncthingandroid.esdesync

import org.w3c.dom.Element
import org.xml.sax.SAXException
import java.io.File
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

internal class EsdeThemeCatalog(private val esdeRoot: File) {
    data class Theme(
        val directoryName: String,
        val displayName: String,
        val variants: Map<String, String>,
    ) {
        fun matches(value: String): Boolean =
            value.equals(directoryName, ignoreCase = true) || value.equals(displayName, ignoreCase = true)

        fun hasVariant(value: String): Boolean = variants.any { (id, label) ->
            value.equals(id, ignoreCase = true) || value.equals(label, ignoreCase = true)
        }
    }

    fun find(value: String): Theme? {
        if (value.isBlank() || !SAFE_NAME.matches(value)) return null
        return themes().firstOrNull { it.matches(value) }
    }

    private fun themes(): Sequence<Theme> {
        val root = File(esdeRoot, "themes")
        val prefix = runCatching { root.canonicalPath.trimEnd(File.separatorChar) + File.separator }.getOrNull()
            ?: return emptySequence()
        return root.listFiles().orEmpty().asSequence()
            .filter { it.isDirectory }
            .take(MAX_THEME_DIRECTORIES)
            .mapNotNull { directory ->
                runCatching {
                    require(directory.canonicalPath.startsWith(prefix))
                    parse(directory)
                }.getOrNull()
            }
    }

    private fun parse(directory: File): Theme {
        val capabilities = File(directory, "capabilities.xml")
        if (!capabilities.isFile || capabilities.length() !in 1..MAX_THEME_FILE_BYTES) {
            return Theme(directory.name, directory.name, emptyMap())
        }
        if (capabilities.inputStream().buffered().use { input ->
                input.reader(Charsets.UTF_8).readText().contains("<!DOCTYPE", ignoreCase = true)
            }) throw SAXException("DOCTYPE is forbidden in theme capabilities")
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            runCatching { isXIncludeAware = false }
            setExpandEntityReferences(false)
            setFeatureSafely(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeatureSafely("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeatureSafely("http://xml.org/sax/features/external-general-entities", false)
            setFeatureSafely("http://xml.org/sax/features/external-parameter-entities", false)
        }
        val document = factory.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> throw SAXException("External entities are forbidden") }
        }.parse(capabilities)
        val root = document.documentElement ?: throw SAXException("Missing capabilities root")
        val displayName = root.getElementsByTagName("themeName").item(0)?.textContent
            ?.trim()?.takeIf { it.isNotBlank() && it.length <= 160 } ?: directory.name
        val variants = linkedMapOf<String, String>()
        val nodes = root.getElementsByTagName("variant")
        for (index in 0 until minOf(nodes.length, MAX_VARIANTS)) {
            val variant = nodes.item(index) as? Element ?: continue
            val id = variant.getAttribute("name").trim().takeIf { SAFE_NAME.matches(it) } ?: continue
            val label = variant.getElementsByTagName("label").item(0)?.textContent
                ?.trim()?.takeIf { it.isNotBlank() && it.length <= 160 } ?: id
            variants[id] = label
        }
        return Theme(directory.name, displayName, variants)
    }

    private fun DocumentBuilderFactory.setFeatureSafely(name: String, value: Boolean) {
        runCatching { setFeature(name, value) }
    }

    companion object {
        private val SAFE_NAME = Regex("^[A-Za-z0-9._ :&+()!'-]{1,160}$")
        private const val MAX_THEME_DIRECTORIES = 256
        private const val MAX_VARIANTS = 256
        private const val MAX_THEME_FILE_BYTES = 1024L * 1024L
    }
}
