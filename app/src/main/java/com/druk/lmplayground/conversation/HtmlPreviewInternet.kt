package com.druk.lmplayground.conversation

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import com.druk.lmplayground.storage.StoragePreferences

/**
 * The "Internet access in previews" setting, plus the promise behind turning
 * it off: previews then look as if they never had internet access.
 *
 * While access is on, previews download libraries, fonts and images into the
 * WebView's HTTP cache, and pages may keep data in localStorage, IndexedDB or
 * cookies. Turning access off deletes all of it. Live previews are the only
 * WebView in the app, so clearing the app's WebView data touches nothing else.
 *
 * [StoragePreferences.htmlPreviewOfflineClean] records that this cleanup has
 * run since access was last on. It starts false, so data left by builds where
 * previews were always online is also cleared, once, by [ensureOfflineClean]
 * when the first preview opens with access off.
 *
 * Every call must be on the main thread (WebView APIs require it).
 */
object HtmlPreviewInternet {

    fun isEnabled(context: Context): Boolean = StoragePreferences(context).htmlPreviewInternet

    /** Turn access on or off. Turning it off deletes everything previews stored. */
    fun setEnabled(context: Context, enabled: Boolean) {
        val prefs = StoragePreferences(context)
        prefs.htmlPreviewInternet = enabled
        if (enabled) {
            prefs.htmlPreviewOfflineClean = false
        } else {
            clearPreviewData(context, prefs, webView = null)
        }
    }

    /**
     * Before a preview loads with access off: if online data may still be
     * around, delete it first. [webView] (the preview about to load) is used
     * to clear the HTTP cache, saving a throwaway WebView.
     */
    fun ensureOfflineClean(context: Context, webView: WebView) {
        val prefs = StoragePreferences(context)
        if (!prefs.htmlPreviewInternet && !prefs.htmlPreviewOfflineClean) {
            clearPreviewData(context, prefs, webView)
        }
    }

    private fun clearPreviewData(context: Context, prefs: StoragePreferences, webView: WebView?) {
        // HTTP cache (downloaded libraries, fonts, images). It is shared by
        // every WebView in the app, so any instance can clear it.
        if (webView != null) {
            webView.clearCache(true)
        } else {
            WebView(context).apply {
                clearCache(true)
                destroy()
            }
        }
        // localStorage, sessionStorage, IndexedDB, Cache Storage.
        WebStorage.getInstance().deleteAllData()
        CookieManager.getInstance().apply {
            removeAllCookies(null)
            flush()
        }
        prefs.htmlPreviewOfflineClean = true
    }
}
