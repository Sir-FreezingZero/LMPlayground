package com.druk.lmplayground.settings

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.fragment.findNavController
import com.druk.llamacpp.InferenceState
import com.druk.lmplayground.App
import com.druk.lmplayground.BuildConfig
import com.druk.lmplayground.R
import com.druk.lmplayground.storage.ModelsContent
import com.druk.lmplayground.storage.StorageDocuments
import com.druk.lmplayground.storage.StorageViewModel
import com.druk.lmplayground.util.rememberHingeWidthDp
import com.druk.lmplayground.theme.PlaygroundTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsFragment : Fragment() {

    /**
     * Rapid double-taps on a settings row can fire the second click after the
     * first has already navigated away from nav_settings, at which point the
     * action ID is no longer known to the NavController and navigate() throws
     * IllegalArgumentException. Guard every navigation by verifying the
     * NavController is still at nav_settings before firing the action.
     */
    private fun NavController.navigateFromSettings(actionId: Int) {
        if (currentDestination?.id == R.id.nav_settings) {
            navigate(actionId)
        }
    }

    /**
     * Debug-only: trigger a Process.killProcess(myPid) inside `:llama` so we
     * can verify that crashes don't take the UI down and that the
     * acknowledge → reload recovery path works.
     */
    private fun crashInferenceEngine() {
        val app = activity?.application as? App ?: return
        val client = app.inferenceClient
        val service = (client.state.value as? InferenceState.Connected)?.service
        if (service == null) {
            Toast.makeText(
                requireContext(),
                R.string.debug_crash_engine_not_connected,
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        // Run off the main thread — the binder call will throw DeadObject
        // because the process dies mid-transaction; that's expected.
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    service.crashForTest()
                } catch (t: Throwable) {
                    Log.d(TAG, "crashForTest returned with expected error: ${t.javaClass.simpleName}")
                }
            }
            Toast.makeText(
                requireContext(),
                R.string.debug_crash_engine_done,
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    companion object {
        private const val TAG = "SettingsFragment"
        /** Optional nav argument: which detail to open on entry (e.g. "tools"). */
        const val ARG_OPEN_DETAIL = "open_detail"
        const val DETAIL_TOOLS = "tools"
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = ComposeView(inflater.context).apply {
        layoutParams = LayoutParams(MATCH_PARENT, MATCH_PARENT)
        setContent {
            PlaygroundTheme {
                // Deep link from "What's New": straight to the Tools page.
                // Consume the argument so returning here doesn't jump again.
                val openDetail = arguments?.getString(ARG_OPEN_DETAIL)
                LaunchedEffect(Unit) {
                    if (openDetail == DETAIL_TOOLS) {
                        arguments?.remove(ARG_OPEN_DETAIL)
                        findNavController().navigateFromSettings(R.id.action_settings_to_tools)
                    }
                }
                val nav = findNavController()
                SettingsTab(
                    actions = SettingsTabActions(
                        appearance = { nav.navigateFromSettings(R.id.action_settings_to_appearance) },
                        soundHaptic = { nav.navigateFromSettings(R.id.action_settings_to_sound_haptic) },
                        language = { nav.navigateFromSettings(R.id.action_settings_to_language) },
                        chatOptions = { nav.navigateFromSettings(R.id.action_settings_to_general) },
                        systemPrompt = { nav.navigateFromSettings(R.id.action_settings_to_system_prompts) },
                        modelSettings = { nav.navigateFromSettings(R.id.action_settings_to_model_settings) },
                        tools = { nav.navigateFromSettings(R.id.action_settings_to_tools) },
                        memory = { nav.navigateFromSettings(R.id.action_settings_to_memory) },
                        remoteServers = { nav.navigateFromSettings(R.id.action_settings_to_remote_servers) },
                        advanced = { nav.navigateFromSettings(R.id.action_settings_to_advanced) },
                        testing = { nav.navigateFromSettings(R.id.action_settings_to_testing) },
                        logs = { nav.navigateFromSettings(R.id.action_settings_to_logs) },
                        faq = { nav.navigateFromSettings(R.id.action_settings_to_faq) },
                        feedback = {
                            val email = getString(R.string.privacy_policy_contact_email)
                            val intent = Intent(Intent.ACTION_SENDTO).apply {
                                data = Uri.parse("mailto:$email")
                                putExtra(Intent.EXTRA_SUBJECT, getString(R.string.app_name))
                            }
                            try { startActivity(intent) } catch (_: ActivityNotFoundException) {}
                        },
                        privacy = { nav.navigateFromSettings(R.id.action_settings_to_privacy_policy) },
                        // Debug builds can crash the inference process to
                        // check the recovery flow.
                        crashEngine = if (BuildConfig.DEBUG) {
                            { crashInferenceEngine() }
                        } else null,
                    ),
                    appVersion = BuildConfig.VERSION_NAME,
                )
            }
        }
    }
}
