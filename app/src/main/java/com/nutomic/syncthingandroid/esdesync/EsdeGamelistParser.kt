package com.nutomic.syncthingandroid.esdesync

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import java.io.OutputStreamWriter
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler

class EsdeGamelistParser {
    data class ApplyResult(val matched: Int, val unmatched: Int, val changed: Int)

    fun parse(file: File): LinkedHashMap<String, EsdeMetadata> {
        val document = parseDocument(file)
        val result = LinkedHashMap<String, EsdeMetadata>()
        for (game in gameElements(document)) {
            val rawPath = childText(game, "path") ?: continue
            val path = runCatching { EsdePathPolicy.normalizeGamePath(rawPath) }.getOrNull() ?: continue
            result[path] = metadataOf(game)
        }
        return result
    }

    fun apply(file: File, updates: Map<String, EsdeMetadata>): ApplyResult {
        val document = parseDocument(file)
        val remaining = updates.toMutableMap()
        var matched = 0
        var changed = 0
        for (game in gameElements(document)) {
            val rawPath = childText(game, "path") ?: continue
            val path = runCatching { EsdePathPolicy.normalizeGamePath(rawPath) }.getOrNull() ?: continue
            val metadata = remaining.remove(path) ?: continue
            matched++
            if (applyMetadata(document, game, metadata)) changed++
        }
        if (changed > 0) writeDocument(file, document)
        return ApplyResult(matched, remaining.size, changed)
    }

    private fun gameElements(document: Document): List<Element> {
        val gameList = document.documentElement.children().single { it.tagName == "gameList" }
        return gameList.children().filter { it.tagName == "game" }
    }

    private fun Element.children(): List<Element> = (0 until childNodes.length)
        .mapNotNull { childNodes.item(it) as? Element }

    private fun metadataOf(game: Element) = EsdeMetadata(
        favorite = childText(game, "favorite")?.toBooleanStrictOrNull(),
        completed = childText(game, "completed")?.toBooleanStrictOrNull(),
        playcount = childText(game, "playcount")?.toLongOrNull(),
        playtime = childText(game, "playtime")?.toLongOrNull(),
        lastplayed = childText(game, "lastplayed"),
        altemulator = childText(game, "altemulator"),
        players = childText(game, "players")?.takeIf(EsdeMetadataValidation::isValidPlayers),
        rating = childText(game, "rating")?.toDoubleOrNull()?.takeIf { it in 0.0..1.0 },
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
        set("players", value.players?.takeIf(EsdeMetadataValidation::isValidPlayers))
        set("rating", value.rating?.takeIf { it in 0.0..1.0 }?.toString())
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
        require(file.isFile && file.length() in 1..MAX_GAMELIST_BYTES) { "gamelist.xml has invalid size" }
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
        builder.setErrorHandler(object : DefaultHandler() {
            override fun error(error: SAXParseException) = throw error
            override fun fatalError(error: SAXParseException) = throw error
        })

        // ES-DE currently writes alternativeEmulator and gameList as sibling roots. Parse that
        // known fragment form inside a private wrapper; never accept arbitrary additional roots.
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = decoder.decode(ByteBuffer.wrap(file.readBytes())).toString().removePrefix("\uFEFF")
        val fragment = XML_DECLARATION.replaceFirst(text, "")
        val document = builder.parse(InputSource(StringReader("<$FRAGMENT_ROOT>$fragment</$FRAGMENT_ROOT>")))
        validateFragment(document)
        return document
    }

    private fun validateFragment(document: Document) {
        val root = document.documentElement
        require(root?.tagName == FRAGMENT_ROOT) { "Missing gamelist fragment root" }
        var gameLists = 0
        var alternativeEmulators = 0
        var child: Node? = root.firstChild
        while (child != null) {
            when (child.nodeType) {
                Node.ELEMENT_NODE -> when (child.nodeName) {
                    "gameList" -> gameLists++
                    "alternativeEmulator" -> alternativeEmulators++
                    else -> throw SAXException("Unsupported top-level gamelist element: ${child.nodeName}")
                }
                Node.TEXT_NODE -> require(child.textContent.isBlank()) { "Unexpected text outside gameList" }
                Node.COMMENT_NODE -> Unit
                else -> throw SAXException("Unsupported top-level gamelist node")
            }
            child = child.nextSibling
        }
        require(gameLists == 1) { "Expected exactly one gameList element" }
        require(alternativeEmulators <= 1) { "Expected at most one alternativeEmulator element" }
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
            setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")
        }
        AtomicFileWriter.write(file) { output ->
            val writer = OutputStreamWriter(output, StandardCharsets.UTF_8)
            writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            var child: Node? = document.documentElement.firstChild
            while (child != null) {
                if (child.nodeType == Node.ELEMENT_NODE || child.nodeType == Node.COMMENT_NODE) {
                    writer.write("\n")
                    transformer.transform(DOMSource(child), StreamResult(writer))
                }
                child = child.nextSibling
            }
            writer.write("\n")
            writer.flush()
        }
    }

    companion object {
        private const val MAX_GAMELIST_BYTES = 64L * 1024L * 1024L
        private const val FRAGMENT_ROOT = "esdeSyncDocument"
        private val XML_DECLARATION = Regex("^\\s*<\\?xml\\s+[^?]*\\?>", RegexOption.IGNORE_CASE)
    }
}
