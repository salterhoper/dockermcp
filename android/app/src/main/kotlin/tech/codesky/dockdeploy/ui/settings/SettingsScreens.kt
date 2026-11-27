package tech.codesky.dockdeploy.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import tech.codesky.dockdeploy.data.model.*
import tech.codesky.dockdeploy.data.preferences.ThemeMode
import tech.codesky.dockdeploy.data.repository.DockDeployRepository
import tech.codesky.dockdeploy.ui.ai.AISettingsPane
import tech.codesky.dockdeploy.ui.components.*
import tech.codesky.dockdeploy.util.Formatters

private enum class SettingsSection(val label: String) {
    PROFILE("Profile & 2FA"),
    HOST_APPEARANCE("Host & Theme"),
    TOKENS("API Tokens"),
    CREDENTIALS("Credentials"),
    AI("AI Assistant"),
    USERS("Users & Invites"),
    AUDIT("Audit Log"),
}

@Composable
fun SettingsScreen(
    repo: DockDeployRepository,
    user: CurrentUser,
    hostUrl: String,
    themeMode: ThemeMode,
    dynamicColor: Boolean,
    onSetThemeMode: (ThemeMode) -> Unit,
    onSetDynamicColor: (Boolean) -> Unit,
    onSwitchHost: () -> Unit,
    onRefreshUser: () -> Unit,
    onLogout: () -> Unit,
) {
    val visibleSections = remember(user.permissions) {
        SettingsSection.entries.filter { sec ->
            when (sec) {
                SettingsSection.CREDENTIALS -> user.can("credential:read")
                SettingsSection.USERS -> user.can("user:read")
                SettingsSection.AUDIT -> user.can("audit:read")
                else -> true
            }
        }
    }
    var active by remember { mutableStateOf(SettingsSection.PROFILE) }

    Column(modifier = Modifier.fillMaxSize()) {
        PrimaryScrollableTabRow(
            selectedTabIndex = visibleSections.indexOf(active).coerceAtLeast(0),
            edgePadding = 12.dp,
        ) {
            visibleSections.forEach { sec ->
                Tab(
                    selected = active == sec,
                    onClick = { active = sec },
                    text = { Text(sec.label, maxLines = 1) },
                )
            }
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (active) {
                SettingsSection.PROFILE -> ProfileAndSecurityPane(repo = repo, user = user, onRefreshUser = onRefreshUser)
                SettingsSection.HOST_APPEARANCE -> HostAndAppearancePane(
                    hostUrl = hostUrl,
                    user = user,
                    themeMode = themeMode,
                    dynamicColor = dynamicColor,
                    onSetThemeMode = onSetThemeMode,
                    onSetDynamicColor = onSetDynamicColor,
                    onSwitchHost = onSwitchHost,
                    onLogout = onLogout,
                )
                SettingsSection.TOKENS -> TokensPane(repo = repo)
                SettingsSection.CREDENTIALS -> CredentialsPane(repo = repo, canWrite = user.can("credential:write"))
                SettingsSection.AI -> AISettingsPane(repo = repo)
                SettingsSection.USERS -> UsersAndInvitesPane(repo = repo, currentUserId = user.id, canWrite = user.can("user:write"))
                SettingsSection.AUDIT -> AuditLogPane(repo = repo)
            }
        }
    }
}

@Composable
private fun HostAndAppearancePane(
    hostUrl: String,
    user: CurrentUser,
    themeMode: ThemeMode,
    dynamicColor: Boolean,
    onSetThemeMode: (ThemeMode) -> Unit,
    onSetDynamicColor: (Boolean) -> Unit,
    onSwitchHost: () -> Unit,
    onLogout: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        DockCard {
            Text("Connected Host", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            CopyableCodeBox(text = hostUrl, label = "Active DockDeploy Instance")
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onSwitchHost) {
                    Icon(Icons.Rounded.SwapHoriz, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Switch Host URL")
                }
                Button(
                    onClick = onLogout,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                ) {
                    Icon(Icons.AutoMirrored.Rounded.Logout, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Sign Out")
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("Signed in as ${user.email} (${user.role})", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        DockCard {
            Text("Appearance", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                ThemeMode.entries.forEachIndexed { idx, mode ->
                    SegmentedButton(
                        selected = themeMode == mode,
                        onClick = { onSetThemeMode(mode) },
                        shape = SegmentedButtonDefaults.itemShape(idx, ThemeMode.entries.size),
                        label = { Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }) },
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Material You Dynamic Color", style = MaterialTheme.typography.bodyMedium)
                    Text("Use wallpaper-derived Android 12+ colors instead of Operator Slate", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = dynamicColor, onCheckedChange = onSetDynamicColor)
            }
        }
    }
}

@Composable
private fun ProfileAndSecurityPane(
    repo: DockDeployRepository,
    user: CurrentUser,
    onRefreshUser: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var name by remember(user.name) { mutableStateOf(user.name) }
    var currentPass by remember { mutableStateOf("") }
    var newPass by remember { mutableStateOf("") }
    var sessions by remember { mutableStateOf<List<SessionSummary>>(emptyList()) }
    var twoFaEnabled by remember(user.totpEnabled) { mutableStateOf(user.totpEnabled) }
    var twoFaSetup by remember { mutableStateOf<TwoFactorSetupResponse?>(null) }
    var twoFaCode by remember { mutableStateOf("") }
    var recoveryCodes by remember { mutableStateOf<List<String>?>(null) }
    var disablePassword by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun loadSecurity() {
        scope.launch {
            runCatching { repo.listSessions() }.onSuccess { sessions = it }
            runCatching { repo.get2FAStatus() }.onSuccess { twoFaEnabled = it.enabled }
        }
    }

    LaunchedEffect(Unit) { loadSecurity() }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (notice != null) AlertBanner(message = notice!!, tone = BadgeTone.SUCCESS, onDismiss = { notice = null })
        if (error != null) AlertBanner(message = error!!, tone = BadgeTone.DANGER, onDismiss = { error = null })

        DockCard {
            Text("Profile", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Display Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            KeyValueRow("Email", user.email, mono = true)
            KeyValueRow("Role", user.role)
            Spacer(Modifier.height(10.dp))
            Button(
                enabled = name.isNotBlank() && name != user.name,
                onClick = {
                    scope.launch {
                        runCatching { repo.updateProfile(name.trim()) }
                            .onSuccess { notice = "Profile updated."; onRefreshUser() }
                            .onFailure { error = it.message }
                    }
                },
            ) { Text("Save Name") }
        }

        DockCard {
            Text("Change Password", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = currentPass, onValueChange = { currentPass = it }, label = { Text("Current Password") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = newPass, onValueChange = { newPass = it }, label = { Text("New Password (min 12 chars)") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            Button(
                enabled = currentPass.isNotBlank() && newPass.isNotBlank(),
                onClick = {
                    scope.launch {
                        runCatching { repo.changePassword(currentPass, newPass) }
                            .onSuccess {
                                currentPass = ""
                                newPass = ""
                                notice = "Password updated. Other sessions signed out."
                                loadSecurity()
                            }
                            .onFailure { error = it.message }
                    }
                },
            ) { Text("Update Password") }
        }

        DockCard {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Two-Factor Auth (TOTP)", style = MaterialTheme.typography.titleMedium)
                StatusBadge(label = if (twoFaEnabled) "Enabled" else "Off", tone = if (twoFaEnabled) BadgeTone.SUCCESS else BadgeTone.NEUTRAL)
            }
            Spacer(Modifier.height(10.dp))

            if (!twoFaEnabled && twoFaSetup == null) {
                Button(onClick = {
                    scope.launch {
                        runCatching { repo.setup2FA() }
                            .onSuccess { twoFaSetup = it }
                            .onFailure { error = it.message }
                    }
                }) { Text("Set Up 2FA") }
            }

            twoFaSetup?.let { setup ->
                val qr = remember(setup.otpauthUrl) { Formatters.generateQrBitmap(setup.otpauthUrl) }
                if (qr != null) {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Image(bitmap = qr.asImageBitmap(), contentDescription = "2FA QR Code", modifier = Modifier.size(180.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                }
                CopyableCodeBox(text = setup.secret, label = "Manual TOTP Secret")
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = twoFaCode,
                    onValueChange = { twoFaCode = it },
                    label = { Text("Enter 6-digit verification code") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    enabled = twoFaCode.length == 6,
                    onClick = {
                        scope.launch {
                            runCatching { repo.enable2FA(twoFaCode) }
                                .onSuccess { res ->
                                    twoFaEnabled = true
                                    twoFaSetup = null
                                    twoFaCode = ""
                                    recoveryCodes = res.recoveryCodes
                                    onRefreshUser()
                                }
                                .onFailure { error = it.message }
                        }
                    },
                ) { Text("Enable 2FA") }
            }

            recoveryCodes?.let { codes ->
                Spacer(Modifier.height(10.dp))
                CopyableCodeBox(text = codes.joinToString("\n"), label = "Save Your Recovery Codes")
            }

            if (twoFaEnabled) {
                OutlinedTextField(
                    value = disablePassword,
                    onValueChange = { disablePassword = it },
                    label = { Text("Current password (to disable or regenerate)") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        enabled = disablePassword.isNotBlank(),
                        onClick = {
                            scope.launch {
                                runCatching { repo.regenerateRecoveryCodes(disablePassword) }
                                    .onSuccess { recoveryCodes = it.recoveryCodes; disablePassword = "" }
                                    .onFailure { error = it.message }
                            }
                        },
                    ) { Text("Regenerate Codes") }
                    TextButton(
                        enabled = disablePassword.isNotBlank(),
                        onClick = {
                            scope.launch {
                                runCatching { repo.disable2FA(password = disablePassword) }
                                    .onSuccess { twoFaEnabled = false; disablePassword = ""; onRefreshUser() }
                                    .onFailure { error = it.message }
                            }
                        },
                    ) { Text("Disable 2FA", color = MaterialTheme.colorScheme.error) }
                }
            }
        }

        DockCard {
            Text("Active Sessions (${sessions.size})", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            sessions.forEach { s ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            (s.ip ?: "Unknown IP") + if (s.current) " (This session)" else "",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text("Last used ${Formatters.formatRelative(s.lastUsedAt)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (!s.current) {
                        TextButton(onClick = {
                            scope.launch {
                                runCatching { repo.revokeSession(s.id) }.onSuccess { loadSecurity() }
                            }
                        }) { Text("Revoke", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }
}

@Composable
private fun TokensPane(repo: DockDeployRepository) {
    val scope = rememberCoroutineScope()
    var tokens by remember { mutableStateOf<List<ApiTokenSummary>>(emptyList()) }
    var name by remember { mutableStateOf("") }
    var expiry by remember { mutableIntStateOf(90) }
    var created by remember { mutableStateOf<CreatedToken?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun load() {
        scope.launch { runCatching { repo.listTokens() }.onSuccess { tokens = it }.onFailure { error = it.message } }
    }
    LaunchedEffect(Unit) { load() }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (error != null) AlertBanner(message = error!!, tone = BadgeTone.DANGER)
        created?.let { t ->
            AlertBanner(message = "Copy this token now — it will never be shown again.", tone = BadgeTone.INFO)
            CopyableCodeBox(text = t.token, label = t.name)
        }

        DockCard {
            Text("Create API Token", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Token Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(30 to "30d", 90 to "90d", 365 to "1y", 0 to "Never").forEach { (days, label) ->
                    FilterChip(selected = expiry == days, onClick = { expiry = days }, label = { Text(label) })
                }
            }
            Spacer(Modifier.height(8.dp))
            Button(
                enabled = name.isNotBlank(),
                onClick = {
                    scope.launch {
                        runCatching { repo.createToken(name.trim(), expiry) }
                            .onSuccess { created = it; name = ""; load() }
                            .onFailure { error = it.message }
                    }
                },
            ) { Text("Generate Token") }
        }

        tokens.forEach { t ->
            DockCard(contentPadding = 12.dp) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(t.name, style = MaterialTheme.typography.titleSmall)
                        Text("${t.prefix}... • used ${Formatters.formatRelative(t.lastUsedAt)}", style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = {
                        scope.launch { runCatching { repo.deleteToken(t.id) }.onSuccess { load() } }
                    }) { Icon(Icons.Rounded.DeleteOutline, contentDescription = "Revoke", tint = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}

@Composable
private fun CredentialsPane(repo: DockDeployRepository, canWrite: Boolean) {
    val scope = rememberCoroutineScope()
    var gitCreds by remember { mutableStateOf<List<GitCredential>>(emptyList()) }
    var registries by remember { mutableStateOf<List<Registry>>(emptyList()) }
    var showAddGit by remember { mutableStateOf(false) }
    var showAddReg by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun load() {
        scope.launch {
            runCatching { repo.listGitCredentials() }.onSuccess { gitCreds = it }.onFailure { error = it.message }
            runCatching { repo.listRegistries() }.onSuccess { registries = it }
        }
    }
    LaunchedEffect(Unit) { load() }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (error != null) AlertBanner(message = error!!, tone = BadgeTone.DANGER)

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Git Credentials", style = MaterialTheme.typography.titleMedium)
            if (canWrite) OutlinedButton(onClick = { showAddGit = true }) { Text("Add Git") }
        }
        gitCreds.forEach { c ->
            DockCard(contentPadding = 12.dp) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(c.name, style = MaterialTheme.typography.titleSmall)
                        Text("${c.kind} • ${c.username}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (canWrite) {
                        IconButton(onClick = { scope.launch { runCatching { repo.deleteGitCredential(c.id) }.onSuccess { load() } } }) {
                            Icon(Icons.Rounded.DeleteOutline, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Container Registries", style = MaterialTheme.typography.titleMedium)
            if (canWrite) OutlinedButton(onClick = { showAddReg = true }) { Text("Add Registry") }
        }
        registries.forEach { r ->
            DockCard(contentPadding = 12.dp) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(r.name, style = MaterialTheme.typography.titleSmall)
                        Text("${r.url} • ${r.username}", style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (canWrite) {
                        IconButton(onClick = { scope.launch { runCatching { repo.deleteRegistry(r.id) }.onSuccess { load() } } }) {
                            Icon(Icons.Rounded.DeleteOutline, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }

    if (showAddGit) {
        var name by remember { mutableStateOf("") }
        var kind by remember { mutableStateOf("token") }
        var username by remember { mutableStateOf("") }
        var secret by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddGit = false },
            title = { Text("Add Git Credential") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SegmentedButton(selected = kind == "token", onClick = { kind = "token" }, shape = SegmentedButtonDefaults.itemShape(0, 2), label = { Text("Token") })
                        SegmentedButton(selected = kind == "ssh_key", onClick = { kind = "ssh_key" }, shape = SegmentedButtonDefaults.itemShape(1, 2), label = { Text("SSH Key") })
                    }
                    if (kind == "token") OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = secret, onValueChange = { secret = it }, label = { Text(if (kind == "token") "Access Token" else "Private Key") }, minLines = if (kind == "ssh_key") 4 else 1, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                Button(onClick = {
                    showAddGit = false
                    scope.launch { runCatching { repo.createGitCredential(name, kind, username, secret) }.onSuccess { load() }.onFailure { error = it.message } }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showAddGit = false }) { Text("Cancel") } },
        )
    }

    if (showAddReg) {
        var name by remember { mutableStateOf("") }
        var url by remember { mutableStateOf("") }
        var username by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddReg = false },
            title = { Text("Add Container Registry") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("Registry URL (e.g. ghcr.io)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Password / Token") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                Button(onClick = {
                    showAddReg = false
                    scope.launch { runCatching { repo.createRegistry(name, url, username, password) }.onSuccess { load() }.onFailure { error = it.message } }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showAddReg = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ServerScopePicker(
    role: String,
    servers: List<Server>,
    grants: Map<String, String>,
    onChange: (Map<String, String>) -> Unit,
) {
    if (role == "admin") {
        Text(
            "Admins automatically see and manage all servers.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Scoped Server Access", style = MaterialTheme.typography.labelMedium)
        Text(
            "Selected servers are visible to this user. All other servers stay hidden.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (servers.isEmpty()) {
            Text(
                "No servers available yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            servers.forEach { sv ->
                val perm = grants[sv.id]
                val selected = perm != null
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f),
                    ) {
                        Checkbox(
                            checked = selected,
                            onCheckedChange = { checked ->
                                val next = grants.toMutableMap()
                                if (checked) next[sv.id] = if (role == "viewer") "read" else "operate"
                                else next.remove(sv.id)
                                onChange(next)
                            },
                        )
                        Column {
                            Text(sv.name, style = MaterialTheme.typography.bodySmall)
                            Text(sv.host, style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (selected) {
                        FilterChip(
                            selected = perm == "operate",
                            onClick = {
                                val next = grants.toMutableMap()
                                next[sv.id] = if (perm == "operate") "read" else "operate"
                                onChange(next)
                            },
                            label = { Text(if (perm == "operate") "Operate" else "Read") },
                        )
                    } else {
                        Text("Hidden", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

private fun toServerGrantInputs(role: String, grants: Map<String, String>): List<ServerGrantInput> {
    if (role == "admin") return emptyList()
    return grants.map { (serverId, permission) -> ServerGrantInput(serverId = serverId, permission = permission) }
}

@Composable
private fun UsersAndInvitesPane(repo: DockDeployRepository, currentUserId: String, canWrite: Boolean) {
    val scope = rememberCoroutineScope()
    var users by remember { mutableStateOf<List<User>>(emptyList()) }
    var servers by remember { mutableStateOf<List<Server>>(emptyList()) }
    var invites by remember { mutableStateOf<List<Invitation>>(emptyList()) }
    var createdInvite by remember { mutableStateOf<CreatedInvitation?>(null) }
    var showInviteDialog by remember { mutableStateOf(false) }
    var showAddUserDialog by remember { mutableStateOf(false) }
    var scopingUser by remember { mutableStateOf<User?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    fun load() {
        scope.launch {
            runCatching { repo.listUsers() }.onSuccess { users = it }.onFailure { error = it.message }
            runCatching { repo.listServers() }.onSuccess { servers = it }
            runCatching { repo.listInvitations() }.onSuccess { invites = it }
        }
    }
    LaunchedEffect(Unit) { load() }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (notice != null) AlertBanner(message = notice!!, tone = BadgeTone.SUCCESS, onDismiss = { notice = null })
        if (error != null) AlertBanner(message = error!!, tone = BadgeTone.DANGER, onDismiss = { error = null })
        createdInvite?.let { inv ->
            CopyableCodeBox(text = inv.url, label = "Share Invitation Link (${inv.email})")
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Users (${users.size})", style = MaterialTheme.typography.titleMedium)
            if (canWrite) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showInviteDialog = true }) { Text("Invite") }
                    Button(onClick = { showAddUserDialog = true }) { Text("Add User") }
                }
            }
        }

        users.forEach { u ->
            DockCard(contentPadding = 12.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(u.name, style = MaterialTheme.typography.titleSmall)
                            Text("${u.email} • ${u.role} • ${u.status}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (canWrite && u.id != currentUserId) {
                            IconButton(onClick = {
                                scope.launch { runCatching { repo.deleteUser(u.id) }.onSuccess { load() }.onFailure { error = it.message } }
                            }) { Icon(Icons.Rounded.DeleteOutline, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error) }
                        }
                    }
                    if (canWrite && u.id != currentUserId) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (u.role != "admin") {
                                OutlinedButton(onClick = { scopingUser = u }) {
                                    Icon(Icons.Rounded.Dns, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Server Access")
                                }
                            }
                            val nextStatus = if (u.status == "active") "suspended" else "active"
                            TextButton(onClick = {
                                scope.launch { runCatching { repo.updateUser(u.id, u.name, u.role, nextStatus) }.onSuccess { load() }.onFailure { error = it.message } }
                            }) { Text(if (u.status == "active") "Suspend" else "Activate") }
                        }
                    }
                }
            }
        }

        if (invites.isNotEmpty()) {
            Text("Pending Invitations (${invites.size})", style = MaterialTheme.typography.titleSmall)
            invites.forEach { inv ->
                DockCard(contentPadding = 12.dp) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(inv.email, style = MaterialTheme.typography.bodySmall)
                            Text("Role: ${inv.role}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (canWrite) {
                            IconButton(onClick = { scope.launch { runCatching { repo.deleteInvitation(inv.id) }.onSuccess { load() } } }) {
                                Icon(Icons.Rounded.Close, contentDescription = "Revoke invite")
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddUserDialog) {
        var name by remember { mutableStateOf("") }
        var email by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        var role by remember { mutableStateOf("member") }
        var grants by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
        var busy by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showAddUserDialog = false },
            title = { Text("Add User & Scope Servers") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = email, onValueChange = { email = it }, label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Password (min 12 chars)") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("admin", "member", "viewer").forEach { r ->
                            FilterChip(selected = role == r, onClick = { role = r }, label = { Text(r) })
                        }
                    }
                    ServerScopePicker(role = role, servers = servers, grants = grants, onChange = { grants = it })
                }
            },
            confirmButton = {
                Button(
                    enabled = !busy && name.isNotBlank() && email.isNotBlank() && password.length >= 12,
                    onClick = {
                        scope.launch {
                            busy = true
                            runCatching { repo.createUser(email.trim(), name.trim(), password, role, toServerGrantInputs(role, grants)) }
                                .onSuccess {
                                    showAddUserDialog = false
                                    notice = "Created user ${it.email}."
                                    load()
                                }
                                .onFailure { error = it.message }
                            busy = false
                        }
                    },
                ) { Text(if (busy) "Creating..." else "Create User") }
            },
            dismissButton = { TextButton(onClick = { showAddUserDialog = false }) { Text("Cancel") } },
        )
    }

    if (showInviteDialog) {
        var email by remember { mutableStateOf("") }
        var name by remember { mutableStateOf("") }
        var role by remember { mutableStateOf("member") }
        var grants by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
        AlertDialog(
            onDismissRequest = { showInviteDialog = false },
            title = { Text("Invite User") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedTextField(value = email, onValueChange = { email = it }, label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("admin", "member", "viewer").forEach { r ->
                            FilterChip(selected = role == r, onClick = { role = r }, label = { Text(r) })
                        }
                    }
                    ServerScopePicker(role = role, servers = servers, grants = grants, onChange = { grants = it })
                }
            },
            confirmButton = {
                Button(
                    enabled = email.isNotBlank(),
                    onClick = {
                        showInviteDialog = false
                        scope.launch {
                            runCatching { repo.createInvitation(email.trim(), name.trim(), role, toServerGrantInputs(role, grants)) }
                                .onSuccess { createdInvite = it; load() }
                                .onFailure { error = it.message }
                        }
                    },
                ) { Text("Create Invite") }
            },
            dismissButton = { TextButton(onClick = { showInviteDialog = false }) { Text("Cancel") } },
        )
    }

    if (scopingUser != null) {
        val target = scopingUser!!
        var grants by remember(target.id) { mutableStateOf<Map<String, String>>(emptyMap()) }
        var loadingGrants by remember(target.id) { mutableStateOf(true) }
        var saving by remember { mutableStateOf(false) }

        LaunchedEffect(target.id) {
            runCatching { repo.listUserServers(target.id) }
                .onSuccess { list -> grants = list.associate { it.serverId to it.permission } }
                .onFailure { error = it.message }
            loadingGrants = false
        }

        AlertDialog(
            onDismissRequest = { scopingUser = null },
            title = { Text("Server Access • ${target.name}") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (loadingGrants) {
                        CircularProgressIndicator()
                    } else {
                        ServerScopePicker(role = target.role, servers = servers, grants = grants, onChange = { grants = it })
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = !loadingGrants && !saving,
                    onClick = {
                        scope.launch {
                            saving = true
                            runCatching { repo.setUserServers(target.id, toServerGrantInputs(target.role, grants)) }
                                .onSuccess {
                                    scopingUser = null
                                    notice = "Updated server access for ${target.email}."
                                }
                                .onFailure { error = it.message }
                            saving = false
                        }
                    },
                ) { Text(if (saving) "Saving..." else "Save Scope") }
            },
            dismissButton = { TextButton(onClick = { scopingUser = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun AuditLogPane(repo: DockDeployRepository) {
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<AuditEntry>>(emptyList()) }
    var nextBefore by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun load(before: String? = null) {
        scope.launch {
            runCatching { repo.listAudit(50, before) }
                .onSuccess { page ->
                    entries = if (before == null) page.entries else entries + page.entries
                    nextBefore = page.before
                }
                .onFailure { error = it.message }
        }
    }
    LaunchedEffect(Unit) { load() }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (error != null) AlertBanner(message = error!!, tone = BadgeTone.DANGER)
        entries.forEach { e ->
            DockCard(contentPadding = 12.dp) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(e.action, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), modifier = Modifier.weight(1f))
                    StatusBadge(
                        label = e.status.toString(),
                        tone = when {
                            e.status >= 500 -> BadgeTone.DANGER
                            e.status == 401 || e.status == 403 -> BadgeTone.WARNING
                            e.status >= 400 -> BadgeTone.NEUTRAL
                            else -> BadgeTone.SUCCESS
                        },
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "${e.actorEmail.ifBlank { "anonymous" }} • ${e.ip} • ${Formatters.formatRelative(e.createdAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (nextBefore != null) {
            OutlinedButton(onClick = { load(nextBefore) }, modifier = Modifier.fillMaxWidth()) {
                Text("Load Older Entries")
            }
        }
    }
}
