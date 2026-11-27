package tech.codesky.dockdeploy.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tech.codesky.dockdeploy.ui.theme.LocalStatusColors

enum class BadgeTone {
    SUCCESS,
    WARNING,
    DANGER,
    INFO,
    NEUTRAL,
}

@Composable
fun DockCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    borderColor: Color = MaterialTheme.colorScheme.outlineVariant,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    contentPadding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Surface(
        modifier = modifier
            .clip(shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = shape,
        color = containerColor,
        border = BorderStroke(1.dp, borderColor),
    ) {
        Column(modifier = Modifier.padding(contentPadding), content = content)
    }
}

@Composable
fun StatusBadge(
    label: String,
    tone: BadgeTone,
    modifier: Modifier = Modifier,
    showDot: Boolean = true,
) {
    val statusColors = LocalStatusColors.current
    val (bg, fg, dot) = when (tone) {
        BadgeTone.SUCCESS -> Triple(statusColors.successContainer, statusColors.onSuccessContainer, statusColors.success)
        BadgeTone.WARNING -> Triple(statusColors.warningContainer, statusColors.onWarningContainer, statusColors.warning)
        BadgeTone.DANGER -> Triple(statusColors.dangerContainer, statusColors.onDangerContainer, statusColors.danger)
        BadgeTone.INFO -> Triple(statusColors.infoContainer, statusColors.onInfoContainer, statusColors.info)
        BadgeTone.NEUTRAL -> Triple(
            MaterialTheme.colorScheme.surfaceContainerHigh,
            MaterialTheme.colorScheme.onSurfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = bg,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (showDot) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(dot),
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

fun serverStatusTone(status: String): BadgeTone = when (status.lowercase()) {
    "online" -> BadgeTone.SUCCESS
    "offline", "unauthorized" -> BadgeTone.DANGER
    else -> BadgeTone.NEUTRAL
}

fun containerStateTone(state: String): BadgeTone = when (state.lowercase()) {
    "running" -> BadgeTone.SUCCESS
    "restarting", "paused", "created" -> BadgeTone.WARNING
    "exited", "dead" -> BadgeTone.DANGER
    else -> BadgeTone.NEUTRAL
}

fun deploymentStatusTone(status: String): BadgeTone = when (status.lowercase()) {
    "running", "succeeded", "active", "healthy", "secure" -> BadgeTone.SUCCESS
    "deploying", "queued", "pending", "warning" -> BadgeTone.WARNING
    "failed", "error", "critical" -> BadgeTone.DANGER
    else -> BadgeTone.NEUTRAL
}

fun statusLabel(raw: String): String = when (raw.lowercase()) {
    "never_deployed" -> "Not deployed"
    "letsencrypt" -> "Let's Encrypt"
    else -> raw.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

@Composable
fun StatTile(
    icon: ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    tone: BadgeTone? = null,
) {
    DockCard(modifier = modifier, contentPadding = 14.dp) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        if (tone != null) {
            StatusBadge(label = value, tone = tone)
        } else {
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!subtitle.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun AlertBanner(
    message: String,
    tone: BadgeTone = BadgeTone.DANGER,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null,
) {
    val statusColors = LocalStatusColors.current
    val (bg, fg, icon) = when (tone) {
        BadgeTone.DANGER -> Triple(statusColors.dangerContainer, statusColors.onDangerContainer, Icons.Rounded.ErrorOutline)
        BadgeTone.WARNING -> Triple(statusColors.warningContainer, statusColors.onWarningContainer, Icons.Rounded.WarningAmber)
        BadgeTone.SUCCESS -> Triple(statusColors.successContainer, statusColors.onSuccessContainer, Icons.Rounded.CheckCircleOutline)
        else -> Triple(statusColors.infoContainer, statusColors.onInfoContainer, Icons.Rounded.Info)
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = bg,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = fg,
                modifier = Modifier.weight(1f),
            )
            if (onDismiss != null) {
                IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Rounded.Close, contentDescription = "Dismiss", tint = fg, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
fun EmptyStateView(
    icon: ImageVector,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    DockCard(modifier = modifier.fillMaxWidth(), contentPadding = 24.dp) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(16.dp))
                Button(onClick = onAction) {
                    Text(actionLabel)
                }
            }
        }
    }
}

@Composable
fun CopyableCodeBox(
    text: String,
    label: String? = null,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        if (!label.isNullOrBlank()) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
        }
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(text))
                        copied = true
                    },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                        contentDescription = "Copy",
                        tint = if (copied) LocalStatusColors.current.success else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun KeyValueRow(
    key: String,
    value: String,
    mono: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = key,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 12.dp),
        )
        Text(
            text = value,
            style = if (mono) {
                MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
            } else {
                MaterialTheme.typography.bodySmall
            },
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
fun SudoPromptDialog(
    title: String = "Sudo Password Required",
    description: String = "Enter the sudo password for this server to complete the elevated operation.",
    onDismiss: () -> Unit,
    onConfirm: (password: String, saveSudo: Boolean) -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var saveSudo by remember { mutableStateOf(true) }
    var showPassword by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.AdminPanelSettings, contentDescription = null) },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(description, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Sudo password") },
                    singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                if (showPassword) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                contentDescription = "Toggle password visibility",
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { saveSudo = !saveSudo },
                ) {
                    Checkbox(checked = saveSudo, onCheckedChange = { saveSudo = it })
                    Text("Save encrypted sudo password on server", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(password, saveSudo) },
                enabled = password.isNotBlank(),
            ) {
                Text("Continue")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
fun ConfirmActionDialog(
    title: String,
    message: String,
    confirmLabel: String = "Confirm",
    destructive: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                if (destructive) Icons.Rounded.DeleteForever else Icons.Rounded.WarningAmber,
                contentDescription = null,
                tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
        },
        title = { Text(title) },
        text = { Text(message, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = if (destructive) {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    )
                } else {
                    ButtonDefaults.buttonColors()
                },
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * Responsive grid that renders 1 column on compact phones, 2 on medium foldables,
 * and [maxColumns] on expanded tablets.
 */
@Composable
fun <T> ResponsiveGrid(
    items: List<T>,
    columns: Int,
    modifier: Modifier = Modifier,
    horizontalSpacing: Dp = 12.dp,
    verticalSpacing: Dp = 12.dp,
    itemContent: @Composable (T) -> Unit,
) {
    val safeCols = columns.coerceAtLeast(1)
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(verticalSpacing),
    ) {
        items.chunked(safeCols).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(horizontalSpacing),
            ) {
                rowItems.forEach { item ->
                    Box(modifier = Modifier.weight(1f)) {
                        itemContent(item)
                    }
                }
                repeat(safeCols - rowItems.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
