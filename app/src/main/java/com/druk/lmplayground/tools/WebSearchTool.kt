package com.druk.lmplayground.tools

import com.druk.llamacpp.tools.Tool
import com.druk.llamacpp.tools.WebLinkStore
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup

/**
 * Searching and reading pages are one tool: with "url" it reads that page
 * (a link or a result's "ddg:N" reference), otherwise it searches.
 */
class WebSearchTool(private val linkStore: WebLinkStore? = null) : Tool {
    override val name = "web_search"
    override val description = "Search the web and fetch page content. Returns snippets for all results. Use the url parameter to fetch full page text from a specific URL."
    override val parametersSchema = """{"type":"object","properties":{"query":{"type":"string","description":"The search query"},"url":{"type":"string","description":"A URL to fetch full page content from (instead of searching). Use this to read a page found in search results."}},"required":[]}"""

    private val page = WebFetchTool(linkStore)

    override fun cancelInFlight() {
        WebHttp.client.dispatcher.cancelAll()
    }

    override fun execute(arguments: String): String {
        return try {
            val args = JSONObject(arguments)
            if (args.optString("url").isNotBlank()) return page.execute(arguments)
            val query = args.optString("query").ifBlank { return WebHttp.errorJson("No query provided.") }
            val maxResults = args.optInt("max_results", 5).coerceIn(1, 10)

            val url = "https://html.duckduckgo.com/html/"
                .toHttpUrlOrNull()!!
                .newBuilder()
                .addQueryParameter("q", query)
                .build()

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", WebHttp.USER_AGENT)
                .get()
                .build()

            val (code, html) = WebHttp.client.newCall(request).execute().use { response ->
                response.code to String(WebHttp.readCapped(response).first, Charsets.UTF_8)
            }
            // DuckDuckGo answers a burst of searches with HTTP 202 and a
            // "are you a bot" page that has no results. Say so, rather than
            // telling the model nothing was found.
            val looksBlocked = code == 202 || "anomaly-modal" in html || "challenge-form" in html
            if (code !in 200..299) return wikipedia(query, maxResults) ?: WebHttp.errorJson("Search failed: HTTP $code")
            if (html.isEmpty()) return WebHttp.errorJson("Empty response")

            val doc = Jsoup.parse(html)
            val results = JSONArray()

            for (element in doc.select(".result.results_links")) {
                if (results.length() >= maxResults) break
                val titleEl = element.selectFirst(".result__title a") ?: continue
                val snippetEl = element.selectFirst(".result__snippet")
                val title = titleEl.text().trim()
                val href = titleEl.attr("href")
                val snippet = snippetEl?.text()?.trim() ?: ""

                // DuckDuckGo wraps URLs in a redirect — extract the actual URL
                val actualUrl = extractDdgUrl(href)
                if (title.isEmpty() || actualUrl.isEmpty()) continue

                val obj = JSONObject()
                obj.put("title", title)
                if (linkStore != null) {
                    // Hand the model a compact reference instead of the full
                    // URL to save context tokens; the url parameter resolves it back.
                    obj.put("url", linkStore.reference(actualUrl))
                } else {
                    obj.put("url", actualUrl)
                }
                obj.put("snippet", snippet)
                results.put(obj)
            }

            if (results.length() == 0) wikipedia(query, maxResults)?.let { return it }
            if (results.length() == 0 && looksBlocked) {
                return WebHttp.errorJson("DuckDuckGo is temporarily blocking searches from this device (too many in a short time). Try again in a minute.")
            }
            val wrapper = JSONObject()
            wrapper.put("results", results)
            wrapper.put("query", query)
            if (results.length() == 0) wrapper.put("message", "No results found.")
            // Small models tend to answer from a snippet; remind them the page itself is there to read.
            else wrapper.put("note", "IMPORTANT: These are only short snippets. To get the full page content, call web_search with the url parameter.")
            wrapper.toString()
        } catch (e: Exception) {
            wikipedia(args = arguments) ?: WebHttp.errorJson(e.message ?: "Search failed")
        }
    }

    private fun wikipedia(args: String): String? =
        runCatching { JSONObject(args).getString("query") }.getOrNull()?.let { wikipedia(it, 5) }

    /**
     * When DuckDuckGo fails, blocks this phone or finds nothing: Wikipedia's own
     * search, labelled so the model knows it isn't current web coverage.
     */
    private fun wikipedia(query: String, maxResults: Int): String? = runCatching {
        val url = "https://en.wikipedia.org/w/api.php".toHttpUrlOrNull()!!.newBuilder()
            .addQueryParameter("action", "query").addQueryParameter("list", "search")
            .addQueryParameter("format", "json").addQueryParameter("srlimit", "$maxResults")
            .addQueryParameter("srsearch", query).build()
        val request = Request.Builder().url(url).header("User-Agent", WikipediaTool.USER_AGENT).build()
        val body = WebHttp.client.newCall(request).execute().use { String(WebHttp.readCapped(it).first, Charsets.UTF_8) }
        val hits = JSONObject(body).getJSONObject("query").getJSONArray("search")
        if (hits.length() == 0) return null
        val results = JSONArray()
        for (i in 0 until hits.length()) {
            val title = hits.getJSONObject(i).getString("title")
            val page = "https://en.wikipedia.org/wiki/" + title.replace(' ', '_')
            results.put(
                JSONObject().put("title", title)
                    .put("url", linkStore?.reference(page) ?: page)
                    .put("snippet", Jsoup.parse(hits.getJSONObject(i).optString("snippet")).text())
            )
        }
        JSONObject()
            .put("note", "General web search was unavailable or returned no usable results. These are " +
                "Wikipedia-only encyclopedia results, not current web coverage. To get the full page content, call web_search with the url parameter.")
            .put("results", results).put("query", query).toString()
    }.getOrNull()

    private fun extractDdgUrl(href: String): String {
        // DuckDuckGo links look like //duckduckgo.com/l/?uddg=<encoded_url>&...
        if (href.contains("uddg=")) {
            val encoded = href.substringAfter("uddg=").substringBefore("&")
            return java.net.URLDecoder.decode(encoded, "UTF-8")
        }
        // Direct link
        if (href.startsWith("http")) return href
        if (href.startsWith("//")) return "https:$href"
        return href
    }
}
