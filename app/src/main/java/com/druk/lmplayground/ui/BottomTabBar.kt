package com.druk.lmplayground.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/** The app's four tabs, in bar order. */
enum class AppTab { CHAT, MODELS, SERVER, SETTINGS }

/**
 * The bar along the bottom of every screen (Chat, Models, Server,
 * Settings), styled like the cross-platform-llm-client: outline icons,
 * the current tab filled and blue.
 */
@Composable
fun BottomTabBar(selected: AppTab, onSelect: (AppTab) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .navigationBarsPadding()
    ) {
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHigh)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            TabItem("Chat", Icons.AutoMirrored.Outlined.Chat, Icons.AutoMirrored.Filled.Chat, selected == AppTab.CHAT) { onSelect(AppTab.CHAT) }
            TabItem("Models", Icons.Outlined.Layers, Icons.Filled.Layers, selected == AppTab.MODELS) { onSelect(AppTab.MODELS) }
            TabItem("Server", Icons.Outlined.AccountTree, Icons.Filled.AccountTree, selected == AppTab.SERVER) { onSelect(AppTab.SERVER) }
            TabItem("Settings", Icons.Outlined.Settings, Icons.Filled.Settings, selected == AppTab.SETTINGS) { onSelect(AppTab.SETTINGS) }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.TabItem(
    label: String,
    icon: ImageVector,
    selectedIcon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val color = if (selected) TabColors.Accent else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = Modifier
            .weight(1f)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(if (selected) selectedIcon else icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(2.dp))
        Text(label, color = color, style = MaterialTheme.typography.labelSmall)
    }
}
