package tech.codesky.dockdeploy.ui.auth

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tech.codesky.dockdeploy.data.model.Health
import tech.codesky.dockdeploy.data.preferences.SavedHost
import tech.codesky.dockdeploy.ui.components.AlertBanner
import tech.codesky.dockdeploy.ui.components.BadgeTone
import tech.codesky.dockdeploy.ui.components.DockCard
import tech.codesky.dockdeploy.ui.components.StatusBadge
import tech.codesky.dockdeploy.util.Formatters

@Composable
private fun AuthCenteredShell(
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 460.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            BrandHeader()
            content()
        }
    }
}

@Composable
private fun BrandHeader() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Dns,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(24.dp),
            )
        }
        Column {
            Text(
                text = "DockDeploy",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            )
            Text(
                text = "Agentless Docker & Server Control Plane",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun ConnectHostScreen(
    savedHosts: List<SavedHost>,
    uiState: AuthUiState,
    onConnect: (String) -> Unit,
    onRemoveSavedHost: (String) -> Unit,
) {
    var hostInput by remember {
        mutableStateOf(savedHosts.firstOrNull()?.url ?: "dc.codesky.tech")
    }

    AuthCenteredShell {
        DockCard {
            Text(
                text = "Connect to your instance",
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Enter the host URL where DockDeploy is running (for example dc.codesky.tech or http://192.168.1.10:8080).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(16.dp))

            if (!uiState.error.isNullOrBlank()) {
                AlertBanner(message = uiState.error, tone = BadgeTone.DANGER)
                Spacer(Modifier.height(12.dp))
            }

            OutlinedTextField(
                value = hostInput,
                onValueChange = { hostInput = it },
                label = { Text("DockDeploy Host URL") },
                placeholder = { Text("dc.codesky.tech") },
                leadingIcon = { Icon(Icons.Rounded.Public, contentDescription = null) },
                trailingIcon = {
                    if (hostInput.isNotEmpty()) {
                        IconButton(onClick = { hostInput = "" }) {
                            Icon(Icons.Rounded.Clear, contentDescription = "Clear")
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Go,
                ),
                keyboardActions = KeyboardActions(
                    onGo = { if (!uiState.isBusy) onConnect(hostInput) },
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(10.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                AssistChip(
                    onClick = { hostInput = "dc.codesky.tech" },
                    label = { Text("dc.codesky.tech", style = MaterialTheme.typography.labelSmall) },
                    leadingIcon = {
                        Icon(Icons.Rounded.CloudQueue, contentDescription = null, modifier = Modifier.size(16.dp))
                    },
                )
                AssistChip(
                    onClick = { hostInput = "http://10.0.2.2:8080" },
                    label = { Text("Emulator :8080", style = MaterialTheme.typography.labelSmall) },
                    leadingIcon = {
                        Icon(Icons.Rounded.Computer, contentDescription = null, modifier = Modifier.size(16.dp))
                    },
                )
            }

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = { onConnect(hostInput) },
                enabled = !uiState.isBusy && hostInput.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) {
                if (uiState.isBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("Checking /api/health...")
                } else {
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Connect")
                }
            }
        }

        if (savedHosts.isNotEmpty()) {
            DockCard {
                Text(
                    text = "Recent Instances",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    savedHosts.forEach { host ->
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable(enabled = !uiState.isBusy) { onConnect(host.url) },
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Icon(
                                    Icons.Rounded.Hub,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp),
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = host.url,
                                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (host.version.isNotBlank()) {
                                        Text(
                                            text = "v${host.version}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                IconButton(
                                    onClick = { onRemoveSavedHost(host.url) },
                                    modifier = Modifier.size(32.dp),
                                ) {
                                    Icon(
                                        Icons.Rounded.DeleteOutline,
                                        contentDescription = "Forget host",
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActiveHostBar(
    hostUrl: String,
    health: Health?,
    onSwitchHost: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Rounded.Dns,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = hostUrl,
                    style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (health != null) {
                    Text(
                        text = "Status: ${health.status} • v${health.version} • up ${Formatters.formatUptime(health.uptimeSeconds)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (health != null) {
                StatusBadge(
                    label = if (health.status == "ok") "Online" else health.status,
                    tone = if (health.status == "ok") BadgeTone.SUCCESS else BadgeTone.WARNING,
                )
            }
            TextButton(onClick = onSwitchHost, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text("Change")
            }
        }
    }
}

@Composable
fun LoginScreen(
    hostUrl: String,
    health: Health?,
    notice: String?,
    uiState: AuthUiState,
    onSwitchHost: () -> Unit,
    onLogin: (email: String, password: String) -> Unit,
    onVerify2FA: (code: String) -> Unit,
    onCancel2FA: () -> Unit,
    onLoginToken: (token: String) -> Unit,
    onPreviewInvite: (tokenOrUrl: String) -> Unit,
    onAcceptInvite: (name: String, password: String) -> Unit,
    onClearInvite: () -> Unit,
) {
    var mode by remember { mutableIntStateOf(0) } // 0 = Password, 1 = API Token, 2 = Invite

    AuthCenteredShell {
        ActiveHostBar(hostUrl = hostUrl, health = health, onSwitchHost = onSwitchHost)

        if (!notice.isNullOrBlank()) {
            AlertBanner(message = notice, tone = BadgeTone.WARNING)
        }

        if (uiState.twoFactorTempToken != null) {
            TwoFactorLoginCard(
                uiState = uiState,
                onVerify = onVerify2FA,
                onBack = onCancel2FA,
            )
            return@AuthCenteredShell
        }

        if (uiState.invitationPreview != null) {
            AcceptInviteFormCard(
                uiState = uiState,
                onAccept = onAcceptInvite,
                onCancel = onClearInvite,
            )
            return@AuthCenteredShell
        }

        DockCard {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                listOf("Sign In", "API Token", "Invite").forEachIndexed { idx, label ->
                    SegmentedButton(
                        selected = mode == idx,
                        onClick = { mode = idx },
                        shape = SegmentedButtonDefaults.itemShape(index = idx, count = 3),
                        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            if (!uiState.error.isNullOrBlank()) {
                AlertBanner(message = uiState.error, tone = BadgeTone.DANGER)
                Spacer(Modifier.height(12.dp))
            }

            when (mode) {
                0 -> CredentialsLoginForm(uiState = uiState, onLogin = onLogin)
                1 -> ApiTokenLoginForm(uiState = uiState, onLoginToken = onLoginToken)
                else -> InviteLookupForm(uiState = uiState, onPreviewInvite = onPreviewInvite)
            }
        }
    }
}

@Composable
private fun CredentialsLoginForm(
    uiState: AuthUiState,
    onLogin: (String, String) -> Unit,
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Sign in to your account", style = MaterialTheme.typography.titleMedium)

        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Email") },
            leadingIcon = { Icon(Icons.Rounded.Email, contentDescription = null) },
            isError = uiState.fieldErrors.containsKey("email"),
            supportingText = uiState.fieldErrors["email"]?.let { { Text(it) } },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Next,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            leadingIcon = { Icon(Icons.Rounded.Lock, contentDescription = null) },
            trailingIcon = {
                IconButton(onClick = { showPassword = !showPassword }) {
                    Icon(
                        if (showPassword) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        contentDescription = "Toggle password",
                    )
                }
            },
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            isError = uiState.fieldErrors.containsKey("password"),
            supportingText = uiState.fieldErrors["password"]?.let { { Text(it) } },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(
                onDone = { if (!uiState.isBusy) onLogin(email, password) },
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        Button(
            onClick = { onLogin(email, password) },
            enabled = !uiState.isBusy && email.isNotBlank() && password.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        ) {
            if (uiState.isBusy) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text("Sign in")
        }
    }
}

@Composable
private fun TwoFactorLoginCard(
    uiState: AuthUiState,
    onVerify: (String) -> Unit,
    onBack: () -> Unit,
) {
    var code by remember { mutableStateOf("") }
    var useRecovery by remember { mutableStateOf(false) }

    DockCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Rounded.VerifiedUser, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text("Two-Factor Verification", style = MaterialTheme.typography.titleLarge)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = if (useRecovery) {
                "Enter one of your 8-character backup recovery codes."
            } else {
                "Enter the 6-digit code from your authenticator app."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))

        if (!uiState.error.isNullOrBlank()) {
            AlertBanner(message = uiState.error, tone = BadgeTone.DANGER)
            Spacer(Modifier.height(12.dp))
        }

        OutlinedTextField(
            value = code,
            onValueChange = { code = it },
            label = { Text(if (useRecovery) "Recovery code" else "6-digit code") },
            placeholder = { Text(if (useRecovery) "xxxx-xxxx" else "123456") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (useRecovery) KeyboardType.Text else KeyboardType.Number,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(
                onDone = { if (!uiState.isBusy && code.isNotBlank()) onVerify(code) },
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = {
                useRecovery = !useRecovery
                code = ""
            }) {
                Text(if (useRecovery) "Use authenticator code" else "Use recovery code")
            }
        }

        Button(
            onClick = { onVerify(code) },
            enabled = !uiState.isBusy && code.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        ) {
            if (uiState.isBusy) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text("Verify & Sign in")
        }

        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Back to email & password")
        }
    }
}

@Composable
private fun ApiTokenLoginForm(
    uiState: AuthUiState,
    onLoginToken: (String) -> Unit,
) {
    var token by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Sign in with Personal API Token", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "Paste a personal token generated from DockDeploy Settings -> Tokens. It inherits your role permissions.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text("Bearer Token") },
            leadingIcon = { Icon(Icons.Rounded.Key, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { onLoginToken(token) },
            enabled = !uiState.isBusy && token.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        ) {
            Text("Authenticate with Token")
        }
    }
}

@Composable
private fun InviteLookupForm(
    uiState: AuthUiState,
    onPreviewInvite: (String) -> Unit,
) {
    var tokenInput by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Accept an Invitation", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "Paste the invitation link or token shared by your administrator.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = tokenInput,
            onValueChange = { tokenInput = it },
            label = { Text("Invitation URL or token") },
            leadingIcon = { Icon(Icons.Rounded.Link, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { onPreviewInvite(tokenInput) },
            enabled = !uiState.isBusy && tokenInput.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        ) {
            Text("Check Invitation")
        }
    }
}

@Composable
private fun AcceptInviteFormCard(
    uiState: AuthUiState,
    onAccept: (name: String, password: String) -> Unit,
    onCancel: () -> Unit,
) {
    val preview = uiState.invitationPreview ?: return
    var name by remember { mutableStateOf(preview.name) }
    var password by remember { mutableStateOf("") }

    DockCard {
        Text("Accept Invitation", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Invited as ${preview.email} (${preview.role})",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))

        if (!uiState.error.isNullOrBlank()) {
            AlertBanner(message = uiState.error, tone = BadgeTone.DANGER)
            Spacer(Modifier.height(12.dp))
        }

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Your name") },
            isError = uiState.fieldErrors.containsKey("name"),
            supportingText = uiState.fieldErrors["name"]?.let { { Text(it) } },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Choose password (min 12 chars)") },
            visualTransformation = PasswordVisualTransformation(),
            isError = uiState.fieldErrors.containsKey("password"),
            supportingText = uiState.fieldErrors["password"]?.let { { Text(it) } },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { onAccept(name, password) },
            enabled = !uiState.isBusy && name.isNotBlank() && password.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        ) {
            Text("Create Account")
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
            Text("Cancel")
        }
    }
}

@Composable
fun SetupAdminScreen(
    hostUrl: String,
    health: Health?,
    uiState: AuthUiState,
    onSwitchHost: () -> Unit,
    onSetup: (name: String, email: String, password: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    AuthCenteredShell {
        ActiveHostBar(hostUrl = hostUrl, health = health, onSwitchHost = onSwitchHost)

        DockCard {
            Text("Create First Administrator", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                text = "This instance needs its initial admin account. Nobody else can sign up unless invited.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))

            if (!uiState.error.isNullOrBlank()) {
                AlertBanner(message = uiState.error, tone = BadgeTone.DANGER)
                Spacer(Modifier.height(12.dp))
            }

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                isError = uiState.fieldErrors.containsKey("name"),
                supportingText = uiState.fieldErrors["name"]?.let { { Text(it) } },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Email") },
                isError = uiState.fieldErrors.containsKey("email"),
                supportingText = uiState.fieldErrors["email"]?.let { { Text(it) } },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password (at least 12 characters)") },
                visualTransformation = PasswordVisualTransformation(),
                isError = uiState.fieldErrors.containsKey("password"),
                supportingText = uiState.fieldErrors["password"]?.let { { Text(it) } },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { onSetup(name, email, password) },
                enabled = !uiState.isBusy && name.isNotBlank() && email.isNotBlank() && password.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) {
                if (uiState.isBusy) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text("Create Admin Account")
            }
        }
    }
}
