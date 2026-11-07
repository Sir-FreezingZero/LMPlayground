package com.druk.lmplayground.tools

import com.druk.llamacpp.tools.Tool
import com.druk.llamacpp.tools.WebLinkStore
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.ByteArrayInputStream
import java.nio.charset.Charset

class WebFetchTool(private val linkStore: WebLinkStore? = null) : Tool {
    override val name = "web_fetch"
    override val useFor = "reading a web page from a link or a search result"
    override val description = "Fetch a web page and return its main content as markdown. Headings, lists, links and code blocks are preserved so you can read the page structure."
    override val parametersSchema = """{"type":"object","properties":{"url":{"type":"string","description":"The URL to fetch, or a reference from web_search results such as \"ddg:3\""},"max_length":{"type":"integer","description":"Maximum content length in characters (default 5000)"}},"required":["url"]}"""

    override fun cancelInFlight() {
        WebHttp.client.dispatcher.cancelAll()
    }

    override fun execute(arguments: String): String {
        return try {
            val args = JSONObject(arguments)
            val rawUrl = args.getString("url").trim()
            // A web_search result reference (e.g. "ddg:3") maps back to the real
            // URL we stored at search time. If it looks like a reference but
            // isn't known, tell the model to search first rather than fetching
            // a bogus host.
            val resolved = linkStore?.resolve(rawUrl)
            if (resolved == null && linkStore?.isReference(rawUrl) == true) {
                return WebHttp.errorJson("Unknown search reference '$rawUrl'. Call web_search first, then fetch a returned ref.")
            }
            val target = resolved ?: rawUrl
            val url = if (!target.startsWith("http")) "https://$target" else target
            val maxLength = args.optInt("max_length", 5000).coerceIn(50, 20000)

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", WebHttp.USER_AGENT)
                .get()
                .build()

            WebHttp.client.newCall(request).execute().use { response ->
                // An error page (404, 403, ...) isn't the page the model asked for.
                if (!response.isSuccessful) {
                    return WebHttp.errorJson("HTTP ${response.code} ${response.message} from $url".trim())
                }
                val contentType = response.header("Content-Type").orEmpty().lowercase()
                val isHtml = "text/html" in contentType || "application/xhtml" in contentType
                // PDFs, images, audio and other binary files would come back as
                // garbage characters, so refuse them by name.
                if (!isHtml && !isReadableText(contentType)) {
                    val kind = contentType.substringBefore(';').ifBlank { "unknown" }
                    return WebHttp.errorJson("This link is not a web page or text file ($kind), so it can't be read.")
                }

                // Read at most WebHttp.MAX_BODY_BYTES, so a huge page can't
                // fill a low-memory phone's RAM before being cut down anyway.
                val (bytes, cut) = WebHttp.readCapped(response)
                if (bytes.isEmpty()) return WebHttp.errorJson("Empty response")
                val headerCharset = response.body?.contentType()?.charset()

                val result = JSONObject()
                result.put("url", url)
                val text = if (isHtml) {
                    // A null charset lets Jsoup use the page's own <meta charset>
                    // when the server doesn't name one, so those pages don't come
                    // out garbled. `url` as baseUri makes relative links absolute.
                    val doc = Jsoup.parse(ByteArrayInputStream(bytes), headerCharset?.name(), url)
                    result.put("title", doc.title())
                    HtmlToMarkdown.convert(doc)
                } else {
                    result.put("title", "")
                    String(bytes, headerCharset ?: Charset.forName("UTF-8"))
                }
                val truncated = if (text.length > maxLength) text.substring(0, maxLength) + "..." else text
                result.put("content", truncated)
                result.put("length", truncated.length)
                if (cut) result.put("note", "Only the first 2 MB of this page was read.")
                result.toString()
            }
        } catch (e: Exception) {
            WebHttp.errorJson(e.message ?: "Fetch failed")
        }
    }

    /** Plain text, JSON, XML, CSV and the like; or no type at all, read as text. */
    private fun isReadableText(contentType: String): Boolean =
        contentType.isBlank() ||
            contentType.startsWith("text/") ||
            "json" in contentType ||
            "xml" in contentType ||
            "javascript" in contentType
}
