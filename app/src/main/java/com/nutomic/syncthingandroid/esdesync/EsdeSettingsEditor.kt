package com.nutomic.syncthingandroid.esdesync

import org.w3c.dom.Element
import org.xml.sax.SAXException
import java.io.File
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

class EsdeSettingsEditor {
    fun isLegacyGamelistLocationEnabled(file: File): Boolean {
        val document = parse(file)
        return boolElements(document.documentElement)
            .firstOrNull { it.getAttribute("name") == LEGACY_SETTING }
            ?.getAttribute("value")
            ?.equals("true", ignoreCase = true) == true
    }

    fun enableLegacyGamelistLocation(file: File): Boolean {
        val document = parse(file)
        val root = document.documentElement ?: throw SAXException("Missing ES-DE settings root")
        val existing = boolElements(root).firstOrNull { it.getAttribute("name") == LEGACY_SETTING }
        if (existing?.getAttribute("value")?.equals("true", ignoreCase = true) == true) return false
        val target = existing ?: document.createElement("bool").also {
            it.setAttribute("name", LEGACY_SETTING)
            root.appendChild(it)
        }
        target.setAttribute("value", "true")
        AtomicFileWriter.write(file) { output ->
            val factory = TransformerFactory.newInstance()
            runCatching { factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
            factory.newTransformer().apply {
                setOutputProperty(OutputKeys.ENCODING, "UTF-8")
                setOutputProperty(OutputKeys.INDENT, "yes")
                setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no")
            }.transform(DOMSource(document), StreamResult(output))
        }
        return true
    }

    private fun parse(file: File) = run {
        require(file.isFile) { "Missing ES-DE settings file: ${file.path}" }
        require(file.length() <= MAX_SETTINGS_BYTES) { "ES-DE settings file is too large" }
        if (containsAsciiIgnoreCase(file, "<!DOCTYPE")) throw SAXException("DOCTYPE is forbidden in es_settings.xml")
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        runCatching { factory.isXIncludeAware = false }
        factory.setExpandEntityReferences(false)
        setFeature(factory, XMLConstants.FEATURE_SECURE_PROCESSING, true)
        setFeature(factory, "http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature(factory, "http://xml.org/sax/features/external-general-entities", false)
        setFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false)
        factory.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> throw SAXException("External entities are forbidden") }
        }.parse(file)
    }

    private fun boolElements(root: Element): List<Element> {
        val nodes = root.getElementsByTagName("bool")
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
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
    }
}
