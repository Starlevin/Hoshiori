package com.glasssign.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Shared building blocks for the appearance sub-pages (presets, colours, glass, background, typography).
 *
 * Everything here is deliberately layout-only: no preference is read or written, so the same widgets work
 * for pages that own the commit (typography slider) and pages that only display a value (overview rows).
 * Rows never fix their height — `heightIn(min = 56.dp)` is a floor, and the text column always takes
 * `weight(1f)`, so a 140% text scale grows the row instead of clipping the description.
 */

/**
 * One titled card. The label sits *above* the surface (outside the rounded card) so groups read as
 * sections of a list rather than as card headers; the caller owns the spacing between groups.
 */
@Composable
internal fun PreferenceGroup(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        title?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelMedium,
                color = Muted,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
            )
        }
        Surface(
            Modifier.fillMaxWidth(),
            shape = GlassShape,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f)),
        ) {
            Column(Modifier.padding(vertical = 4.dp), content = content)
        }
    }
}

/**
 * Navigable row: tinted circular icon, wrapping title/description, optional value, chevron.
 * The whole row is the 48dp+ target and announces title + value in one sentence.
 */
@Composable
internal fun PreferenceRow(
    title: String,
    description: String,
    icon: ImageVector,
    onClick: () -> Unit,
    trailing: String? = null,
) {
    val spoken = title + (trailing?.let { "，$it" } ?: "")
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics { contentDescription = spoken },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(36.dp)) {
            androidx.compose.foundation.layout.Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(20.dp))
            }
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(description, style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        // Capped so a long current value can never starve the title/description column above.
        trailing?.let {
            Text(
                it,
                modifier = Modifier.widthIn(max = 84.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = Muted,
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
            )
        }
        Icon(HoshioriIcons.ChevronRight, null, tint = Muted, modifier = Modifier.size(18.dp))
    }
}

/**
 * Page skeleton shared by every second-level appearance page: scrolling column, page margins, an
 * optional one-line hint, then the page's own groups.
 *
 * No page title is drawn here: the host already shows the section name in its fixed top bar, so a
 * second heading would repeat it. [title] is kept as a stable parameter for call sites but is only
 * used for the accessibility heading, not painted as a duplicate.
 */
@Composable
internal fun AppearanceSubScaffold(
    title: String,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 12.dp, bottom = 32.dp)
            .semantics { heading() },
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Muted) }
        content()
    }
}

/**
 * Wrapping chip row. `FlowRow` (not a weighted `Row`) so four or more options reflow onto extra lines
 * instead of squeezing their labels; each chip keeps a 48dp touch target.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChoiceChips(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (value, label) ->
            FilterChip(
                selected = selected == value,
                onClick = { onSelect(value) },
                label = { Text(label) },
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }
    }
}

/**
 * Row separator for a list of [PreferenceRow]s inside one [PreferenceGroup]. The indent starts at the
 * text column (16dp padding + 36dp icon + 12dp gap) so the line reads as part of the row, not the card.
 */
@Composable
internal fun PreferenceDivider() {
    HorizontalDivider(
        Modifier.padding(start = 64.dp, end = 16.dp),
        thickness = .5.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .35f),
    )
}

/**
 * Local switch row (the shared `QSwitch` is being reworked by the host file, so this page family keeps
 * its own). Text takes the remaining width and wraps; the Switch stays at its intrinsic size.
 *
 * [enabled] sits BEFORE the trailing callback on purpose: this row is usually called with a trailing
 * lambda, and putting the Boolean last would let `SettingToggleRow(a, b, checked) { ... }` bind the
 * lambda to `enabled` and silently drop the callback.
 *
 * [enabled] is false only when the backing preference has no owner in the current composition (for
 * instance the "reduce motion" preference outside the screen that provides its setter). A disabled row
 * keeps its title and explanation visible, so the user can tell why it cannot be toggled.
 */
@Composable
internal fun SettingToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChecked: (Boolean) -> Unit,
) {
    val contentAlpha = if (enabled) 1f else .6f
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha))
            Text(description, style = MaterialTheme.typography.bodySmall, color = Muted.copy(alpha = contentAlpha))
        }
        Switch(
            checked = checked,
            onCheckedChange = onChecked,
            enabled = enabled,
            modifier = Modifier.semantics { contentDescription = title },
        )
    }
}
