package com.druk.lmplayground.tools

import com.druk.llamacpp.tools.Tool
import com.druk.llamacpp.tools.WebLinkStore
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup

class WebSearchTool(private val linkStore: WebLinkStore? = null) : Tool {
    override val name = "web_search"
    override val useFor = "news and current information"
    override val description = "Search the web and return results with titles, snippets, and a compact reference for each result. Pass a result's \"ref\" to web_fetch to read that page."
    override val parametersSchema = """{"type":"object","properties":{"query":{"type":"string","description":"The search query"},"max_results":{"type":"integer","description":"Maximum number of results to return (default 5, max 10)"}},"required":["query"]}"""

    override fun cancelInFlight() {
        WebHttp.client.dispatcher.cancelAll()
    }

    override fun execute(arguments: String): String {
        return try {
            val args = JSONObject(arguments)
            val query = args.getString("query")
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
            if (code !in 200..299) return WebHttp.errorJson("Search failed: HTTP $code")
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
                    // URL to save context tokens; web_fetch resolves it back.
                    obj.put("ref", linkStore.reference(actualUrl))
                } else {
                    obj.put("url", actualUrl)
                }
                obj.put("snippet", snippet)
                results.put(obj)
            }

            if (results.length() == 0 && looksBlocked) {
                return WebHttp.errorJson("DuckDuckGo is temporarily blocking searches from this device (too many in a short time). Try again in a minute.")
            }
            val wrapper = JSONObject()
            wrapper.put("results", results)
            wrapper.put("query", query)
            if (results.length() == 0) wrapper.put("message", "No results found")
            wrapper.toString()
        } catch (e: Exception) {
            WebHttp.errorJson(e.message ?: "Search failed")
        }
    }

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
