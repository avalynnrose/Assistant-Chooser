package com.hussain.assistantchooser.debugtools

import android.content.res.Resources
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One element from a compiled (binary) Android XML file. */
class XmlNode(val name: String, val depth: Int, val attrs: List<Pair<String, String>>, val refs: Map<String, Int>)

/**
 * Minimal reader for Android's compiled binary XML (as stored in an APK's AndroidManifest.xml).
 * Returns start tags in document order with their depth. Debug tooling only.
 */
object BinaryXml {
    fun parse(bytes: ByteArray): List<XmlNode> {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val out = mutableListOf<XmlNode>()
        var strings: List<String> = emptyList()
        var resMap = IntArray(0)
        var depth = 0
        var pos = 8 // skip RES_XML_TYPE file header
        while (pos + 8 <= bytes.size) {
            val type = buf.getShort(pos).toInt() and 0xffff
            val headerSize = buf.getShort(pos + 2).toInt() and 0xffff
            val size = buf.getInt(pos + 4)
            if (size <= 0) break
            when (type) {
                0x0001 -> strings = readStringPool(buf, pos)
                0x0180 -> resMap = IntArray((size - headerSize) / 4) { buf.getInt(pos + headerSize + it * 4) }
                0x0102 -> {
                    val ext = pos + headerSize
                    val name = strings.getOrElse(buf.getInt(ext + 4)) { "?" }
                    val attrStart = buf.getShort(ext + 8).toInt() and 0xffff
                    val attrSize = buf.getShort(ext + 10).toInt() and 0xffff
                    val attrCount = buf.getShort(ext + 12).toInt() and 0xffff
                    val attrs = mutableListOf<Pair<String, String>>()
                    val refs = mutableMapOf<String, Int>()
                    for (i in 0 until attrCount) {
                        val a = ext + attrStart + i * attrSize
                        val nameIdx = buf.getInt(a + 4)
                        var attrName = strings.getOrElse(nameIdx) { "" }
                        if (attrName.isEmpty() && nameIdx in resMap.indices) {
                            attrName = runCatching { Resources.getSystem().getResourceEntryName(resMap[nameIdx]) }
                                .getOrDefault("0x" + Integer.toHexString(resMap[nameIdx]))
                        }
                        val raw = buf.getInt(a + 8)
                        val dataType = buf.get(a + 15).toInt() and 0xff
                        val data = buf.getInt(a + 16)
                        val value = when {
                            raw != -1 -> strings.getOrElse(raw) { "?" }
                            dataType == 0x03 -> strings.getOrElse(data) { "?" }
                            dataType == 0x01 -> { refs[attrName] = data; "@0x" + Integer.toHexString(data) }
                            dataType == 0x12 -> (data != 0).toString()
                            dataType == 0x10 -> data.toString()
                            else -> "0x" + Integer.toHexString(data)
                        }
                        attrs += attrName to value
                    }
                    depth++
                    out += XmlNode(name, depth, attrs, refs)
                }
                0x0103 -> depth--
            }
            pos += size
        }
        return out
    }

    private fun readStringPool(buf: ByteBuffer, chunk: Int): List<String> {
        val count = buf.getInt(chunk + 8)
        val flags = buf.getInt(chunk + 16)
        val stringsStart = buf.getInt(chunk + 20)
        val utf8 = flags and 0x100 != 0
        val headerSize = buf.getShort(chunk + 2).toInt() and 0xffff
        return List(count) { i ->
            var p = chunk + stringsStart + buf.getInt(chunk + headerSize + i * 4)
            if (utf8) {
                // utf16 length (skip), then utf8 byte length
                p += if (buf.get(p).toInt() and 0x80 != 0) 2 else 1
                var len = buf.get(p).toInt() and 0xff
                if (len and 0x80 != 0) { len = ((len and 0x7f) shl 8) or (buf.get(p + 1).toInt() and 0xff); p += 2 } else p += 1
                String(buf.array(), p, len, Charsets.UTF_8)
            } else {
                var len = buf.getShort(p).toInt() and 0xffff
                if (len and 0x8000 != 0) { len = ((len and 0x7fff) shl 16) or (buf.getShort(p + 2).toInt() and 0xffff); p += 4 } else p += 2
                String(CharArray(len) { buf.getChar(p + it * 2) })
            }
        }
    }
}
