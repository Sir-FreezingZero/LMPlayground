package com.druk.lmplayground.tools

import com.druk.llamacpp.tools.Tool
import com.druk.llamacpp.tools.toolError
import com.druk.llamacpp.tools.toolResult
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONObject

/**
 * Focused encyclopedic lookup: searches Wikipedia and returns the best matching
 * article's intro summary, title and URL in a single API call
 * (generator=search + extracts). More reliable than a general web search for
 * well-known topics. Network, no key, no permission.
 */
class WikipediaTool : Tool {
    override val name = "wikipedia"
    override val functionName = "search_wikipedia"
    override val description = "Looks up a well-known person, place, thing or idea on Wikipedia. Returns the article's summary, title and link."
    override val parametersSchema = """{"type":"object","properties":{"query":{"type":"string","description":"The topic, e.g. \"Eiffel Tower\""}},"required":["query"]}"""

    override fun cancelInFlight() = WebHttp.client.dispatcher.cancelAll()

    override fun execute(arguments: String): String = try {
        val query = JSONObject(arguments).optString("query").trim()
        if (query.isEmpty()) toolError("Give a topic to look up.") else lookUp(query)
    } catch (e: Exception) {
        toolError(e.message ?: "Wikipedia lookup failed")
    }

    private fun lookUp(query: String): String {
        val url = "https://en.wikipedia.org/w/api.php".toHttpUrl().newBuilder()
            .addQueryParameter("format", "json")
            .addQueryParameter("action", "query")
            .addQueryParameter("generator", "search")
            .addQueryParameter("gsrsearch", query)
            .addQueryParameter("gsrlimit", "1")
            .addQueryParameter("prop", "extracts|info")
            .addQueryParameter("exintro", "1")
            .addQueryParameter("explaintext", "1")
            .addQueryParameter("inprop", "url")
            .addQueryParameter("redirects", "1")
            .build()
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        val body = WebHttp.client.newCall(request).execute().use { WebHttp.text(it) }
        val pages = JSONObject(body).optJSONObject("query")?.optJSONObject("pages")
        val page = pages?.keys()?.asSequence()?.firstOrNull()?.let { pages.optJSONObject(it) }
        val extract = page?.optString("extract")?.trim().orEmpty()
        if (extract.isEmpty()) return toolResult("No Wikipedia article found for \"$query\".")
        return JSONObject()
            .put("title", page!!.optString("title"))
            .put("summary", if (extract.length > MAX_CHARS) extract.take(MAX_CHARS).trimEnd() + "..." else extract)
            .put("url", page.optString("fullurl"))
            .toString()
    }

    companion object {
        private const val MAX_CHARS = 1500
        const val USER_AGENT = "LMPlayground-Android/1.0 (github.com/Sir-FreezingZero/LMPlayground)"
    }
}
