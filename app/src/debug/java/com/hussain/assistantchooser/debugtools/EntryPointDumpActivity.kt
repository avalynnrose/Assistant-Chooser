package com.hussain.assistantchooser.debugtools

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.XmlResourceParser
import android.os.Bundle
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.xmlpull.v1.XmlPullParser

private const val TARGET_PKG = "com.openai.chatgpt"
private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
private val SKIP_ATTRS = setOf("icon", "theme", "roundIcon", "screenOrientation", "configChanges",
    "windowSoftInputMode", "launchMode", "taskAffinity", "label", "resizeableActivity")

/**
 * Debug-only screen that reads ChatGPT's compiled AndroidManifest.xml and launcher-shortcut XML
 * straight from the installed app, so we can see which activities / deep links / shortcuts it
 * exposes for voice mode without needing adb or the APK.
 */
class EntryPointDumpActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val report = runCatching { buildReport(TARGET_PKG) }
            .getOrElse { "Failed: ${it.stackTraceToString()}" }

        val text = TextView(this).apply {
            this.text = report
            setTextIsSelectable(true)
            textSize = 11f
            setPadding(24, 24, 24, 24)
        }
        val share = Button(this).apply {
            this.text = "Share report"
            setOnClickListener {
                startActivity(Intent.createChooser(
                    Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_SUBJECT, "ChatGPT entry points")
                        .putExtra(Intent.EXTRA_TEXT, report),
                    "Share report",
                ))
            }
        }
        val copy = Button(this).apply {
            this.text = "Copy"
            setOnClickListener {
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("ChatGPT entry points", report))
                Toast.makeText(this@EntryPointDumpActivity, "Copied", Toast.LENGTH_SHORT).show()
            }
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(share); addView(copy)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            addView(buttons, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(ScrollView(this@EntryPointDumpActivity).apply { addView(text) },
                LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }
        setContentView(root)
    }

    private fun buildReport(pkg: String): String {
        val out = StringBuilder()
        val info = packageManager.getPackageInfo(pkg, 0)
        out.appendLine("$pkg ${info.versionName}")
        out.appendLine()

        val pkgContext = createPackageContext(pkg, 0)
        val shortcutRes = mutableListOf<Int>()

        out.appendLine("=== Manifest (activities with intent filters, or exported) ===")
        val manifestBytes = java.util.zip.ZipFile(info.applicationInfo!!.sourceDir).use { zip ->
            zip.getInputStream(zip.getEntry("AndroidManifest.xml")).readBytes()
        }
        val nodes = BinaryXml.parse(manifestBytes)
        out.appendLine("(${nodes.size} manifest elements)")
        var i = 0
        while (i < nodes.size) {
            val n = nodes[i]
            if (n.name == "activity" || n.name == "activity-alias") {
                val block = mutableListOf(n)
                var j = i + 1
                while (j < nodes.size && nodes[j].depth > n.depth) { block += nodes[j]; j++ }
                val exported = n.attrs.any { it.first == "exported" && it.second == "true" }
                if (exported || block.any { it.name == "intent-filter" }) {
                    for (b in block) {
                        out.append("  ".repeat(b.depth - n.depth)).append('<').append(b.name)
                        for ((k, v) in b.attrs) if (k !in SKIP_ATTRS) out.append(' ').append(k).append('=').append(v)
                        out.appendLine(">")
                    }
                    out.appendLine()
                }
                for (b in block) if (b.name == "meta-data" &&
                    b.attrs.any { it.first == "name" && it.second == "android.app.shortcuts" }) {
                    b.refs["resource"]?.let { shortcutRes += it }
                }
                i = j
            } else i++
        }

        out.appendLine("=== Launcher shortcuts ===")
        if (shortcutRes.isEmpty()) out.appendLine("(none declared)")
        for (id in shortcutRes) {
            out.appendLine("-- resource 0x${Integer.toHexString(id)}")
            val xml = pkgContext.resources.getXml(id)
            while (true) {
                val ev = xml.next()
                if (ev == XmlPullParser.END_DOCUMENT) break
                if (ev == XmlPullParser.START_TAG) {
                    out.append("  ".repeat(xml.depth)).append('<').append(xml.name)
                        .append(describeAttrs(xml, pkgContext)).appendLine(">")
                }
            }
            xml.close()
        }
        return out.toString()
    }

    /** Prints attributes, resolving string references (e.g. shortcut labels) where possible. */
    private fun describeAttrs(p: XmlResourceParser, ctx: Context? = null): String {
        val sb = StringBuilder()
        for (i in 0 until p.attributeCount) {
            val name = (p.getAttributeName(i) ?: "").ifEmpty {
                runCatching { resources.getResourceEntryName(p.getAttributeNameResource(i)) }
                    .getOrDefault("attr${p.getAttributeNameResource(i)}")
            }
            if (name in SKIP_ATTRS) continue
            var value = p.getAttributeValue(i) ?: ""
            if (ctx != null && value.startsWith("@")) {
                val id = p.getAttributeResourceValue(i, 0)
                runCatching { ctx.resources.getString(id) }.getOrNull()?.let { value = "\"$it\"" }
            }
            sb.append(' ').append(name).append('=').append(value)
        }
        return sb.toString()
    }
}
