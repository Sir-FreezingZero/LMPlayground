package com.druk.lmplayground.conversation

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.util.Log
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

private const val TAG = "HtmlPreviewWebView"

/**
 * Base URL for previewed pages. It gives the page a real (https) origin so
 * localStorage, fetch() with CORS and friends work, while the reserved
 * `.invalid` TLD guarantees it can never resolve to a real server.
 */
private const val PREVIEW_HOST = "preview.lmplayground.invalid"
private const val PREVIEW_BASE_URL = "https://$PREVIEW_HOST/"

/**
 * The live preview of an HTML artifact. Whether the page may use the internet
 * comes from Settings → General ([HtmlPreviewInternet], off by default) and is
 * read when the preview opens. While it's off,
 * [onBlockedRequest] fires (on the main thread) the first time the page tries
 * to load something from the internet, so the card can explain why the page
 * may look incomplete.
 */
@Composable
internal fun HtmlWebView(
    html: String,
    captureTouches: Boolean,
    modifier: Modifier = Modifier,
    onBlockedRequest: () -> Unit = {},
) {
    val context = LocalContext.current
    val allowInternet = remember { HtmlPreviewInternet.isEnabled(context) }
    val currentOnBlocked by rememberUpdatedState(onBlockedRequest)
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            createPreviewWebView(ctx, captureTouches, allowInternet) { currentOnBlocked() }.also {
                // Offline: make sure nothing from an online session lingers
                // before the page loads (see HtmlPreviewInternet).
                if (!allowInternet) HtmlPreviewInternet.ensureOfflineClean(ctx, it)
            }
        },
        update = { webView ->
            // Only (re)load when the page actually changed; recomposition
            // alone must not restart a running game or animation.
            if (webView.tag != html) {
                webView.tag = html
                webView.loadDataWithBaseURL(PREVIEW_BASE_URL, html, "text/html", "utf-8", null)
            }
        },
        onRelease = { webView ->
            webView.stopLoading()
            webView.destroy()
        },
    )
}

/**
 * A sandboxed WebView for model-written pages:
 * - JavaScript on (the point of a live preview), but no JS bridge to the app.
 * - Internet only when [allowInternet]: then pages can use CDN libraries,
 *   web fonts, remote images and public APIs, with normal HTTP caching.
 *   Otherwise network loads are blocked twice over (blockNetworkLoads, and
 *   every http(s) request intercepted and refused) and [onBlocked] reports it.
 * - No file/content access, no geolocation, no popups.
 * - The preview never navigates away from its page. A link the user taps
 *   opens in their browser; script- or redirect-driven navigation is refused,
 *   so a page can't silently replace itself with another site.
 */
@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
private fun createPreviewWebView(
    context: Context,
    captureTouches: Boolean,
    allowInternet: Boolean,
    onBlocked: () -> Unit,
): WebView =
    WebView(context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        setBackgroundColor(Color.WHITE)
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            blockNetworkLoads = !allowInternet
            cacheMode = if (allowInternet) WebSettings.LOAD_DEFAULT else WebSettings.LOAD_NO_CACHE
            // The page's origin is https; still show http:// images and
            // media that models often link to.
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            setGeolocationEnabled(false)
            mediaPlaybackRequiresUserGesture = true
        }
        webViewClient = object : WebViewClient() {
            private var reportedBlock = false

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean {
                val url = request.url
                // In-page anchors (#section) just scroll; let them through.
                if (url.host == PREVIEW_HOST && url.fragment != null) return false
                val scheme = url.scheme?.lowercase()
                if (request.hasGesture() && (scheme == "http" || scheme == "https")) {
                    openLinkInBrowser(view.context, url)
                }
                return true
            }

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? {
                if (allowInternet) return null
                val scheme = request.url.scheme?.lowercase()
                if (scheme != "http" && scheme != "https") return null
                // Runs on a WebView background thread: report once, on main.
                if (!reportedBlock) {
                    reportedBlock = true
                    view.post { onBlocked() }
                }
                return WebResourceResponse(
                    "text/plain",
                    "utf-8",
                    403,
                    "Internet access is off for previews",
                    emptyMap(),
                    ByteArrayInputStream(ByteArray(0)),
                )
            }
        }
        // A WebChromeClient enables alert()/confirm()/prompt() dialogs, which
        // generated games and demos commonly rely on.
        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                Log.d(
                    TAG,
                    "${consoleMessage.messageLevel()}: ${consoleMessage.message()} " +
                        "(line ${consoleMessage.lineNumber()})",
                )
                return true
            }
        }
        if (captureTouches) {
            setOnTouchListener { view, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                }
                false
            }
        }
    }

/**
 * Open [html] in the user's browser. The page is served by [LocalPageServer]
 * at an http://127.0.0.1 address, which every browser opens (unlike a local
 * file, which many browsers refuse). The browser is a separate app, so the
 * preview's internet setting doesn't apply there. Returns false when the page
 * couldn't be served or no browser is installed.
 */
internal suspend fun openPageInBrowser(context: Context, html: String, fileName: String): Boolean {
    val url = try {
        withContext(Dispatchers.IO) { LocalPageServer.publish(html, fileName) }
    } catch (e: Exception) {
        Log.w(TAG, "Couldn't serve the page for the browser", e)
        return false
    }
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        .addCategory(Intent.CATEGORY_BROWSABLE)
    return try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "No browser to open $url", e)
        false
    }
}

/** Open a link the user tapped inside a preview in their default browser. */
private fun openLinkInBrowser(context: Context, uri: Uri) {
    val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "No app to open $uri", e)
    }
}
