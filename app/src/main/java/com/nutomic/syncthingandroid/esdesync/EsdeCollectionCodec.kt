package com.nutomic.syncthingandroid.esdesync

import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler
import java.io.File
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

data class EsdeCollectionDefinition(val name: String, val filters: Map<String, String>)

class EsdeCollectionCodec {
    fun read(file: File): EsdeCollectionDefinition = readCandidate(file, file.nameWithoutExtension)

    internal fun readCandidate(file: File, expectedName: String): EsdeCollectionDefinition {
        require(file.isFile) { "Missing collection file" }
        require(file.length() in 1..MAX_BYTES) { "Collection file has invalid size" }
        require(file.extension.equals(EXTENSION, true)) { "Collection file must use .$EXTENSION" }
        validateName(expectedName)
        if (containsAsciiIgnoreCase(file, "<!DOCTYPE")) throw SAXException("DOCTYPE is forbidden")

        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            runCatching { isXIncludeAware = false }
            setExpandEntityReferences(false)
        }
        setFeature(factory, XMLConstants.FEATURE_SECURE_PROCESSING, true)
        setFeature(factory, "http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature(factory, "http://xml.org/sax/features/external-general-entities", false)
        setFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false)
        val document = factory.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> throw SAXException("External entities are forbidden") }
            setErrorHandler(object : DefaultHandler() {
                override fun error(error: SAXParseException) = throw error
                override fun fatalError(error: SAXParseException) = throw error
            })
        }.parse(file)
        val root = document.documentElement ?: throw SAXException("Missing filter root")
        require(root.tagName == "filter") { "Collection root must be <filter>" }
        val name = root.getAttribute("name")
        validateName(name)
        require(name == expectedName) { "Collection filename and filter name differ" }
        require(root.attributes.length == 1) { "Unsupported filter attribute" }

        val filters = linkedMapOf<String, String>()
        var child: Node? = root.firstChild
        while (child != null) {
            if (child.nodeType == Node.ELEMENT_NODE) {
                val element = child as Element
                require(element.attributes.length == 0 && element.children().isEmpty()) {
                    "Nested or attributed collection filters are unsupported"
                }
                require(element.tagName in SUPPORTED_FILTERS) { "Unsupported filter: ${element.tagName}" }
                require(element.tagName !in filters) { "Duplicate filter: ${element.tagName}" }
                val value = element.textContent.trim()
                validateFilter(element.tagName, value)
                filters[element.tagName] = value
            } else if (child.nodeType == Node.TEXT_NODE && child.textContent.isNotBlank()) {
                throw SAXException("Unexpected collection text")
            }
            child = child.nextSibling
        }
        require(filters.isNotEmpty()) { "Collection has no filters" }
        return EsdeCollectionDefinition(name, filters)
    }

    fun validateName(name: String) {
        require(name.isNotBlank() && name.length <= 160) { "Invalid collection name" }
        require(name == name.trim()) { "Collection name has surrounding whitespace" }
        require(name.none { it in INVALID_NAME_CHARS || it.isISOControl() }) { "Unsafe collection name" }
        require(name != "." && name != "..") { "Unsafe collection name" }
    }

    private fun validateFilter(key: String, value: String) {
        when (key) {
            "players" -> require(value.toIntOrNull() in 1..99) { "Invalid players filter" }
            "cheevos", "favorites" -> require(value == "TRUE" || value == "FALSE") { "Invalid boolean filter" }
            "ratings" -> {
                val match = RATING.matchEntire(value) ?: throw IllegalArgumentException("Invalid ratings filter")
                require(match.groupValues[1].toDouble() in 0.5..5.0) { "Invalid ratings filter" }
            }
        }
    }

    private fun Element.children(): List<Element> = (0 until childNodes.length)
        .mapNotNull { childNodes.item(it) as? Element }

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
                matched = if (upper == target[matched].toInt()) matched + 1
                else if (upper == target[0].toInt()) 1 else 0
                if (matched == target.size) return true
            }
        }
    }

    companion object {
        const val EXTENSION = "xcc"
        const val MAX_BYTES = 64L * 1024L
        val SUPPORTED_FILTERS = setOf("players", "cheevos", "favorites", "ratings")
        private const val INVALID_NAME_CHARS = "*\",./:;<>\\|"
        private val RATING = Regex("([0-5](?:\\.5)?) STARS?")
    }
}
