package com.druk.lmplayground.tools

import com.druk.llamacpp.tools.Tool
import com.druk.llamacpp.tools.WebLinkStore
import com.druk.llamacpp.tools.toolError
import com.druk.llamacpp.tools.toolResult
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup

/**
 * Searching and reading pages are one tool: with "url" it reads that page
 * (a link or a result's "ddg:N" reference), otherwise it searches
 * DuckDuckGo, falling back to Wikipedia's search when that fails.
 */
class WebSearchTool(private val linkStore: WebLinkStore? = null) : Tool {
    override val name = "web_search"
    override val description = "Search the web and fetch page content. Returns snippets for all results. Use the url parameter to fetch full page text from a specific URL."
    override val parametersSchema = """{"type":"object","properties":{"query":{"type":"string","description":"The search query"},"url":{"type":"string","description":"A URL to fetch full page content from (instead of searching). Use this to read a link the user gives or a page found in search results."}},"required":[]}"""

    private val page = WebPageReader(linkStore)

    override fun cancelInFlight() = WebHttp.client.dispatcher.cancelAll()

    override fun execute(arguments: String): String {
        val args = runCatching { JSONObject(arguments) }.getOrDefault(JSONObject())
        args.optString("url").trim().takeIf { it.isNotEmpty() }?.let { return page.read(it) }
        val query = args.optString("query").trim().ifEmpty { return toolError("No query provided.") }
        return try {
            search(query)
        } catch (e: Exception) {
            wikipedia(query) ?: toolError(e.message ?: "Search failed")
        }
    }

    private fun search(query: String): String {
        val url = "https://html.duckduckgo.com/html/".toHttpUrl().newBuilder().addQueryParameter("q", query).build()
        val request = Request.Builder().url(url).header("User-Agent", WebHttp.USER_AGENT).build()
        val (code, html) = WebHttp.client.newCall(request).execute().use {
            it.code to WebHttp.text(it)
        }
        val results = JSONArray()
        if (code in 200..299) {
            for (element in Jsoup.parse(html).select(".result.results_links")) {
                if (results.length() >= MAX_RESULTS) break
                val link = element.selectFirst(".result__title a") ?: continue
                val title = link.text().trim()
                val target = ddgTarget(link.attr("href"))
                if (title.isEmpty() || target.isEmpty()) continue
                results.put(result(title, target, element.selectFirst(".result__snippet")?.text()?.trim().orEmpty()))
            }
        }
        if (results.length() > 0) {
            return JSONObject().put("results", results).put("query", query)
                .put("note", "IMPORTANT: These are only short snippets. To get the full page content, call web_search with the url parameter.")
                .toString()
        }
        wikipedia(query)?.let { return it }
        // DuckDuckGo answers a burst of searches with HTTP 202 and a "are you a
        // bot" page; say so rather than that nothing was found.
        return when {
            code == 202 || "anomaly-modal" in html || "challenge-form" in html ->
                toolError("DuckDuckGo is temporarily blocking searches from this device (too many in a short time). Try again in a minute.")
            code !in 200..299 -> toolError("Search failed: HTTP $code")
            else -> toolResult("No results found for \"$query\".")
        }
    }

    /** A result, with a short "ddg:N" reference in place of the link to save tokens. */
    private fun result(title: String, link: String, snippet: String) =
        JSONObject().put("title", title).put("url", linkStore?.reference(link) ?: link).put("snippet", snippet)

    /**
     * Wikipedia's own search, for when DuckDuckGo fails, blocks this phone or
     * finds nothing; labelled so the model knows it isn't current web coverage.
     */
    private fun wikipedia(query: String): String? = runCatching {
        val url = "https://en.wikipedia.org/w/api.php".toHttpUrl().newBuilder()
            .addQueryParameter("action", "query").addQueryParameter("list", "search")
            .addQueryParameter("format", "json").addQueryParameter("srlimit", "$MAX_RESULTS")
            .addQueryParameter("srsearch", query).build()
        val request = Request.Builder().url(url).header("User-Agent", WikipediaTool.USER_AGENT).build()
        val body = WebHttp.client.newCall(request).execute().use { it.takeIf { r -> r.isSuccessful }?.let(WebHttp::text) }
        val hits = JSONObject(body ?: return null).getJSONObject("query").getJSONArray("search")
        if (hits.length() == 0) return null
        val results = JSONArray((0 until hits.length()).map { i ->
            val hit = hits.getJSONObject(i)
            val title = hit.getString("title")
            result(title, "https://en.wikipedia.org/wiki/" + title.replace(' ', '_'), Jsoup.parse(hit.optString("snippet")).text())
        })
        JSONObject()
            .put("note", "General web search was unavailable or returned no usable results. These are " +
                "Wikipedia-only encyclopedia results, not current web coverage. To get the full page content, call web_search with the url parameter.")
            .put("results", results).put("query", query).toString()
    }.getOrNull()

    /** The real link inside DuckDuckGo's redirect (//duckduckgo.com/l/?uddg=<link>&...). */
    private fun ddgTarget(href: String): String = when {
        "uddg=" in href -> java.net.URLDecoder.decode(href.substringAfter("uddg=").substringBefore("&"), "UTF-8")
        href.startsWith("//") -> "https:$href"
        else -> href
    }

    private companion object {
        const val MAX_RESULTS = 5
    }
}
