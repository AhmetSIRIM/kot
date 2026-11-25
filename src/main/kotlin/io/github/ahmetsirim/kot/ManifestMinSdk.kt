package io.github.ahmetsirim.kot

/**
 * Finds the minSdkVersion an Android manifest records on its uses-sdk element.
 *
 * The manifest is scanned as text, not parsed: comments and CDATA sections are dropped, the
 * first uses-sdk element is taken, and its android:minSdkVersion attribute is read. A scan
 * cannot fail on a manifest that is not well-formed, which matters because the manifest is read
 * whether or not the minSdk floor is declared; with the floor declared, a value that is not
 * found fails the task. Every step is one pass over the text.
 */
internal object ManifestMinSdk {

    private const val USES_SDK_TAG_START = "<uses-sdk"

    // What a manifest holds that is not markup: comments and CDATA sections.
    private val nonMarkupSpans: List<Pair<String, String>> = listOf("<!--" to "-->", "<![CDATA[" to "]]>")

    // Applied to one start tag only. The closing quote has to be the opening one.
    private val minSdkAttributePattern = Regex("""\bandroid:minSdkVersion\s*=\s*(["'])(.*?)\1""")

    /** The minSdkVersion [manifest] records, as written; null when it records none. */
    fun recordedIn(manifest: String): String? {
        val markup: String = nonMarkupSpans.fold(manifest) { text: String, span: Pair<String, String> ->
            withoutSpans(text = text, opening = span.first, closing = span.second)
        }
        return usesSdkStartTag(markup = markup)
            ?.let { startTag: String -> minSdkAttributePattern.find(startTag)?.groupValues?.get(2) }
    }

    /** [text] with every span from [opening] to the next [closing] removed; an unclosed span runs to the end. */
    private fun withoutSpans(text: String, opening: String, closing: String): String {
        val kept = StringBuilder()
        var position = 0
        while (position < text.length) {
            val spanStart: Int = text.indexOf(opening, startIndex = position)
            if (spanStart < 0) {
                kept.append(text, position, text.length)
                break
            }
            kept.append(text, position, spanStart)
            val spanEnd: Int = text.indexOf(closing, startIndex = spanStart + opening.length)
            position = if (spanEnd < 0) text.length else spanEnd + closing.length
        }
        return kept.toString()
    }

    /**
     * The first uses-sdk start tag in [markup], up to its closing bracket; null when there is
     * none or it is never closed. The character after the name has to end the name, so an
     * element whose name only starts the same way is passed over.
     */
    private fun usesSdkStartTag(markup: String): String? {
        var position: Int = markup.indexOf(USES_SDK_TAG_START)
        while (position >= 0) {
            val afterName: Char? = markup.getOrNull(position + USES_SDK_TAG_START.length)
            val nameEndsHere: Boolean = afterName != null && (afterName.isWhitespace() || afterName in "/>")
            if (nameEndsHere) {
                val tagEnd: Int = markup.indexOf('>', startIndex = position)
                return if (tagEnd < 0) null else markup.substring(position, tagEnd + 1)
            }
            position = markup.indexOf(USES_SDK_TAG_START, startIndex = position + 1)
        }
        return null
    }
}
