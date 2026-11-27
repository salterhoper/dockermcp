package tech.codesky.dockdeploy.ui.domains

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import tech.codesky.dockdeploy.data.model.*
import tech.codesky.dockdeploy.data.network.ApiException
import tech.codesky.dockdeploy.data.repository.DockDeployRepository
import tech.codesky.dockdeploy.ui.components.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DomainsScreen(
    repo: DockDeployRepository,
    user: CurrentUser,
    widthClass: WindowWidthSizeClass,
    onSessionExpired: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var domains by remember { mutableStateOf<List<Domain>>(emptyList()) }
    var servers by remember { mutableStateOf<List<Server>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var search by remember { mutableStateOf("") }
    var selectedServerId by remember { mutableStateOf<String?>(null) }

    var showAdd by remember { mutableStateOf(false) }
    var configDomain by remember { mutableStateOf<Domain?>(null) }
    var deleteDomain by remember { mutableStateOf<Domain?>(null) }
    var sudoAction by remember { mutableStateOf<Pair<Domain, String>?>(null) } // domain to "ssl"|"sync"
    var autoDetecting by remember { mutableStateOf(false) }

    val canWrite = user.can("domain:write")

    suspend fun load(isRefresh: Boolean) {
        if (isRefresh) refreshing = true else loading = true
        error = null
        try {
            domains = repo.listDomains(serverId = selectedServerId)
            servers = repo.listServers()
        } catch (e: ApiException) {
            if (e.isAuthFailure) onSessionExpired() else error = e.message
        } finally {
            loading = false
            refreshing = false
        }
    }

    LaunchedEffect(selectedServerId) { load(false) }

    val filtered = remember(domains, search) {
        val q = search.trim().lowercase()
        if (q.isEmpty()) domains else domains.filter { it.hostname.lowercase().contains(q) }
    }

    val columns = if (widthClass == WindowWidthSizeClass.Compact) 1 else 2

    Scaffold(
        floatingActionButton = {
            if (canWrite && servers.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { showAdd = true },
                    icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                    text = { Text("Add Domain") },
                )
            }
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { scope.launch { load(true) } },
            modifier = Modifier.fillMaxSize().padding(padding),
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
                        placeholder = { Text("Filter hostnames...") },
                        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    if (canWrite && servers.isNotEmpty()) {
                        FilledTonalButton(
                            enabled = !autoDetecting,
                            onClick = {
                                scope.launch {
                                    autoDetecting = true
                                    runCatching { repo.autoDetectAllServers() }
                                        .onSuccess { load(true) }
                                        .onFailure { error = it.message }
                                    autoDetecting = false
                                }
                            },
                        ) {
                            Icon(Icons.Rounded.AutoFixHigh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(if (autoDetecting) "Scanning..." else "Detect")
                        }
                    }
                }

                if (servers.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        FilterChip(
                            selected = selectedServerId == null,
                            onClick = { selectedServerId = null },
                            label = { Text("All Servers") },
                        )
                        servers.forEach { sv ->
                            FilterChip(
                                selected = selectedServerId == sv.id,
                                onClick = { selectedServerId = sv.id },
                                label = { Text(sv.name) },
                            )
                        }
                    }
                }

                if (error != null) AlertBanner(message = error!!, tone = BadgeTone.DANGER, onDismiss = { error = null })

                when {
                    loading -> Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    filtered.isEmpty() -> EmptyStateView(
                        icon = Icons.Rounded.Language,
                        title = "No domains configured",
                        description = "Point a hostname at an upstream port on any server and optionally issue Let's Encrypt TLS.",
                        actionLabel = if (canWrite && servers.isNotEmpty()) "Add Domain" else null,
                        onAction = if (canWrite && servers.isNotEmpty()) { { showAdd = true } } else null,
                    )
                    else -> {
                        ResponsiveGrid(items = filtered, columns = columns) { d ->
                            DockCard(contentPadding = 14.dp) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                        Text(
                                            d.hostname,
                                            style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            "${d.serverName ?: "server"} • :${d.upstreamPort}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    StatusBadge(label = statusLabel(d.status), tone = deploymentStatusTone(d.status))
                                }

                                if (d.statusMessage.isNotBlank() && d.status != "active") {
                                    Spacer(Modifier.height(6.dp))
                                    Text(d.statusMessage, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                                }

                                Spacer(Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    StatusBadge(
                                        label = if (d.sslMode == "letsencrypt") "Let's Encrypt" else "HTTP only",
                                        tone = if (d.sslMode == "letsencrypt") BadgeTone.INFO else BadgeTone.NEUTRAL,
                                        showDot = false,
                                    )
                                    if (d.websocket) {
                                        StatusBadge(label = "WebSocket", tone = BadgeTone.SUCCESS, showDot = false)
                                    }
                                }

                                Spacer(Modifier.height(10.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    OutlinedButton(onClick = { configDomain = d }) {
                                        Icon(Icons.Rounded.Code, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Nginx")
                                    }
                                    if (canWrite) {
                                        OutlinedButton(onClick = {
                                            scope.launch {
                                                try {
                                                    repo.syncDomain(d.id)
                                                    load(true)
                                                } catch (e: ApiException) {
                                                    if (e.requiresSudoPassword) sudoAction = d to "sync"
                                                    else error = e.message
                                                }
                                            }
                                        }) { Text("Sync") }
                                        OutlinedButton(onClick = {
                                            scope.launch {
                                                try {
                                                    repo.issueDomainSSL(d.id)
                                                    load(true)
                                                } catch (e: ApiException) {
                                                    if (e.requiresSudoPassword) sudoAction = d to "ssl"
                                                    else error = e.message
                                                }
                                            }
                                        }) { Text("Issue SSL") }
                                        TextButton(onClick = { deleteDomain = d }) {
                                            Text("Delete", color = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(64.dp))
                    }
                }
            }
        }
    }

    if (showAdd) {
        AddDomainDialog(
            repo = repo,
            onDismiss = { showAdd = false },
            onCreated = { showAdd = false; scope.launch { load(true) } },
        )
    }

    if (configDomain != null) {
        NginxConfigDialog(domain = configDomain!!, onDismiss = { configDomain = null })
    }

    if (sudoAction != null) {
        val (domain, op) = sudoAction!!
        SudoPromptDialog(
            onDismiss = { sudoAction = null },
            onConfirm = { pass, save ->
                sudoAction = null
                scope.launch {
                    runCatching {
                        if (op == "ssl") repo.issueDomainSSL(domain.id, pass, save)
                        else repo.syncDomain(domain.id, pass, save)
                    }.onSuccess { load(true) }.onFailure { error = it.message }
                }
            },
        )
    }

    if (deleteDomain != null) {
        ConfirmActionDialog(
            title = "Remove ${deleteDomain!!.hostname}?",
            message = "This removes the Nginx virtual host from the server.",
            confirmLabel = "Delete",
            destructive = true,
            onDismiss = { deleteDomain = null },
            onConfirm = {
                val id = deleteDomain!!.id
                deleteDomain = null
                scope.launch {
                    runCatching { repo.deleteDomain(id) }
                        .onSuccess { load(true) }
                        .onFailure { error = it.message }
                }
            },
        )
    }
}

@Composable
fun AddDomainDialog(
    repo: DockDeployRepository,
    defaultServerId: String = "",
    defaultDeploymentId: String = "",
    defaultUpstreamPort: String = "",
    onDismiss: () -> Unit,
    onCreated: (Domain) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var servers by remember { mutableStateOf<List<Server>>(emptyList()) }
    var deployments by remember { mutableStateOf<List<Deployment>>(emptyList()) }

    var serverId by remember { mutableStateOf(defaultServerId) }
    var deploymentId by remember { mutableStateOf(defaultDeploymentId) }
    var hostname by remember { mutableStateOf("") }
    var upstreamPort by remember { mutableStateOf(defaultUpstreamPort) }
    var websocket by remember { mutableStateOf(false) }
    var enableSsl by remember { mutableStateOf(true) }
    var sudoPassword by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        servers = runCatching { repo.listServers() }.getOrDefault(emptyList())
        deployments = runCatching { repo.listDeployments() }.getOrDefault(emptyList())
        if (serverId.isBlank()) serverId = servers.firstOrNull()?.id.orEmpty()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Domain") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (error != null) AlertBanner(message = error!!, tone = BadgeTone.DANGER)

                Text("Server", style = MaterialTheme.typography.labelMedium)
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    servers.forEach { sv ->
                        FilterChip(selected = serverId == sv.id, onClick = { serverId = sv.id }, label = { Text(sv.name) })
                    }
                }

                val availDeps = deployments.filter { serverId.isBlank() || it.serverId == serverId }
                if (availDeps.isNotEmpty()) {
                    Text("Link Deployment (optional)", style = MaterialTheme.typography.labelMedium)
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = deploymentId.isBlank(), onClick = { deploymentId = "" }, label = { Text("Custom Port") })
                        availDeps.forEach { d ->
                            FilterChip(
                                selected = deploymentId == d.id,
                                onClick = {
                                    deploymentId = d.id
                                    if (d.hostPort != null) upstreamPort = d.hostPort.toString()
                                },
                                label = { Text(d.name) },
                            )
                        }
                    }
                }

                OutlinedTextField(value = hostname, onValueChange = { hostname = it }, label = { Text("Hostname (e.g. app.example.com)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = upstreamPort,
                    onValueChange = { upstreamPort = it },
                    label = { Text("Upstream Port") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = enableSsl, onCheckedChange = { enableSsl = it })
                    Text("Issue Let's Encrypt TLS certificate", style = MaterialTheme.typography.bodySmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = websocket, onCheckedChange = { websocket = it })
                    Text("Enable WebSocket proxying", style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(
                    value = sudoPassword,
                    onValueChange = { sudoPassword = it },
                    label = { Text("Sudo password (if needed)") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                enabled = !busy && serverId.isNotBlank() && hostname.isNotBlank(),
                onClick = {
                    scope.launch {
                        busy = true
                        error = null
                        try {
                            val created = repo.createDomain(
                                CreateDomainRequest(
                                    serverId = serverId,
                                    deploymentId = deploymentId.takeIf { it.isNotBlank() },
                                    hostname = hostname.trim().lowercase(),
                                    upstreamPort = upstreamPort.toIntOrNull() ?: 80,
                                    sslMode = if (enableSsl) "letsencrypt" else "none",
                                    websocket = websocket,
                                    sudoPassword = sudoPassword.takeIf { it.isNotBlank() },
                                    saveSudo = true,
                                ),
                            )
                            onCreated(created)
                        } catch (e: ApiException) {
                            error = e.message
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text(if (busy) "Creating..." else "Create Domain") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun NginxConfigDialog(
    domain: Domain,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nginx Config • ${domain.hostname}") },
        text = {
            Column(modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                CopyableCodeBox(text = domain.configRendered.ifBlank { "# Config not rendered yet" })
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
