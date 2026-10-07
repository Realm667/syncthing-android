package com.nutomic.syncthingandroid.esdesync

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import java.io.OutputStreamWriter
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
import org.xml.sax.Attributes
import javax.xml.parsers.SAXParserFactory

class EsdeGamelistParser {
    data class ApplyResult(val matched: Int, val unmatched: Int, val changed: Int)
    data class AppliedSnapshot(val result: ApplyResult, val metadata: LinkedHashMap<String, EsdeMetadata>)

    fun parse(file: File): LinkedHashMap<String, EsdeMetadata> {
        val result = LinkedHashMap<String, EsdeMetadata>()
        val factory = SAXParserFactory.newInstance()
        runCatching { factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        val handler = object : DefaultHandler() {
            var depth = 0
            var lists = 0
            var alternatives = 0
            var inGame = false
            var inGameList = false
            var field: String? = null
            val values = mutableMapOf<String, String>()
            val text = StringBuilder()
            override fun resolveEntity(publicId: String?, systemId: String?): InputSource = throw SAXException("External entities are forbidden")
            override fun error(error: SAXParseException) = throw error
            override fun fatalError(error: SAXParseException) = throw error
            override fun startElement(uri: String?, local: String?, name: String, attributes: Attributes?) {
                depth++
                if (depth == 2) when (name) {
                    "gameList" -> { lists++; inGameList = true }
                    "alternativeEmulator" -> { alternatives++; inGameList = false }
                    else -> throw SAXException("Unsupported top-level gamelist element: $name")
                }
                if (depth == 3 && inGameList && name == "game") { inGame = true; values.clear() }
                if (depth == 4 && inGame && name in METADATA_FIELDS) { field = name; text.setLength(0) }
            }
            override fun characters(chars: CharArray, start: Int, length: Int) {
                if (depth == 1) require((start until start + length).all { chars[it].isWhitespace() }) { "Unexpected text outside gameList" }
                if (field != null) { require(text.length + length <= 4096) { "Metadata field is too large" }; text.append(chars, start, length) }
            }
            override fun processingInstruction(target: String?, data: String?) {
                require(depth != 1) { "Unsupported top-level gamelist node" }
            }
            override fun endElement(uri: String?, local: String?, name: String) {
                if (depth == 4 && field != null) { if (field !in values) values[field!!] = text.toString(); field = null }
                if (depth == 3 && inGame) {
                    values["path"]?.let { raw -> runCatching { EsdePathPolicy.normalizeGamePath(raw) }.getOrNull()?.let { path ->
                        result[path] = metadataFrom { values[it] }
                    } }
                    inGame = false
                }
                depth--
            }
            override fun endDocument() {
                require(lists == 1 && alternatives <= 1) { "Expected one gameList and at most one alternativeEmulator" }
            }
        }
        EsdeXmlFragmentReader(file, FRAGMENT_ROOT, MAX_GAMELIST_BYTES).use {
            factory.newSAXParser().parse(InputSource(it), handler)
        }
        return result
    }

    private fun metadataSnapshot(document: Document): LinkedHashMap<String, EsdeMetadata> {
        val result = LinkedHashMap<String, EsdeMetadata>()
        for (game in gameElements(document)) {
            val rawPath = childText(game, "path") ?: continue
            val path = runCatching { EsdePathPolicy.normalizeGamePath(rawPath) }.getOrNull() ?: continue
            result[path] = metadataOf(game)
        }
        return result
    }

    fun apply(file: File, updates: Map<String, EsdeMetadata>): ApplyResult =
        applyWithSnapshot(file, updates).result

    /** Parse once; backup the original file only when a real change is about to be committed. */
    fun applyWithSnapshot(
        file: File,
        updates: Map<String, EsdeMetadata>,
        beforeWrite: () -> Unit = {},
    ): AppliedSnapshot {
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
        if (changed > 0) {
            beforeWrite()
            writeDocument(file, document)
        }
        return AppliedSnapshot(ApplyResult(matched, remaining.size, changed), metadataSnapshot(document))
    }

    private fun gameElements(document: Document): List<Element> {
        val gameList = document.documentElement.children().single { it.tagName == "gameList" }
        return gameList.children().filter { it.tagName == "game" }
    }

    private fun Element.children(): List<Element> = (0 until childNodes.length)
        .mapNotNull { childNodes.item(it) as? Element }

    private fun metadataOf(game: Element) = metadataFrom { childText(game, it) }
    private fun metadataFrom(value: (String) -> String?) = EsdeMetadata(
        favorite = value("favorite")?.toBooleanStrictOrNull(),
        completed = value("completed")?.toBooleanStrictOrNull(),
        playcount = value("playcount")?.toLongOrNull()?.takeIf { it >= 0 },
        playtime = value("playtime")?.toLongOrNull()?.takeIf { it >= 0 },
        lastplayed = value("lastplayed")?.takeIf(EsdeMetadataValidation::isValidLastPlayed),
        altemulator = value("altemulator"),
        players = value("players")?.takeIf(EsdeMetadataValidation::isValidPlayers),
        rating = value("rating")?.toDoubleOrNull()?.takeIf { it in 0.0..1.0 },
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
        val document = EsdeXmlFragmentReader(file, FRAGMENT_ROOT, MAX_GAMELIST_BYTES).use { builder.parse(InputSource(it)) }
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
        private val METADATA_FIELDS = setOf("path", "favorite", "completed", "playcount", "playtime", "lastplayed", "altemulator", "players", "rating")
    }
}
