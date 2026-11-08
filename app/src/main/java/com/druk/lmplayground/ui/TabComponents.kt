package com.druk.lmplayground.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The look of the Models, Server and Settings tabs: the app's own flat
 * settings style (as on Settings → General), not cards. A section is a
 * heading over a divider; rows are an icon, a name and a switch, value or
 * action. Blue marks actions, green marks "ready" states.
 */
object TabColors {
    val Accent = Color(0xFF0A84FF)
    val Green = Color(0xFF34C759)
    val Red = Color(0xFFFF453A)
}

/** Space from the screen edge to text and icons, as on the other settings pages. */
val TabEdge = 16.dp

/** A tab's title, in the size of a page's top bar, with an optional action on the right. */
@Composable
fun TabTitle(title: String, modifier: Modifier = Modifier, action: @Composable RowScope.() -> Unit = {}) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .padding(start = TabEdge, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        action()
    }
}

/** A blue text action with an optional icon, like "+ Import". */
@Composable
fun TabTextAction(text: String, icon: ImageVector? = null, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = TabColors.Accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(text, color = TabColors.Accent, style = MaterialTheme.typography.bodyLarge)
    }
}

/** A section heading over a divider; [trailing] sits at the heading's end (e.g. a sort toggle). */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = TabEdge, end = 8.dp, top = 20.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            trailing()
        }
        HorizontalDivider(Modifier.padding(horizontal = TabEdge))
    }
}

/** A section's rows (no card: the heading's divider does the grouping). */
@Composable
fun GroupCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth(), content = content)
}

/** Rows aren't separated by lines in this style; kept so callers stay simple. */
@Composable
@Suppress("UNUSED_PARAMETER")
fun GroupDivider(indent: Boolean = true) {}

/** A row's icon: plain, in the secondary text colour. */
@Composable
fun RowIcon(icon: ImageVector, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
}

/**
 * A settings row: icon, name (and subtitle), then [value] and a chevron
 * when it opens a page, or [trailing] (a switch, an action).
 * [comingSoon] greys it out with "Coming soon" and makes it inert.
 */
@Composable
fun GroupRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    value: String? = null,
    onClick: (() -> Unit)? = null,
    comingSoon: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null && !comingSoon) Modifier.clickable(onClick = onClick) else Modifier)
            .alpha(if (comingSoon) 0.45f else 1f)
            .padding(horizontal = TabEdge, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowIcon(icon)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val sub = if (comingSoon) "Coming soon" else subtitle
            if (sub != null) {
                Text(
                    sub,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        }
        when {
            trailing != null && !comingSoon -> trailing()
            onClick != null -> {
                if (value != null) {
                    Text(
                        value,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The app's usual switch, without the extra touch padding (as CompactSwitch). */
@Composable
fun TabSwitch(checked: Boolean, enabled: Boolean = true, onCheckedChange: (Boolean) -> Unit) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

/** A coloured status word ("Ready", "Loaded"). */
@Composable
fun StatusBadge(text: String, color: Color = TabColors.Green) {
    Text(
        text,
        color = color,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(horizontal = 8.dp),
    )
}

/** A text action at a row's end: blue ("Load"), or grey ("Unload"). */
@Composable
fun PillButton(text: String, primary: Boolean = true, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        text,
        color = if (primary) TabColors.Accent else MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .alpha(if (enabled) 1f else 0.4f)
            .clip(RoundedCornerShape(8.dp))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    )
}

/** A full-width button ("Start server"). */
@Composable
fun BigButton(
    text: String,
    icon: ImageVector? = null,
    color: Color = TabColors.Accent,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.4f)
            .clip(RoundedCornerShape(8.dp))
            .background(color)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = Color.White, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

/** Text tabs under the title ("My models | Browse | Cloud"), the current one underlined. */
@Composable
fun SegmentedTabs(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = TabEdge - 8.dp)
        ) {
            options.forEachIndexed { i, label ->
                val on = i == selected
                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onSelect(i) }
                        .padding(horizontal = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.titleSmall,
                        color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                    Box(
                        Modifier
                            .height(2.dp)
                            .width(24.dp)
                            .background(if (on) MaterialTheme.colorScheme.onSurface else Color.Transparent)
                    )
                }
                if (i < options.lastIndex) Spacer(Modifier.width(8.dp))
            }
        }
        HorizontalDivider()
    }
}

/** Space at the end of a tab so the last row clears the tab bar. */
@Composable
fun TabBottomSpace() = Spacer(Modifier.height(16.dp))
