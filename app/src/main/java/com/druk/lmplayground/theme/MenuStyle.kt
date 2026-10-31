package com.druk.lmplayground.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Floating-card look shared by the chat menu, dialogs and pop-up menus. */
val MenuCardShape = RoundedCornerShape(28.dp)

/** Smaller corners for pop-up (dropdown) menus. */
val PopupMenuShape = RoundedCornerShape(16.dp)

/** Thin outline around floating cards and menus. */
val menuBorder: BorderStroke
    @Composable @ReadOnlyComposable
    get() = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)

/** Draws the floating-card outline; for dialogs that take no border, such as AlertDialog. */
@Composable
fun Modifier.menuOutline(): Modifier = border(menuBorder, MenuCardShape)
