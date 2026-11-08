package com.druk.lmplayground.tools

import com.druk.llamacpp.tools.toolError
import com.druk.llamacpp.tools.WebLinkStore
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.ByteArrayInputStream

/**
 * Reads a page for web_search's url input: its title and main text as
 * Markdown, cut to [WebHttp.pageChars].
 */
internal class WebPageReader(private val linkStore: WebLinkStore? = null) {

    fun read(rawUrl: String): String = try {
        // A web_search reference ("ddg:3") maps back to the link stored at search
        // time; an unknown one gets an error rather than a fetch of a bogus host.
        val resolved = linkStore?.resolve(rawUrl)
        if (resolved == null && linkStore?.isReference(rawUrl) == true) {
            toolError("Unknown search reference '$rawUrl'. Call web_search with a query first, then pass a returned url.")
        } else {
            fetch((resolved ?: rawUrl).let { if (it.startsWith("http")) it else "https://$it" })
        }
    } catch (e: Exception) {
        toolError(e.message ?: "Fetch failed")
    }

    private fun fetch(url: String): String {
        val request = Request.Builder().url(url).header("User-Agent", WebHttp.USER_AGENT).build()
        return WebHttp.client.newCall(request).execute().use { response ->
            // An error page (404, 403, ...) isn't the page the model asked for.
            if (!response.isSuccessful) return toolError("HTTP ${response.code} ${response.message} from $url".trim())
            val contentType = response.header("Content-Type").orEmpty().lowercase()
            val isHtml = "text/html" in contentType || "application/xhtml" in contentType
            // PDFs, images and other binary files would come back as garbage.
            if (!isHtml && !isReadableText(contentType)) {
                return toolError("This link is not a web page or text file (${contentType.substringBefore(';').ifBlank { "unknown" }}), so it can't be read.")
            }
            // At most WebHttp.MAX_BODY_BYTES, so a huge page can't fill a small phone's memory.
            val bytes = WebHttp.bytes(response)
            if (bytes.isEmpty()) return toolError("Empty response")
            val charset = response.body?.contentType()?.charset()
            // A null charset lets Jsoup use the page's own <meta charset>; `url`
            // as the base makes relative links absolute.
            val doc = if (isHtml) Jsoup.parse(ByteArrayInputStream(bytes), charset?.name(), url) else null
            val text = doc?.let { HtmlToMarkdown.convert(it) } ?: String(bytes, charset ?: Charsets.UTF_8)
            val max = WebHttp.pageChars()
            JSONObject().put("url", url).put("title", doc?.title().orEmpty())
                .put("content", if (text.length > max) text.take(max) + "\n\n[Page cut here to fit the conversation.]" else text)
                .toString()
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
