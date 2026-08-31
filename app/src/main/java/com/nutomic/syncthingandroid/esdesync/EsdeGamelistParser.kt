package com.nutomic.syncthingandroid.esdesync

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.xml.sax.SAXException

class EsdeGamelistParser {
    data class ApplyResult(val matched: Int, val unmatched: Int, val changed: Int)

    fun parse(file: File): LinkedHashMap<String, EsdeMetadata> {
        val document = parseDocument(file)
        val result = LinkedHashMap<String, EsdeMetadata>()
        val games = document.getElementsByTagName("game")
        for (index in 0 until games.length) {
            val game = games.item(index) as? Element ?: continue
            val rawPath = childText(game, "path") ?: continue
            val path = runCatching { EsdePathPolicy.normalizeGamePath(rawPath) }.getOrNull() ?: continue
            result[path] = metadataOf(game)
        }
        return result
    }

    fun apply(file: File, updates: Map<String, EsdeMetadata>): ApplyResult {
        val document = parseDocument(file)
        val games = document.getElementsByTagName("game")
        val remaining = updates.toMutableMap()
        var matched = 0
        var changed = 0
        for (index in 0 until games.length) {
            val game = games.item(index) as? Element ?: continue
            val rawPath = childText(game, "path") ?: continue
            val path = runCatching { EsdePathPolicy.normalizeGamePath(rawPath) }.getOrNull() ?: continue
            val metadata = remaining.remove(path) ?: continue
            matched++
            if (applyMetadata(document, game, metadata)) changed++
        }
        if (changed > 0) writeDocument(file, document)
        return ApplyResult(matched, remaining.size, changed)
    }

    private fun metadataOf(game: Element) = EsdeMetadata(
        favorite = childText(game, "favorite")?.toBooleanStrictOrNull(),
        completed = childText(game, "completed")?.toBooleanStrictOrNull(),
        playcount = childText(game, "playcount")?.toLongOrNull(),
        playtime = childText(game, "playtime")?.toLongOrNull(),
        lastplayed = childText(game, "lastplayed"),
        altemulator = childText(game, "altemulator"),
    )

    private fun applyMetadata(document: Document, game: Element, value: EsdeMetadata): Boolean {
        var changed = false
        fun set(name: String, text: String?) {
            if (text == null) return
            val existing = directChild(game, name)
            if (existing?.textContent == text) return
            val target = existing ?: document.createElement(name).also { game.appendChild(it) }
            target.textContent = text
            changed = true
        }
        set("favorite", value.favorite?.toString())
        set("completed", value.completed?.toString())
        set("playcount", value.playcount?.toString())
        set("playtime", value.playtime?.toString())
        set("lastplayed", value.lastplayed)
        set("altemulator", value.altemulator)
        return changed
    }

    private fun directChild(parent: Element, name: String): Element? {
        var child: Node? = parent.firstChild
        while (child != null) {
            if (child.nodeType == Node.ELEMENT_NODE && child.nodeName == name) return child as Element
            child = child.nextSibling
        }
        return null
    }

    private fun childText(parent: Element, name: String): String? =
        directChild(parent, name)?.textContent

    private fun parseDocument(file: File): Document {
        if (containsAsciiIgnoreCase(file, "<!DOCTYPE")) throw SAXException("DOCTYPE is forbidden in gamelist.xml")
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        runCatching { factory.isXIncludeAware = false }
        factory.setExpandEntityReferences(false)
        setFeature(factory, XMLConstants.FEATURE_SECURE_PROCESSING, true)
        setFeature(factory, "http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature(factory, "http://xml.org/sax/features/external-general-entities", false)
        setFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false)
        val builder = factory.newDocumentBuilder()
        builder.setEntityResolver { _, _ -> throw SAXException("External entities are forbidden") }
        return builder.parse(file)
    }

    private fun setFeature(factory: DocumentBuilderFactory, name: String, enabled: Boolean) {
        runCatching { factory.setFeature(name, enabled) }
    }

    private fun containsAsciiIgnoreCase(file: File, needle: String): Boolean {
        val target = needle.uppercase().encodeToByteArray()
        var matched = 0
        file.inputStream().buffered().use { input ->
            while (true) {
                val value = input.read()
                if (value < 0) return false
                val upper = if (value in 'a'.code..'z'.code) value - 32 else value
                matched = if (upper == target[matched].toInt()) matched + 1 else if (upper == target[0].toInt()) 1 else 0
                if (matched == target.size) return true
            }
        }
    }

    private fun writeDocument(file: File, document: Document) {
        val transformerFactory = TransformerFactory.newInstance()
        runCatching { transformerFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
        val transformer = transformerFactory.newTransformer().apply {
            setOutputProperty(OutputKeys.ENCODING, "UTF-8")
            setOutputProperty(OutputKeys.INDENT, "yes")
            setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no")
        }
        AtomicFileWriter.write(file) { output -> transformer.transform(DOMSource(document), StreamResult(output)) }
    }
}
