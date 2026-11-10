package com.moscilez.track.ui.components

import androidx.annotation.StringRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.ElectricScooter
import androidx.compose.material.icons.filled.Hiking
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.TwoWheeler
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.moscilez.track.R
import com.moscilez.track.domain.model.ActivityType
import com.moscilez.track.ui.theme.LocalMT
import com.moscilez.track.ui.theme.Num

val Square = RoundedCornerShape(4.dp)
val ContentMaxWidth = 720.dp

fun ActivityType.icon(): ImageVector = when (this) {
    ActivityType.RUN -> Icons.AutoMirrored.Filled.DirectionsRun
    ActivityType.WALK -> Icons.AutoMirrored.Filled.DirectionsWalk
    ActivityType.BIKE -> Icons.AutoMirrored.Filled.DirectionsBike
    ActivityType.SCOOTER -> Icons.Filled.ElectricScooter
    ActivityType.HIKE -> Icons.Filled.Hiking
    ActivityType.CAR -> Icons.Filled.DirectionsCar
    ActivityType.MOTO -> Icons.Filled.TwoWheeler
    ActivityType.OTHER -> Icons.Filled.MoreHoriz
}

@StringRes
fun ActivityType.label(): Int = when (this) {
    ActivityType.RUN -> R.string.type_run
    ActivityType.WALK -> R.string.type_walk
    ActivityType.BIKE -> R.string.type_bike
    ActivityType.SCOOTER -> R.string.type_scooter
    ActivityType.HIKE -> R.string.type_hike
    ActivityType.CAR -> R.string.type_car
    ActivityType.MOTO -> R.string.type_moto
    ActivityType.OTHER -> R.string.type_other
}

/** Centers content and caps its width for tablets and landscape. */
@Composable
fun Bounded(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = ContentMaxWidth).fillMaxWidth(), content = content)
    }
}

@Composable
fun Hairline(modifier: Modifier = Modifier, inset: Dp = 0.dp) {
    HorizontalDivider(modifier.padding(start = inset), thickness = 1.dp, color = LocalMT.current.line)
}

/** Scales down slightly while pressed; the app's single press-feedback language. */
@Composable
fun Modifier.pressScale(source: MutableInteractionSource): Modifier {
    val pressed by source.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.97f else 1f, tween(90), label = "press")
    return this.scale(s)
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, loading: Boolean = false) {
    val source = remember { MutableInteractionSource() }
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp).pressScale(source),
        shape = Square,
        interactionSource = source,
        colors = ButtonDefaults.buttonColors(containerColor = LocalMT.current.accent, contentColor = Color.White),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
    ) {
        if (loading) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
        else Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, danger: Boolean = false) {
    val mt = LocalMT.current
    val color = if (danger) mt.danger else mt.accent
    val source = remember { MutableInteractionSource() }
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 56.dp).pressScale(source),
        shape = Square,
        interactionSource = source,
        border = BorderStroke(1.dp, if (enabled) color else mt.line),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = color),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().padding(top = 28.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).semantics { heading() })
        if (action != null && onAction != null) {
            Row(
                Modifier.heightIn(min = 48.dp).clip(Square).clickable(onClick = onAction).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(action, color = LocalMT.current.accent, style = MaterialTheme.typography.bodyLarge)
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = LocalMT.current.accent, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** Small uppercase mono caption above a value. */
@Composable
fun Caption(text: String, modifier: Modifier = Modifier, color: Color = LocalMT.current.ink2) {
    Text(text.uppercase(), style = Num.label, color = color, modifier = modifier)
}

@Composable
fun MetricBlock(label: String, value: String, unit: String?, modifier: Modifier = Modifier, valueStyle: TextStyle = Num.m, mono: Boolean = false) {
    Column(modifier.semantics(mergeDescendants = true) {}) {
        if (mono) Caption(label) else Text(label, style = MaterialTheme.typography.bodyMedium, color = LocalMT.current.ink2)
        Spacer(Modifier.height(4.dp))
        Text(value, style = valueStyle, maxLines = 1)
        if (unit != null) Text(unit, style = MaterialTheme.typography.bodySmall, color = LocalMT.current.ink2)
    }
}

@Composable
fun VDivider(modifier: Modifier = Modifier) {
    Box(modifier.width(1.dp).background(LocalMT.current.line))
}

@Composable
fun IconBadge(icon: ImageVector, modifier: Modifier = Modifier, selected: Boolean = false, size: Dp = 44.dp) {
    val mt = LocalMT.current
    Box(
        modifier.size(size).clip(CircleShape).background(if (selected) mt.accentSoft else mt.chip),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = if (selected) mt.accent else mt.ink, modifier = Modifier.size(size * 0.5f)) }
}

/** The app's list row: icon badge, title/subtitle, chevron; hairline below. */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    selected: Boolean = false,
    chevron: Boolean = true,
    divider: Boolean = true,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val mt = LocalMT.current
    Column(modifier) {
        val base = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .then(if (selected) Modifier.border(1.dp, mt.accent, Square).background(mt.accentSoft.copy(alpha = 0.6f), Square) else Modifier)
        val clickable = if (onClick != null || onLongClick != null) {
            base.combinedClickableCompat(onClick = onClick ?: {}, onLongClick = onLongClick)
        } else base
        Row(clickable.padding(vertical = 10.dp, horizontal = if (selected) 8.dp else 0.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                IconBadge(icon, selected = selected)
                Spacer(Modifier.width(20.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = mt.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            trailing?.invoke(this)
            if (chevron) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = if (selected) mt.accent else mt.ink2)
        }
        if (divider && !selected) Hairline(inset = if (icon != null) 64.dp else 0.dp)
    }
}

fun Modifier.combinedClickableCompat(onClick: () -> Unit, onLongClick: (() -> Unit)?): Modifier =
    this.clip(Square).combinedClickable(onClick = onClick, onLongClick = onLongClick)

@Composable
fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, subtitle: String? = null) {
    val mt = LocalMT.current
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Switch) { onChange(!checked) }.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = mt.ink2)
            }
            Switch(
                checked = checked, onCheckedChange = null,
                colors = SwitchDefaults.colors(checkedTrackColor = mt.accent, uncheckedTrackColor = mt.chip, uncheckedBorderColor = mt.line, uncheckedThumbColor = mt.ink2),
            )
        }
        Hairline()
    }
}

/** Segmented single choice drawn as bordered rectangles. */
@Composable
fun <T> Segmented(options: List<T>, selected: T, label: @Composable (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val mt = LocalMT.current
    Row(modifier.fillMaxWidth().border(1.dp, mt.line, Square)) {
        options.forEachIndexed { i, o ->
            val sel = o == selected
            val text = label(o)
            Box(
                Modifier.weight(1f).heightIn(min = 48.dp)
                    .background(if (sel) mt.ink else Color.Transparent)
                    .clickable(role = Role.RadioButton) { onSelect(o) }
                    .semantics { stateDescription = if (sel) "✓" else "" },
                contentAlignment = Alignment.Center,
            ) {
                Text(text, style = MaterialTheme.typography.labelMedium, color = if (sel) mt.bg else mt.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 6.dp))
            }
            if (i < options.lastIndex) VDivider(Modifier.height(48.dp))
        }
    }
}

@Composable
fun SquareChip(text: String, selected: Boolean, onClick: () -> Unit, leading: ImageVector? = null) {
    val mt = LocalMT.current
    FilterChip(
        selected = selected, onClick = onClick, label = { Text(text) }, shape = Square,
        leadingIcon = leading?.let { { Icon(it, null, Modifier.size(18.dp)) } },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Color.Transparent, selectedContainerColor = mt.ink, selectedLabelColor = mt.bg,
            selectedLeadingIconColor = mt.bg, labelColor = mt.ink, iconColor = mt.ink,
        ),
        border = FilterChipDefaults.filterChipBorder(enabled = true, selected = selected, borderColor = mt.line, selectedBorderColor = mt.ink),
        modifier = Modifier.heightIn(min = 40.dp),
    )
}

@Composable
fun FlatProgress(fraction: Float, modifier: Modifier = Modifier, color: Color = LocalMT.current.accent, description: String? = null) {
    val f by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(600), label = "progress")
    val mt = LocalMT.current
    Box(
        modifier.fillMaxWidth().height(6.dp).background(mt.line).semantics { if (description != null) contentDescription = description },
    ) { Box(Modifier.fillMaxWidth(f).height(6.dp).background(color)) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailTopBar(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    TopAppBar(
        title = { Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back)) }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background, scrolledContainerColor = MaterialTheme.colorScheme.background),
    )
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(vertical = 40.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.width(40.dp).height(4.dp).background(LocalMT.current.accent))
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = LocalMT.current.ink2)
        if (action != null && onAction != null) {
            Spacer(Modifier.height(8.dp))
            SecondaryButton(action, onAction)
        }
    }
}

@Composable
fun LoadingState(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = LocalMT.current.accent, strokeWidth = 2.dp)
    }
}

@Composable
fun ErrorState(message: String, onRetry: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.width(40.dp).height(4.dp).background(LocalMT.current.danger))
        Text(stringResource(R.string.error_title), style = MaterialTheme.typography.titleMedium)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = LocalMT.current.ink2)
        if (onRetry != null) SecondaryButton(stringResource(R.string.action_retry), onRetry)
    }
}

@Composable
fun FullscreenLoading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = LocalMT.current.accent, strokeWidth = 2.dp) }
}

@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    danger: Boolean = false,
    extra: (@Composable ColumnScope.() -> Unit)? = null,
    confirmEnabled: Boolean = true,
) {
    val mt = LocalMT.current
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = Square,
        containerColor = mt.surface,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(body, style = MaterialTheme.typography.bodyMedium)
                extra?.invoke(this)
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = confirmEnabled, shape = Square) {
                Text(confirm, color = if (!confirmEnabled) mt.ink2 else if (danger) mt.danger else mt.accent)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, shape = Square) { Text(stringResource(R.string.action_cancel), color = mt.ink) } },
    )
}

@Composable
fun MTTextField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    supporting: String? = null,
    isError: Boolean = false,
    password: Boolean = false,
    minLines: Int = 1,
) {
    val mt = LocalMT.current
    var visible by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        modifier = modifier.fillMaxWidth(),
        singleLine = singleLine,
        minLines = minLines,
        isError = isError,
        shape = Square,
        supportingText = supporting?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboardType),
        visualTransformation = if (password && !visible) PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon = if (password) {
            {
                IconButton(onClick = { visible = !visible }) {
                    Icon(
                        if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        stringResource(if (visible) R.string.cd_hide_password else R.string.cd_show_password),
                    )
                }
            }
        } else null,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = mt.accent, unfocusedBorderColor = mt.line, focusedLabelColor = mt.accent,
            cursorColor = mt.accent, focusedContainerColor = mt.surface, unfocusedContainerColor = mt.surface,
        ),
    )
}

/** Key/value line used in details and diagnostics. */
@Composable
fun KeyValueRow(label: String, value: String, icon: ImageVector? = null, valueColor: Color = Color.Unspecified) {
    val mt = LocalMT.current
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(vertical = 10.dp).semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, null, tint = mt.ink, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(20.dp))
            }
            Text(label, style = MaterialTheme.typography.bodyLarge, color = mt.ink2, modifier = Modifier.weight(1f))
            Text(value, style = Num.s.copy(fontSize = MaterialTheme.typography.titleMedium.fontSize), color = if (valueColor == Color.Unspecified) mt.ink else valueColor)
        }
        Hairline()
    }
}
