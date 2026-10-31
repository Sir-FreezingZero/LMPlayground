package com.druk.lmplayground.conversation

import com.druk.lmplayground.conversation.HtmlArtifacts.Segment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [HtmlArtifacts] is pure Kotlin, so these run on the plain JVM. */
class HtmlArtifactsTest {

    private fun artifacts(text: String) = HtmlArtifacts.split(text).filterIsInstance<Segment.Artifact>()
    private fun texts(text: String) = HtmlArtifacts.split(text).filterIsInstance<Segment.Text>()

    // ── Splitting ───────────────────────────────────────────────────

    @Test
    fun `plain reply is a single text segment`() {
        val segments = HtmlArtifacts.split("Hello **world**")
        assertEquals(listOf(Segment.Text("Hello **world**")), segments)
    }

    @Test
    fun `html block becomes an artifact between text segments`() {
        val reply = "Here is your page:\n\n```html\n<h1>Hi</h1>\n<p>x</p>\n```\n\nEnjoy!"
        val segments = HtmlArtifacts.split(reply)
        assertEquals(3, segments.size)
        assertEquals(Segment.Text("Here is your page:"), segments[0])
        assertEquals(Segment.Artifact("<h1>Hi</h1>\n<p>x</p>", ordinal = 0, complete = true), segments[1])
        assertEquals(Segment.Text("Enjoy!"), segments[2])
    }

    @Test
    fun `other code blocks stay in the text with their fences`() {
        val reply = "Code:\n```kotlin\nval x = 1\n```\nDone"
        val segments = HtmlArtifacts.split(reply)
        assertEquals(1, segments.size)
        assertEquals(reply, (segments[0] as Segment.Text).text)
    }

    @Test
    fun `html inside another fenced block is not an artifact`() {
        val reply = "````markdown\n```html\n<h1>x</h1>\n<p>y</p>\n```\n````"
        assertTrue(artifacts(reply).isEmpty())
    }

    @Test
    fun `unclosed html block while streaming is an incomplete artifact`() {
        val streaming = "Sure!\n```html\n<!DOCTYPE html>\n<html><body><h1>Ti"
        val a = artifacts(streaming).single()
        assertFalse(a.complete)
        assertTrue(a.code.endsWith("<h1>Ti"))
        assertEquals("Sure!", texts(streaming).single().text)
    }

    @Test
    fun `a streaming block becomes a card once its second line arrives`() {
        assertTrue(artifacts("Building it:\n```html").isEmpty())
        assertTrue(artifacts("Building it:\n```html\n<h1>Hi</h1>").isEmpty())
        val a = artifacts("Building it:\n```html\n<h1>Hi</h1>\n<p>th").single()
        assertFalse(a.complete)
        // A streaming full document is a card from its first line.
        assertEquals(1, artifacts("```html\n<!DOCTYPE html>").size)
    }

    @Test
    fun `one line html snippets stay ordinary code blocks`() {
        val reply = "Use a line break:\n```html\n<br>\n```\nlike that."
        assertTrue(artifacts(reply).isEmpty())
        assertEquals(reply, texts(reply).single().text)
        // Blank lines don't count towards the two lines.
        assertTrue(artifacts("```html\n\n<p>x</p>\n\n```").isEmpty())
        // A complete document on one line is still a page.
        assertEquals(1, artifacts("```html\n<html><body>x</body></html>\n```").size)
    }

    @Test
    fun `ordinals count artifacts and are stable as the reply grows`() {
        val one = "```html\n<h1>a</h1>\n<p>a</p>\n```\ntext\n```html\n<h1>b</h1>\n<p>b"
        val two = "$one</p>\n```\nmore"
        assertEquals(listOf(0, 1), artifacts(one).map { it.ordinal })
        assertEquals(listOf(0, 1), artifacts(two).map { it.ordinal })
        assertTrue(artifacts(two).all { it.complete })
    }

    @Test
    fun `language tag is case insensitive and accepts htm and attributes`() {
        val page = "<h1>x</h1>\n<p>y</p>"
        assertEquals(1, artifacts("```HTML\n$page\n```").size)
        assertEquals(1, artifacts("```htm\n$page\n```").size)
        assertEquals(1, artifacts("```html title=\"index.html\"\n$page\n```").size)
        assertEquals(1, artifacts("~~~html\n$page\n~~~").size)
        assertEquals(1, artifacts("   ```html\n$page\n   ```").size)
    }

    @Test
    fun `unlabeled fence counts only when it holds a full document`() {
        assertEquals(1, artifacts("```\n<!DOCTYPE html>\n<html><body></body></html>\n```").size)
        assertEquals(1, artifacts("```\n<html lang=\"en\"></html>\n```").size)
        assertTrue(artifacts("```\n<p>just a snippet</p>\n```").isEmpty())
        assertTrue(artifacts("```\nnpm install\n```").isEmpty())
    }

    @Test
    fun `closing fence must be at least as long as the opening one`() {
        val reply = "````html\n<h1>Fences</h1>\n<pre>```</pre>\n````\nafter"
        val a = artifacts(reply).single()
        assertEquals("<h1>Fences</h1>\n<pre>```</pre>", a.code)
        assertTrue(a.complete)
    }

    // ── Preview document ────────────────────────────────────────────

    @Test
    fun `fragment is wrapped into a full page with viewport`() {
        val doc = HtmlArtifacts.buildDocument("<h1>Hi</h1>")
        assertTrue(doc.startsWith("<!DOCTYPE html>"))
        assertTrue(doc.contains("name=\"viewport\""))
        assertTrue(doc.contains("<body>\n<h1>Hi</h1>"))
    }

    @Test
    fun `full document is kept and gets charset and viewport if missing`() {
        val page = "<!DOCTYPE html><html><head><title>T</title></head><body><p>a</p></body></html>"
        val doc = HtmlArtifacts.buildDocument(page)
        assertEquals(1, Regex("<html").findAll(doc).count())
        assertTrue(doc.contains("charset=\"utf-8\""))
        assertTrue(doc.contains("name=\"viewport\""))
        assertTrue(doc.contains("<body><p>a</p></body>"))
    }

    @Test
    fun `existing viewport is not duplicated`() {
        val page = "<html><head><meta name=\"viewport\" content=\"width=device-width\"></head><body></body></html>"
        assertEquals(1, Regex("viewport").findAll(HtmlArtifacts.buildDocument(page)).count())
    }

    @Test
    fun `separate css and js blocks are folded into a single artifact`() {
        val reply = "```html\n<h1>Counter</h1>\n<button id=\"b\">Go</button>\n```\n\n```css\nbutton{color:red}\n```\n\n" +
            "```javascript\nb.onclick = () => 1;\n```"
        val a = artifacts(reply).single()
        val doc = HtmlArtifacts.previewDocument(reply, a)
        assertTrue(doc.indexOf("button{color:red}") < doc.indexOf("</head>"))
        assertTrue(doc.indexOf("<button id=\"b\">") < doc.indexOf("b.onclick"))
    }

    @Test
    fun `with several html artifacts each previews on its own`() {
        val reply = "```html\n<h1>A</h1>\n<p>a</p>\n```\n```html\n<h1>B</h1>\n<p>b</p>\n```\n```css\np{color:red}\n```"
        val list = artifacts(reply)
        assertEquals(2, list.size)
        val doc = HtmlArtifacts.previewDocument(reply, list[1])
        assertTrue(doc.contains("<p>b</p>"))
        assertFalse(doc.contains("<p>a</p>"))
        assertFalse(doc.contains("p{color:red}"))
    }

    @Test
    fun `cdn links and template strings survive untouched`() {
        val page = "<script src=\"https://cdn.tailwindcss.com\"></script>\n" +
            "<p id=\"o\"></p><script>o.textContent = `a ${'$'}{1}\\n`;</script>"
        val doc = HtmlArtifacts.buildDocument(page)
        assertTrue(doc.contains(page))
    }

    // ── Title ───────────────────────────────────────────────────────

    @Test
    fun `title comes from title tag, then h1, else blank`() {
        assertEquals("Tip & Split", HtmlArtifacts.title("<title> Tip &amp; Split </title><h1>Other</h1>"))
        assertEquals("Snake game", HtmlArtifacts.title("<h1 class=\"x\">Snake <b>game</b></h1>"))
        assertEquals("", HtmlArtifacts.title("<p>no heading</p>"))
    }

    // ── Download file name ──────────────────────────────────────────

    @Test
    fun `file name is a slug of the title with an html extension`() {
        assertEquals("interactive-example.html", HtmlArtifacts.fileName("Interactive Example"))
        assertEquals("tip-split-calculator.html", HtmlArtifacts.fileName("Tip & Split  Calculator!"))
        assertEquals("ゲーム.html", HtmlArtifacts.fileName("ゲーム"))
        assertEquals("page.html", HtmlArtifacts.fileName(""))
        assertEquals("page.html", HtmlArtifacts.fileName("!!!"))
        assertTrue(HtmlArtifacts.fileName("x".repeat(200)).length <= 55)
    }
}
