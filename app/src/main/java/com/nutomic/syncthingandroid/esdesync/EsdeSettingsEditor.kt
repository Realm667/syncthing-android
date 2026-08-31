package com.nutomic.syncthingandroid.esdesync

import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.SAXException
import org.xml.sax.InputSource
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler
import java.io.File
import java.io.OutputStreamWriter
import java.io.StringReader
import javax.xml.XMLConstants
import org.w3c.dom.Document
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

class EsdeSettingsEditor {
    data class XmlSetting(val type: String, val value: String)

    fun read(file: File, names: Set<String>): Map<String, XmlSetting> {
        val parsed = parse(file)
        val result = linkedMapOf<String, XmlSetting>()
        settingElements(parsed.container).forEach { element ->
            val name = element.getAttribute("name")
            if (name in names && element.tagName in SETTING_TYPES && element.hasAttribute("value")) {
                require(name !in result) { "Duplicate ES-DE setting: $name" }
                result[name] = XmlSetting(element.tagName, element.getAttribute("value"))
            }
        }
        return result
    }

    fun apply(file: File, values: Map<String, XmlSetting>): Int {
        if (values.isEmpty()) return 0
        val parsed = parse(file)
        var changed = 0
        values.forEach { (name, value) ->
            require(value.type in SETTING_TYPES) { "Unsupported ES-DE setting type" }
            val matches = settingElements(parsed.container).filter { it.getAttribute("name") == name }
            require(matches.size <= 1) { "Duplicate ES-DE setting: $name" }
            val existing = matches.firstOrNull()
            if (existing?.tagName == value.type && existing.getAttribute("value") == value.value) return@forEach
            if (existing != null && existing.tagName != value.type) {
                existing.parentNode.removeChild(existing)
            }
            val target = existing?.takeIf { it.tagName == value.type } ?: parsed.document.createElement(value.type).also {
                it.setAttribute("name", name)
                parsed.container.appendChild(it)
            }
            target.setAttribute("value", value.value)
            changed++
        }
        if (changed > 0) write(file, parsed)
        return changed
    }

    fun mergeCommaSeparated(file: File, name: String, additions: Set<String>): Int {
        val current = read(file, setOf(name))[name]
        val values = current?.value?.split(',')?.map { it.trim() }?.filter { it.isNotBlank() }?.toMutableList()
            ?: mutableListOf()
        val oldSize = values.size
        additions.sorted().forEach { if (it !in values) values += it }
        if (values.size == oldSize) return 0
        return apply(file, mapOf(name to XmlSetting("string", values.joinToString(","))))
    }

    fun isLegacyGamelistLocationEnabled(file: File): Boolean {
        val parsed = parse(file)
        return boolElements(parsed.container)
            .firstOrNull { it.getAttribute("name") == LEGACY_SETTING }
            ?.getAttribute("value")
            ?.equals("true", ignoreCase = true) == true
    }

    fun enableLegacyGamelistLocation(file: File): Boolean {
        val parsed = parse(file)
        val existing = boolElements(parsed.container).firstOrNull { it.getAttribute("name") == LEGACY_SETTING }
        if (existing?.getAttribute("value")?.equals("true", ignoreCase = true) == true) return false
        val target = existing ?: parsed.document.createElement("bool").also {
            it.setAttribute("name", LEGACY_SETTING)
            parsed.container.appendChild(it)
        }
        target.setAttribute("value", "true")
        write(file, parsed)
        return true
    }

    private fun parse(file: File): ParsedSettings {
        require(file.isFile) { "Missing ES-DE settings file: ${file.path}" }
        require(file.length() <= MAX_SETTINGS_BYTES) { "ES-DE settings file is too large" }
        if (containsAsciiIgnoreCase(file, "<!DOCTYPE")) throw SAXException("DOCTYPE is forbidden in es_settings.xml")
        val source = file.readText(Charsets.UTF_8)
            .removePrefix("\uFEFF")
            .replaceFirst(XML_DECLARATION, "")
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        runCatching { factory.isXIncludeAware = false }
        factory.setExpandEntityReferences(false)
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
        }.parse(InputSource(StringReader("<$WRAPPER_TAG>$source</$WRAPPER_TAG>")))
        val wrapper = document.documentElement ?: throw SAXException("Missing ES-DE settings content")
        val topLevelElements = (0 until wrapper.childNodes.length)
            .mapNotNull { wrapper.childNodes.item(it) as? Element }
        if (topLevelElements.isEmpty()) throw SAXException("Missing ES-DE settings content")
        val fragment = topLevelElements.size != 1
        return ParsedSettings(document, if (fragment) wrapper else topLevelElements.single(), fragment)
    }

    private fun write(file: File, parsed: ParsedSettings) {
        val factory = TransformerFactory.newInstance()
        runCatching { factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
        val transformer = factory.newTransformer().apply {
            setOutputProperty(OutputKeys.ENCODING, "UTF-8")
            setOutputProperty(OutputKeys.INDENT, "yes")
            setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, if (parsed.fragment) "yes" else "no")
        }
        AtomicFileWriter.write(file) { output ->
            if (!parsed.fragment) {
                transformer.transform(DOMSource(parsed.container), StreamResult(output))
                return@write
            }
            val writer = OutputStreamWriter(output, Charsets.UTF_8)
            writer.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            val nodes = parsed.container.childNodes
            var wroteNode = false
            for (index in 0 until nodes.length) {
                val node = nodes.item(index)
                if (node.nodeType !in SERIALIZED_NODE_TYPES) continue
                if (wroteNode) writer.append('\n')
                transformer.transform(DOMSource(node), StreamResult(writer))
                wroteNode = true
            }
            writer.append('\n')
            writer.flush()
        }
    }

    private fun boolElements(root: Element): List<Element> {
        val nodes = root.getElementsByTagName("bool")
        val descendants = (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
        return if (root.tagName == "bool") listOf(root) + descendants else descendants
    }

    private fun settingElements(root: Element): List<Element> {
        val result = mutableListOf<Element>()
        if (root.tagName in SETTING_TYPES) result += root
        SETTING_TYPES.forEach { type ->
            val nodes = root.getElementsByTagName(type)
            result += (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
        }
        return result.distinct()
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
                matched = if (upper == target[matched].toInt()) matched + 1
                    else if (upper == target[0].toInt()) 1 else 0
                if (matched == target.size) return true
            }
        }
    }

    companion object {
        const val LEGACY_SETTING = "LegacyGamelistFileLocation"
        const val MAX_SETTINGS_BYTES = 2L * 1024L * 1024L
        private const val WRAPPER_TAG = "esde-settings-fragment"
        private val SETTING_TYPES = setOf("bool", "int", "float", "string")
        private val XML_DECLARATION = Regex("^\\s*<\\?xml[^?]*\\?>", RegexOption.IGNORE_CASE)
        private val SERIALIZED_NODE_TYPES = setOf(
            Node.ELEMENT_NODE,
            Node.COMMENT_NODE,
            Node.PROCESSING_INSTRUCTION_NODE,
        )
    }

    private data class ParsedSettings(
        val document: Document,
        val container: Element,
        val fragment: Boolean,
    )
}
