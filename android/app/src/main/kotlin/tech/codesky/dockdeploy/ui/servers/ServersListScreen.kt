package tech.codesky.dockdeploy.ui.servers

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import tech.codesky.dockdeploy.data.model.*
import tech.codesky.dockdeploy.data.network.ApiException
import tech.codesky.dockdeploy.data.repository.DockDeployRepository
import tech.codesky.dockdeploy.ui.components.*
import tech.codesky.dockdeploy.util.Formatters

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServersListScreen(
    repo: DockDeployRepository,
    user: CurrentUser,
    widthClass: WindowWidthSizeClass,
    selectedServerId: String? = null,
    onSelectServer: (Server) -> Unit,
    onSessionExpired: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var servers by remember { mutableStateOf<List<Server>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var search by remember { mutableStateOf("") }

    var showAddWizard by remember { mutableStateOf(false) }
    var autoDetectingAll by remember { mutableStateOf(false) }
    var autoDetectReports by remember { mutableStateOf<List<DiscoveryReport>?>(null) }

    val canWrite = user.can("server:write")

    suspend fun load(isRefresh: Boolean) {
        if (isRefresh) refreshing = true else loading = true
        error = null
        try {
            servers = repo.listServers()
        } catch (e: ApiException) {
            if (e.isAuthFailure) onSessionExpired() else error = e.message
        } catch (e: Exception) {
            error = e.message ?: "Failed to load servers."
        } finally {
            loading = false
            refreshing = false
        }
    }

    LaunchedEffect(Unit) { load(false) }

    val filtered = remember(servers, search) {
        val q = search.trim().lowercase()
        if (q.isEmpty()) servers
        else servers.filter {
            it.name.lowercase().contains(q) || it.host.lowercase().contains(q)
        }
    }

    val columns = when (widthClass) {
        WindowWidthSizeClass.Expanded -> 2
        WindowWidthSizeClass.Medium -> 2
        else -> 1
    }

    Scaffold(
        floatingActionButton = {
            if (canWrite) {
                ExtendedFloatingActionButton(
                    onClick = { showAddWizard = true },
                    icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                    text = { Text("Add Server") },
                )
            }
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { scope.launch { load(true) } },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = search,
                        onValueChange = { search = it },
                        placeholder = { Text("Filter servers...") },
                        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    if (canWrite && servers.isNotEmpty()) {
                        FilledTonalButton(
                            enabled = !autoDetectingAll,
                            onClick = {
                                scope.launch {
                                    autoDetectingAll = true
                                    error = null
                                    try {
                                        autoDetectReports = repo.autoDetectAllServers()
                                        load(true)
                                    } catch (e: ApiException) {
                                        error = e.message
                                    } finally {
                                        autoDetectingAll = false
                                    }
                                }
                            },
                        ) {
                            if (autoDetectingAll) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Rounded.AutoFixHigh, contentDescription = null, modifier = Modifier.size(18.dp))
                            }
                            Spacer(Modifier.width(6.dp))
                            Text("Auto-Detect")
                        }
                    }
                }

                if (error != null) {
                    AlertBanner(message = error!!, tone = BadgeTone.DANGER, onDismiss = { error = null })
                }

                when {
                    loading -> {
                        Box(modifier = Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                    filtered.isEmpty() -> {
                        EmptyStateView(
                            icon = Icons.Rounded.Dns,
                            title = if (servers.isEmpty()) "No servers connected" else "No matching servers",
                            description = if (servers.isEmpty()) {
                                if (canWrite) "Add a machine with SSH credentials or local Docker socket to discover containers."
                                else "No servers have been shared with you yet. Ask an administrator for access."
                            } else "Try another search term.",
                            actionLabel = if (servers.isEmpty() && canWrite) "Add Server" else null,
                            onAction = if (servers.isEmpty() && canWrite) { { showAddWizard = true } } else null,
                        )
                    }
                    else -> {
                        ResponsiveGrid(items = filtered, columns = columns) { server ->
                            val caps = Formatters.parseCapabilities(server.capabilities)
                            val isSelected = server.id == selectedServerId
                            DockCard(
                                onClick = { onSelectServer(server) },
                                borderColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.Top,
                                ) {
                                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                        Text(
                                            text = server.name,
                                            style = MaterialTheme.typography.titleMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = if (server.authMethod == "local") {
                                                "Local Docker host"
                                            } else {
                                                "${server.username}@${server.host}${if (server.port != 22) ":${server.port}" else ""}"
                                            },
                                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    StatusBadge(
                                        label = statusLabel(server.status),
                                        tone = serverStatusTone(server.status),
                                    )
                                }

                                HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outlineVariant)

                                KeyValueRow("Docker", caps?.dockerVersion?.ifBlank { "—" } ?: "—", mono = true)
                                KeyValueRow("Last seen", Formatters.formatRelative(server.lastSeenAt))
                            }
                        }
                        Spacer(Modifier.height(64.dp))
                    }
                }
            }
        }
    }

    if (showAddWizard) {
        AddServerWizardDialog(
            repo = repo,
            onDismiss = { showAddWizard = false },
            onCreated = { created ->
                showAddWizard = false
                scope.launch { load(true) }
                onSelectServer(created.server)
            },
        )
    }

    if (autoDetectReports != null) {
        DiscoveryReportDialog(
            reports = autoDetectReports!!,
            onDismiss = { autoDetectReports = null },
        )
    }
}

@Composable
fun DiscoveryReportDialog(
    reports: List<DiscoveryReport>,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.AutoFixHigh, contentDescription = null) },
        title = { Text("Auto-Detection Complete") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                reports.forEach { report ->
                    DockCard(contentPadding = 12.dp) {
                        Text(report.serverName.ifBlank { "Server" }, style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Domains: ${report.domains.size} • Deployments: ${report.deployments.size} • Git Creds: ${report.credentials.size}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        report.errors?.takeIf { it.isNotEmpty() }?.let { errs ->
                            Spacer(Modifier.height(6.dp))
                            Text(errs.joinToString("\n"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text("Done") }
        },
    )
}

@Composable
fun AddServerWizardDialog(
    repo: DockDeployRepository,
    onDismiss: () -> Unit,
    onCreated: (CreateServerResult) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf("address") } // address, verify, credentials
    var connType by remember { mutableStateOf("ssh") } // ssh, local

    var name by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("22") }
    var dockerSocket by remember { mutableStateOf("/var/run/docker.sock") }

    var fingerprint by remember { mutableStateOf("") }
    var keyType by remember { mutableStateOf("") }

    var username by remember { mutableStateOf("root") }
    var authMethod by remember { mutableStateOf("key") } // key, password
    var password by remember { mutableStateOf("") }
    var privateKey by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var sudoPassword by remember { mutableStateOf("") }

    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when (step) {
                    "verify" -> "Verify SSH Host Key"
                    "credentials" -> "SSH Credentials"
                    else -> "Add Server"
                },
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (error != null) {
                    AlertBanner(message = error!!, tone = BadgeTone.DANGER)
                }

                when (step) {
                    "address" -> {
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            SegmentedButton(
                                selected = connType == "ssh",
                                onClick = { connType = "ssh" },
                                shape = SegmentedButtonDefaults.itemShape(0, 2),
                                label = { Text("Remote SSH") },
                            )
                            SegmentedButton(
                                selected = connType == "local",
                                onClick = { connType = "local" },
                                shape = SegmentedButtonDefaults.itemShape(1, 2),
                                label = { Text("Local Socket") },
                            )
                        }

                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text("Display Name") },
                            placeholder = { Text(if (connType == "local") "Local Docker" else "prod-eu-1") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )

                        if (connType == "ssh") {
                            OutlinedTextField(
                                value = host,
                                onValueChange = { host = it },
                                label = { Text("Host / IP") },
                                placeholder = { Text("203.0.113.10") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = port,
                                onValueChange = { port = it },
                                label = { Text("SSH Port") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            OutlinedTextField(
                                value = dockerSocket,
                                onValueChange = { dockerSocket = it },
                                label = { Text("Docker Socket Path") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }

                    "verify" -> {
                        Text(
                            text = "Confirm that this $keyType host key fingerprint matches your server before sending any credentials.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        CopyableCodeBox(text = fingerprint, label = "Host Key Fingerprint")
                    }

                    "credentials" -> {
                        OutlinedTextField(
                            value = username,
                            onValueChange = { username = it },
                            label = { Text("SSH Username") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )

                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            SegmentedButton(
                                selected = authMethod == "key",
                                onClick = { authMethod = "key" },
                                shape = SegmentedButtonDefaults.itemShape(0, 2),
                                label = { Text("Private Key") },
                            )
                            SegmentedButton(
                                selected = authMethod == "password",
                                onClick = { authMethod = "password" },
                                shape = SegmentedButtonDefaults.itemShape(1, 2),
                                label = { Text("Password") },
                            )
                        }

                        if (authMethod == "key") {
                            OutlinedTextField(
                                value = privateKey,
                                onValueChange = { privateKey = it },
                                label = { Text("PEM / OpenSSH Private Key") },
                                minLines = 4,
                                maxLines = 6,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = passphrase,
                                onValueChange = { passphrase = it },
                                label = { Text("Key Passphrase (optional)") },
                                visualTransformation = PasswordVisualTransformation(),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            OutlinedTextField(
                                value = password,
                                onValueChange = { password = it },
                                label = { Text("SSH Password") },
                                visualTransformation = PasswordVisualTransformation(),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }

                        OutlinedTextField(
                            value = sudoPassword,
                            onValueChange = { sudoPassword = it },
                            label = { Text("Sudo Password (optional)") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        },
        confirmButton = {
            when (step) {
                "address" -> Button(
                    enabled = !busy && (connType == "local" || (host.isNotBlank() && name.isNotBlank())),
                    onClick = {
                        scope.launch {
                            busy = true
                            error = null
                            try {
                                if (connType == "local") {
                                    val res = repo.createServer(
                                        CreateServerRequest(
                                            name = name.trim().ifBlank { "Local Docker" },
                                            host = "localhost",
                                            port = 0,
                                            username = "local",
                                            authMethod = "local",
                                            dockerSocket = dockerSocket.trim().ifBlank { "/var/run/docker.sock" },
                                            hostKeyFingerprint = "local",
                                        ),
                                    )
                                    onCreated(res)
                                } else {
                                    val fp = repo.fingerprintServer(host.trim(), port.toIntOrNull() ?: 22)
                                    fingerprint = fp.fingerprint
                                    keyType = fp.keyType
                                    step = "verify"
                                }
                            } catch (e: ApiException) {
                                error = e.message
                            } finally {
                                busy = false
                            }
                        }
                    },
                ) {
                    if (busy) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Text(if (connType == "local") "Connect Local" else "Fetch Host Key")
                }

                "verify" -> Button(onClick = { step = "credentials" }) {
                    Text("Trust & Continue")
                }

                "credentials" -> Button(
                    enabled = !busy && username.isNotBlank() &&
                        ((authMethod == "key" && privateKey.isNotBlank()) || (authMethod == "password" && password.isNotBlank())),
                    onClick = {
                        scope.launch {
                            busy = true
                            error = null
                            try {
                                val res = repo.createServer(
                                    CreateServerRequest(
                                        name = name.trim(),
                                        host = host.trim(),
                                        port = port.toIntOrNull() ?: 22,
                                        username = username.trim(),
                                        authMethod = authMethod,
                                        password = if (authMethod == "password") password else null,
                                        privateKey = if (authMethod == "key") privateKey else null,
                                        passphrase = if (authMethod == "key" && passphrase.isNotBlank()) passphrase else null,
                                        sudoPassword = sudoPassword.takeIf { it.isNotBlank() },
                                        hostKeyFingerprint = fingerprint,
                                    ),
                                )
                                onCreated(res)
                            } catch (e: ApiException) {
                                error = e.message
                            } finally {
                                busy = false
                            }
                        }
                    },
                ) {
                    if (busy) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Text("Connect Server")
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    when (step) {
                        "credentials" -> step = "verify"
                        "verify" -> step = "address"
                        else -> onDismiss()
                    }
                },
            ) {
                Text(if (step == "address") "Cancel" else "Back")
            }
        },
    )
}
