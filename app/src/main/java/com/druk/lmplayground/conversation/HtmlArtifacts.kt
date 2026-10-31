package com.druk.lmplayground.conversation

/**
 * Finds HTML "artifacts" in an assistant reply: the web pages a model writes
 * as Markdown code blocks (```html ... ```). The chat shows each one as an
 * artifact card with a Live preview button (see HtmlArtifactCard.kt) instead
 * of plain code text — the same flow as the big hosted assistants: the model
 * writes the page once, the user taps to run it.
 *
 * Pure Kotlin with no Android dependencies, so it is unit-tested on the JVM.
 */
object HtmlArtifacts {

    sealed interface Segment {
        /** Ordinary reply text (Markdown, including non-HTML code blocks). */
        data class Text(val text: String) : Segment

        /**
         * An HTML code block. [ordinal] counts artifacts within the reply and
         * is stable while the reply streams (new text only appends).
         * [complete] is false while the closing fence hasn't arrived yet.
         */
        data class Artifact(
            val code: String,
            val ordinal: Int,
            val complete: Boolean,
        ) : Segment
    }

    private val HTML_LANGS = setOf("html", "htm", "xhtml")
    private val CSS_LANGS = setOf("css")
    private val JS_LANGS = setOf("js", "javascript", "mjs")

    private val OPEN_FENCE = Regex("^ {0,3}(`{3,}|~{3,})[ \\t]*([^`\\s]*)[^`]*$")
    private val FULL_DOCUMENT = Regex("^\\s*(<!doctype\\s+html|<html[\\s>])", RegexOption.IGNORE_CASE)

    /**
     * One fenced code block, as line indexes into the reply. [closeLine] is
     * the closing fence's line, or the reply's line count when the block is
     * still open (the reply is streaming).
     */
    private class Fence(
        val openLine: Int,
        val closeLine: Int,
        val lang: String,
        val body: String,
        val closed: Boolean,
    ) {
        /**
         * An HTML page: an html/htm/xhtml block, or an unlabeled full
         * document. A one-line snippet (`<br>` while explaining a tag) isn't a
         * page, so it stays an ordinary code block — unless it is itself a
         * complete document.
         */
        val isHtml: Boolean
            get() {
                val fullDocument = FULL_DOCUMENT.containsMatchIn(body)
                if (lang.isEmpty()) return fullDocument
                return lang in HTML_LANGS && (fullDocument || body.lines().count { it.isNotBlank() } >= 2)
            }
    }

    /**
     * Every fenced code block in [lines], following CommonMark fence rules:
     * up to 3 spaces of indent, ``` or ~~~, and a closing fence of the same
     * character at least as long as the opening one. An unclosed fence runs
     * to the end of the reply.
     */
    private fun scanFences(lines: List<String>): List<Fence> {
        val fences = mutableListOf<Fence>()
        var i = 0
        while (i < lines.size) {
            val open = OPEN_FENCE.matchEntire(lines[i])
            if (open == null) {
                i++
                continue
            }
            val marker = open.groupValues[1]
            // ` and ~ aren't regex-special, so the marker can be used directly.
            val close = Regex("^ {0,3}${marker[0]}{${marker.length},}[ \\t]*$")
            var j = i + 1
            while (j < lines.size && !close.matches(lines[j])) j++
            fences += Fence(
                openLine = i,
                closeLine = j,
                lang = open.groupValues[2].lowercase(),
                body = lines.subList(i + 1, minOf(j, lines.size)).joinToString("\n"),
                closed = j < lines.size,
            )
            i = j + 1
        }
        return fences
    }

    /**
     * Split a reply into text and artifact segments. Each HTML code block
     * (see [Fence.isHtml]) becomes an [Segment.Artifact]; an unclosed one at
     * the end — the reply is still streaming — is marked incomplete (it turns
     * into a card once its second line arrives). Every
     * other code block stays in the text, fences included, so the Markdown
     * renderer shows it as before.
     */
    fun split(text: String): List<Segment> {
        if (!text.contains("```") && !text.contains("~~~")) return listOf(Segment.Text(text))
        val lines = text.split('\n')
        val segments = mutableListOf<Segment>()
        var textStart = 0
        var ordinal = 0

        fun addText(fromLine: Int, toLine: Int) {
            if (fromLine >= toLine) return
            val chunk = lines.subList(fromLine, toLine).joinToString("\n")
            if (chunk.isNotBlank()) segments += Segment.Text(chunk.trim('\n'))
        }

        for (fence in scanFences(lines)) {
            if (!fence.isHtml) continue
            addText(textStart, fence.openLine)
            segments += Segment.Artifact(code = fence.body, ordinal = ordinal++, complete = fence.closed)
            textStart = fence.closeLine + 1
        }
        addText(textStart, lines.size)
        return segments
    }

    /**
     * The page to run for [artifact]. When a reply holds exactly one HTML
     * artifact plus separate ```css / ```js blocks (a common way models split
     * a page), those are folded into the preview so it works as intended.
     */
    fun previewDocument(replyText: String, artifact: Segment.Artifact): String {
        val fences = scanFences(replyText.split('\n'))
        if (fences.count { it.isHtml } != 1) return buildDocument(artifact.code)
        val css = StringBuilder()
        val js = StringBuilder()
        for (fence in fences) {
            if (!fence.closed || fence.isHtml) continue
            when (fence.lang) {
                in CSS_LANGS -> css.append(fence.body).append('\n')
                in JS_LANGS -> js.append(fence.body).append('\n')
            }
        }
        return buildDocument(artifact.code, css.toString(), js.toString())
    }

    /**
     * Applied before the page's own CSS, so anything the model writes wins.
     * Only used for fragments (no <html>/<!DOCTYPE>), to keep a bare snippet
     * readable on a phone-sized preview.
     */
    private const val BASE_CSS =
        "*,*::before,*::after{box-sizing:border-box}" +
            "html{-webkit-text-size-adjust:100%}" +
            "body{margin:0;padding:16px;font-family:system-ui,-apple-system,Roboto,sans-serif;line-height:1.5;color:#1f2937;background:#fff}" +
            "img,svg,canvas,video{max-width:100%}" +
            "button,input,select,textarea{font:inherit}"

    /**
     * A complete document for the WebView. A full document gets [css] / [js]
     * spliced in (and a charset if missing); a fragment is wrapped in a page
     * with a charset, a mobile viewport and [BASE_CSS].
     */
    fun buildDocument(html: String, css: String = "", js: String = ""): String {
        val styleTag = if (css.isNotBlank()) "<style>\n${css.trim()}\n</style>\n" else ""
        val scriptTag = if (js.isNotBlank()) "<script>\n${js.trim()}\n</script>\n" else ""
        val isFullDocument = Regex("<html[\\s>]|<!doctype\\s+html", RegexOption.IGNORE_CASE).containsMatchIn(html)
        if (!isFullDocument) {
            return buildString {
                append("<!DOCTYPE html>\n<html>\n<head>\n")
                append("<meta charset=\"utf-8\">\n")
                append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
                append("<style>").append(BASE_CSS).append("</style>\n")
                append(styleTag)
                append("</head>\n<body>\n")
                append(html.trim()).append('\n')
                append(scriptTag)
                append("</body>\n</html>\n")
            }
        }
        var doc = html
        if (!Regex("<meta[^>]+charset", RegexOption.IGNORE_CASE).containsMatchIn(doc)) {
            doc = insertAfterOpenTag(doc, "head", "<meta charset=\"utf-8\">\n")
        }
        if (!Regex("<meta[^>]+name\\s*=\\s*[\"']?viewport", RegexOption.IGNORE_CASE).containsMatchIn(doc)) {
            doc = insertAfterOpenTag(doc, "head", "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
        }
        if (styleTag.isNotEmpty()) {
            doc = insertBefore(doc, "</head>", styleTag) ?: insertAfterOpenTag(doc, "head", styleTag)
        }
        if (scriptTag.isNotEmpty()) {
            doc = insertBefore(doc, "</body>", scriptTag)
                ?: insertBefore(doc, "</html>", scriptTag)
                ?: (doc + "\n" + scriptTag)
        }
        return doc
    }

    /** Insert [content] before the last [closingTag] (case-insensitive), or null if absent. */
    private fun insertBefore(doc: String, closingTag: String, content: String): String? {
        val idx = doc.lastIndexOf(closingTag, ignoreCase = true)
        if (idx < 0) return null
        return doc.substring(0, idx) + content + doc.substring(idx)
    }

    /** Insert [content] right after `<tag ...>`; creates <head> after <html> if needed. */
    private fun insertAfterOpenTag(doc: String, tag: String, content: String): String {
        Regex("<$tag(\\s[^>]*)?>", RegexOption.IGNORE_CASE).find(doc)?.let {
            return doc.substring(0, it.range.last + 1) + "\n" + content + doc.substring(it.range.last + 1)
        }
        Regex("<html(\\s[^>]*)?>", RegexOption.IGNORE_CASE).find(doc)?.let {
            return doc.substring(0, it.range.last + 1) + "\n<head>\n" + content + "</head>" +
                doc.substring(it.range.last + 1)
        }
        return content + doc
    }

    /**
     * Suggested file name for downloading a page, from its [title]:
     * "Tip & Split Calculator" -> "tip-split-calculator.html". Letters in any
     * script are kept; a blank or symbol-only title falls back to "page.html".
     */
    fun fileName(title: String): String {
        val slug = title.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), "-")
            .trim('-')
            .take(50)
            .trimEnd('-')
        return (slug.ifEmpty { "page" }) + ".html"
    }

    private val TITLE = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val H1 = Regex("<h1[^>]*>(.*?)</h1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val TAG = Regex("<[^>]+>")

    /** A short title for the card: the page's <title>, else its first <h1>, else "". */
    fun title(code: String): String {
        val raw = TITLE.find(code)?.groupValues?.get(1) ?: H1.find(code)?.groupValues?.get(1) ?: return ""
        return raw.replace(TAG, "")
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(60)
    }
}
