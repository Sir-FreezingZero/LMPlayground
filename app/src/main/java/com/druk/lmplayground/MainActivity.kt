package com.druk.lmplayground

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import android.view.View
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.navOptions
import com.druk.lmplayground.theme.PlaygroundTheme
import com.druk.lmplayground.ui.AppTab
import com.druk.lmplayground.ui.BottomTabBar
import com.druk.lmplayground.storage.StorageDocuments

class MainActivity : AppCompatActivity() {

    private var folderPickerCallback: ((Uri?) -> Unit)? = null

    val folderPickerLauncher: ActivityResultLauncher<Uri?> = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        folderPickerCallback?.invoke(uri)
        folderPickerCallback = null
    }

    fun launchFolderPicker(callback: (Uri?) -> Unit) {
        folderPickerCallback = callback
        try {
            folderPickerLauncher.launch(null)
        } catch (_: ActivityNotFoundException) {
            // Some devices ship without (or with disabled) DocumentsUI —
            // OPEN_DOCUMENT_TREE has no handler and ActivityResultLauncher
            // throws synchronously. No file-manager app can stand in for it,
            // so fall back to the app-private models directory instead of
            // leaving the user wedged on the mandatory folder-setup dialog.
            folderPickerCallback = null
            val fallbackUri = StorageDocuments.appStorageFallbackUri(this)
            if (fallbackUri != null) {
                Toast.makeText(
                    this,
                    R.string.storage_fallback_used,
                    Toast.LENGTH_LONG,
                ).show()
                callback(fallbackUri)
            } else {
                Toast.makeText(
                    this,
                    R.string.folder_picker_unavailable,
                    Toast.LENGTH_LONG,
                ).show()
                callback(null)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContentView(R.layout.content_main)
        // The tab bar colours the navigation-bar area itself; without this,
        // 3-button navigation gets a grey scrim under the bar.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        setUpTabBar()
    }

    /**
     * The tab bar under every screen. Each tab keeps its own place: going to
     * another tab saves this one's back stack, and coming back restores it.
     * Tapping the current tab goes back to its first page. The bar hides
     * while the keyboard is up, so the chat's input sits on the keyboard.
     */
    private fun setUpTabBar() {
        val navHost = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val nav = navHost.navController
        navController = nav
        val selected = selectedTab
        nav.addOnDestinationChangedListener { _, destination, _ ->
            selected.value = tabOf(destination.id)
        }
        val bar = findViewById<ComposeView>(R.id.tab_bar)
        bar.setContent {
            PlaygroundTheme {
                BottomTabBar(selected = selected.value) { tab -> selectTab(nav, tab, selected.value) }
            }
        }
        // The bar takes the navigation-bar space, so the screens above it
        // don't pad for it again; with the keyboard up the bar is hidden and
        // the screens get the real insets.
        val container = findViewById<View>(R.id.nav_host_fragment)
        ViewCompat.setOnApplyWindowInsetsListener(container) { _, insets ->
            val keyboard = insets.isVisible(WindowInsetsCompat.Type.ime())
            bar.visibility = if (keyboard) View.GONE else View.VISIBLE
            if (keyboard) {
                insets
            } else {
                // Only the navigation bars: systemBars() is built from them,
                // and setting it too would overwrite the status-bar inset.
                val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
                WindowInsetsCompat.Builder(insets)
                    .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(navBars.left, navBars.top, navBars.right, 0))
                    .build()
            }
        }
    }

    private var navController: NavController? = null
    private val selectedTab = mutableStateOf(AppTab.CHAT)

    /** Switch to [tab] as if its tab-bar button was tapped (e.g. the chat's model name opens Models). */
    fun openTab(tab: AppTab) {
        val nav = navController ?: return
        selectTab(nav, tab, selectedTab.value)
    }

    private fun tabOf(destinationId: Int): AppTab = when (destinationId) {
        R.id.nav_home -> AppTab.CHAT
        R.id.nav_models -> AppTab.MODELS
        R.id.nav_phone_server -> AppTab.SERVER
        else -> AppTab.SETTINGS
    }

    private fun selectTab(nav: NavController, tab: AppTab, current: AppTab) {
        val root = when (tab) {
            AppTab.CHAT -> R.id.nav_home
            AppTab.MODELS -> R.id.nav_models
            AppTab.SERVER -> R.id.nav_phone_server
            AppTab.SETTINGS -> R.id.nav_settings
        }
        if (tab == current) {
            // Already here: back to the tab's first page.
            if (nav.currentDestination?.id != root) nav.popBackStack(root, false)
            return
        }
        if (tab == AppTab.CHAT) {
            // Save the tab being left, so going back to it restores its page.
            nav.popBackStack(R.id.nav_home, inclusive = false, saveState = true)
            return
        }
        nav.navigate(root, null, navOptions {
            popUpTo(R.id.nav_home) { saveState = true }
            launchSingleTop = true
            restoreState = true
        })
    }
}
