package com.bulat.whis

import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object EpubExporter {
    fun write(
        output: OutputStream,
        title: String,
        language: String,
        text: String,
    ) {
        ZipOutputStream(output.buffered()).use { zip ->
            val mime = "application/epub+zip".toByteArray(StandardCharsets.US_ASCII)
            val crc = CRC32().apply { update(mime) }
            val mimetypeEntry = ZipEntry("mimetype").apply {
                method = ZipEntry.STORED
                size = mime.size.toLong()
                compressedSize = mime.size.toLong()
                this.crc = crc.value
            }
            zip.putNextEntry(mimetypeEntry)
            zip.write(mime)
            zip.closeEntry()

            putText(zip, "META-INF/container.xml", containerXml())
            putText(zip, "OEBPS/package.opf", packageOpf(title, language))
            putText(zip, "OEBPS/nav.xhtml", navXhtml())
            putText(zip, "OEBPS/content.xhtml", contentXhtml(title, language, text))
        }
    }

    private fun putText(zip: ZipOutputStream, path: String, content: String) {
        zip.putNextEntry(ZipEntry(path))
        zip.write(content.toByteArray(StandardCharsets.UTF_8))
        zip.closeEntry()
    }

    private fun containerXml(): String = """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/package.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>
"""

    private fun packageOpf(title: String, language: String): String {
        val identifier = "urn:uuid:${UUID.randomUUID()}"
        return """<?xml version="1.0" encoding="UTF-8"?>
<package version="3.0" unique-identifier="book-id"
    xmlns="http://www.idpf.org/2007/opf">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="book-id">${escapeXml(identifier)}</dc:identifier>
    <dc:title>${escapeXml(title)}</dc:title>
    <dc:language>${escapeXml(language.ifBlank { "und" })}</dc:language>
    <meta property="dcterms:modified">2026-09-18T00:00:00Z</meta>
  </metadata>
  <manifest>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
    <item id="content" href="content.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine>
    <itemref idref="content"/>
  </spine>
</package>
"""
    }

    private fun navXhtml(): String = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml">
<head><title>Contents</title></head>
<body>
<nav xmlns:epub="http://www.idpf.org/2007/ops" epub:type="toc">
  <ol><li><a href="content.xhtml">Transcript</a></li></ol>
</nav>
</body>
</html>
"""

    private fun contentXhtml(title: String, language: String, text: String): String {
        val paragraphs = text
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .split(Regex("\\n\\s*\\n"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n") { paragraph ->
                "<p>${escapeXml(paragraph).replace("\n", "<br/>")}</p>"
            }

        return """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="${escapeXml(language.ifBlank { "und" })}">
<head>
  <meta charset="utf-8"/>
  <title>${escapeXml(title)}</title>
  <style>
    body { font-family: sans-serif; line-height: 1.55; margin: 5%; }
    h1 { font-size: 1.4em; }
    p { margin: 0 0 0.85em 0; }
  </style>
</head>
<body>
<h1>${escapeXml(title)}</h1>
$paragraphs
</body>
</html>
"""
    }

    private fun escapeXml(value: String): String = buildString(value.length) {
        value.forEach { ch ->
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(ch)
            }
        }
    }
}
