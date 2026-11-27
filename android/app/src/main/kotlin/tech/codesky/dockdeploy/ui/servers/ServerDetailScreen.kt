package tech.codesky.dockdeploy.ui.servers

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import tech.codesky.dockdeploy.data.model.*
import tech.codesky.dockdeploy.data.network.ApiException
import tech.codesky.dockdeploy.data.repository.DockDeployRepository
import tech.codesky.dockdeploy.ui.components.*
import tech.codesky.dockdeploy.ui.theme.LocalStatusColors
import tech.codesky.dockdeploy.ui.theme.TerminalBg
import tech.codesky.dockdeploy.ui.theme.TerminalFg
import tech.codesky.dockdeploy.util.Formatters

private val prettyJson = Json { prettyPrint = true }

private enum class ServerTab(val label: String) {
    CONTAINERS("Containers"),
    METRICS("Metrics & Health"),
    SECURITY("Security & Audits"),
    FILES("Files & Volumes"),
    BACKUPS("Databases & Backups"),
    IMAGES("Images"),
    VOLUMES("Volumes"),
    NETWORKS("Networks"),
    SYSTEM("System"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerDetailScreen(
    repo: DockDeployRepository,
    user: CurrentUser,
    serverId: String,
    showBack: Boolean,
    onBack: () -> Unit,
    onOpenAiForServer: (String) -> Unit,
    onDeleted: () -> Unit,
    onSessionExpired: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var server by remember(serverId) { mutableStateOf<Server?>(null) }
    var dockerInfo by remember(serverId) { mutableStateOf<DockerInfo?>(null) }
    var loading by remember(serverId) { mutableStateOf(true) }
    var error by remember(serverId) { mutableStateOf<String?>(null) }
    var notice by remember(serverId) { mutableStateOf<String?>(null) }

    var selectedTab by remember(serverId) { mutableStateOf(ServerTab.CONTAINERS) }
    var filesInitialPath by remember(serverId) { mutableStateOf("~") }

    var showTerminal by remember { mutableStateOf(false) }
    var showRootExec by remember { mutableStateOf(false) }
    var showSudoDialog by remember { mutableStateOf(false) }
    var showInstallDocker by remember { mutableStateOf(false) }
    var showEditServer by remember { mutableStateOf(false) }
    var showMembers by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var discoveryReport by remember { mutableStateOf<DiscoveryReport?>(null) }
    var logsFor by remember { mutableStateOf<Container?>(null) }
    var inspectJson by remember { mutableStateOf<Pair<String, String>?>(null) }
    var probing by remember { mutableStateOf(false) }
    var detecting by remember { mutableStateOf(false) }

    val canOperate = user.can("container:operate")
    val canWrite = user.can("server:write")
    val canDelete = user.can("server:delete")
    val canTerminal = canWrite || canOperate
    val canManageMembers = user.can("user:write")

    fun loadHeader() {
        scope.launch {
            loading = true
            error = null
            try {
                server = repo.getServer(serverId)
                dockerInfo = runCatching { repo.getDockerInfo(serverId) }.getOrNull()
            } catch (e: ApiException) {
                if (e.isAuthFailure) onSessionExpired() else error = e.message
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(serverId) { loadHeader() }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text(server?.name ?: "Server", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    server?.let { sv ->
                        Text(
                            text = if (sv.authMethod == "local") sv.dockerSocket else "${sv.username}@${sv.host}:${sv.port}",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            },
            navigationIcon = {
                if (showBack) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                }
            },
            actions = {
                if (canTerminal && server != null) {
                    IconButton(onClick = { showTerminal = true }) {
                        Icon(Icons.Rounded.Terminal, contentDescription = "SSH Terminal")
                    }
                }
                IconButton(onClick = { onOpenAiForServer(serverId) }) {
                    Icon(Icons.Rounded.AutoAwesome, contentDescription = "AI Diagnostics")
                }
                var menuOpen by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = "Server actions")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(if (probing) "Probing..." else "Re-check / Probe") },
                            leadingIcon = { Icon(Icons.Rounded.Refresh, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                scope.launch {
                                    probing = true
                                    runCatching { repo.probeServer(serverId) }
                                        .onSuccess {
                                            server = it.server
                                            notice = if (it.reachable) "Server is reachable." else "Probe completed with warnings."
                                        }
                                        .onFailure { error = it.message }
                                    probing = false
                                }
                            },
                        )
                        if (canWrite) {
                            DropdownMenuItem(
                                text = { Text(if (detecting) "Detecting..." else "Auto-Detect Stack") },
                                leadingIcon = { Icon(Icons.Rounded.AutoFixHigh, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    scope.launch {
                                        detecting = true
                                        runCatching { repo.autoDetectServer(serverId) }
                                            .onSuccess { discoveryReport = it }
                                            .onFailure { error = it.message }
                                        detecting = false
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Run Root Command") },
                                leadingIcon = { Icon(Icons.Rounded.Shield, contentDescription = null) },
                                onClick = { menuOpen = false; showRootExec = true },
                            )
                            DropdownMenuItem(
                                text = { Text("Sudo Password") },
                                leadingIcon = { Icon(Icons.Rounded.Key, contentDescription = null) },
                                onClick = { menuOpen = false; showSudoDialog = true },
                            )
                            DropdownMenuItem(
                                text = { Text("Install Docker") },
                                leadingIcon = { Icon(Icons.Rounded.Download, contentDescription = null) },
                                onClick = { menuOpen = false; showInstallDocker = true },
                            )
                            DropdownMenuItem(
                                text = { Text("Edit Server") },
                                leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                                onClick = { menuOpen = false; showEditServer = true },
                            )
                        }
                        if (canManageMembers) {
                            DropdownMenuItem(
                                text = { Text("Access & Members") },
                                leadingIcon = { Icon(Icons.Rounded.Group, contentDescription = null) },
                                onClick = { menuOpen = false; showMembers = true },
                            )
                        }
                        if (canDelete) {
                            DropdownMenuItem(
                                text = { Text("Remove Server", color = MaterialTheme.colorScheme.error) },
                                leadingIcon = { Icon(Icons.Rounded.DeleteOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                                onClick = { menuOpen = false; showDeleteConfirm = true },
                            )
                        }
                    }
                }
            },
        )

        val sv = server
        val caps = Formatters.parseCapabilities(sv?.capabilities)

        if (sv != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatusBadge(label = statusLabel(sv.status), tone = serverStatusTone(sv.status))
                dockerInfo?.let { info ->
                    Text(
                        text = "Docker ${info.serverVersion} • ${info.cpus} CPU • ${Formatters.formatBytes(info.memoryBytes)} • ${info.containersRunning}/${info.containers} running",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (sv?.statusMessage?.isNotBlank() == true) {
            Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                AlertBanner(
                    message = sv.statusMessage,
                    tone = if (sv.status == "online") BadgeTone.WARNING else BadgeTone.DANGER,
                )
            }
        }

        if (notice != null) {
            Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                AlertBanner(message = notice!!, tone = BadgeTone.INFO, onDismiss = { notice = null })
            }
        }

        if (error != null) {
            Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                AlertBanner(message = error!!, tone = BadgeTone.DANGER, onDismiss = { error = null })
            }
        }

        PrimaryScrollableTabRow(
            selectedTabIndex = selectedTab.ordinal,
            edgePadding = 12.dp,
        ) {
            ServerTab.entries.forEach { tab ->
                Tab(
                    selected = selectedTab == tab,
                    onClick = { selectedTab = tab },
                    text = { Text(tab.label, maxLines = 1) },
                )
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            if (loading && sv == null) {
                Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (sv != null) {
                when (selectedTab) {
                    ServerTab.CONTAINERS -> ContainersTabContent(
                        repo = repo,
                        serverId = serverId,
                        canOperate = canOperate,
                        onShowLogs = { logsFor = it },
                        onInspect = { container ->
                            scope.launch {
                                runCatching { repo.inspectContainer(serverId, container.id) }
                                    .onSuccess { obj ->
                                        val pretty = prettyJson.encodeToString(JsonObject.serializer(), obj)
                                        inspectJson = container.name to pretty
                                    }
                                    .onFailure { error = it.message }
                            }
                        },
                    )

                    ServerTab.METRICS -> MetricsTabContent(
                        repo = repo,
                        serverId = serverId,
                        onLaunchTerminal = if (canTerminal) { { showTerminal = true } } else null,
                    )

                    ServerTab.SECURITY -> SecurityTabContent(
                        repo = repo,
                        serverId = serverId,
                        canOperate = canOperate,
                    )

                    ServerTab.FILES -> ServerFilesTab(
                        repo = repo,
                        serverId = serverId,
                        initialPath = filesInitialPath,
                        canWrite = canWrite,
                    )

                    ServerTab.BACKUPS -> ServerBackupsTab(
                        repo = repo,
                        serverId = serverId,
                        canWrite = canWrite,
                    )

                    ServerTab.IMAGES -> ImagesTabContent(repo = repo, serverId = serverId, canOperate = canOperate)
                    ServerTab.VOLUMES -> VolumesTabContent(
                        repo = repo,
                        serverId = serverId,
                        onBrowseVolume = { mount ->
                            filesInitialPath = mount
                            selectedTab = ServerTab.FILES
                        },
                    )
                    ServerTab.NETWORKS -> NetworksTabContent(repo = repo, serverId = serverId)
                    ServerTab.SYSTEM -> SystemTabContent(
                        server = sv,
                        caps = caps,
                        canWrite = canWrite,
                        onInstallDocker = { showInstallDocker = true },
                    )
                }
            }
        }
    }

    if (showTerminal && server != null) {
        ServerTerminalDialog(
            repo = repo,
            serverId = serverId,
            serverName = server!!.name,
            onDismiss = { showTerminal = false },
        )
    }

    if (logsFor != null) {
        ContainerLogsDialog(
            repo = repo,
            serverId = serverId,
            container = logsFor!!,
            onDismiss = { logsFor = null },
        )
    }

    if (inspectJson != null) {
        AlertDialog(
            onDismissRequest = { inspectJson = null },
            title = { Text("Inspect • ${inspectJson!!.first}") },
            text = {
                SelectionContainer {
                    Text(
                        text = inspectJson!!.second,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                        modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    )
                }
            },
            confirmButton = { TextButton(onClick = { inspectJson = null }) { Text("Close") } },
        )
    }

    if (showRootExec) {
        RootExecDialog(
            repo = repo,
            serverId = serverId,
            onDismiss = { showRootExec = false },
        )
    }

    if (showSudoDialog) {
        SudoPromptDialog(
            title = "Set Server Sudo Password",
            description = "Encrypted at rest on the DockDeploy controller and used for Nginx, Certbot, and protected files.",
            onDismiss = { showSudoDialog = false },
            onConfirm = { pass, _ ->
                showSudoDialog = false
                scope.launch {
                    runCatching { repo.setServerSudoPassword(serverId, pass) }
                        .onSuccess {
                            server = it
                            notice = "Sudo password saved."
                        }
                        .onFailure { error = it.message }
                }
            },
        )
    }

    if (showInstallDocker) {
        InstallDockerDialog(
            repo = repo,
            serverId = serverId,
            onDismiss = { showInstallDocker = false },
            onInstalled = { loadHeader() },
        )
    }

    if (showEditServer && server != null) {
        var name by remember { mutableStateOf(server!!.name) }
        var socket by remember { mutableStateOf(server!!.dockerSocket) }
        AlertDialog(
            onDismissRequest = { showEditServer = false },
            title = { Text("Edit Server") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = socket, onValueChange = { socket = it }, label = { Text("Docker Socket") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                Button(
                    enabled = name.isNotBlank(),
                    onClick = {
                        showEditServer = false
                        scope.launch {
                            runCatching { repo.updateServer(serverId, name.trim(), socket.trim()) }
                                .onSuccess { server = it }
                                .onFailure { error = it.message }
                        }
                    },
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showEditServer = false }) { Text("Cancel") } },
        )
    }

    if (showMembers) {
        ServerMembersDialog(
            repo = repo,
            serverId = serverId,
            onDismiss = { showMembers = false },
        )
    }

    if (discoveryReport != null) {
        DiscoveryReportDialog(reports = listOf(discoveryReport!!), onDismiss = { discoveryReport = null })
    }

    if (showDeleteConfirm && server != null) {
        ConfirmActionDialog(
            title = "Remove ${server!!.name}?",
            message = "The server and its running containers are left untouched. Only the stored connection and credentials are deleted.",
            confirmLabel = "Remove",
            destructive = true,
            onDismiss = { showDeleteConfirm = false },
            onConfirm = {
                showDeleteConfirm = false
                scope.launch {
                    runCatching { repo.deleteServer(serverId) }
                        .onSuccess { onDeleted() }
                        .onFailure { error = it.message }
                }
            },
        )
    }
}

@Composable
private fun ContainersTabContent(
    repo: DockDeployRepository,
    serverId: String,
    canOperate: Boolean,
    onShowLogs: (Container) -> Unit,
    onInspect: (Container) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var containers by remember { mutableStateOf<List<Container>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var busyId by remember { mutableStateOf<String?>(null) }

    suspend fun fetch() {
        try {
            containers = repo.listContainers(serverId)
            error = null
        } catch (e: ApiException) {
            error = e.message
        } finally {
            loading = false
        }
    }

    LaunchedEffect(serverId) {
        fetch()
        while (true) {
            delay(10_000)
            fetch()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (error != null) AlertBanner(message = error!!, tone = BadgeTone.DANGER)
        if (loading) {
            Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (containers.isEmpty()) {
            EmptyStateView(
                icon = Icons.Rounded.Inventory2,
                title = "No containers on this server",
                description = "Create a deployment or start containers on this host.",
            )
        } else {
            containers.forEach { c ->
                DockCard(contentPadding = 14.dp) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top,
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(c.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                c.image,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        StatusBadge(label = statusLabel(c.state), tone = containerStateTone(c.state))
                    }

                    val portsStr = c.ports.filter { it.hostPort != null }.joinToString(" ") { "${it.hostPort}:${it.containerPort}" }
                    if (portsStr.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Ports: $portsStr",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        OutlinedButton(onClick = { onShowLogs(c) }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) {
                            Icon(Icons.AutoMirrored.Rounded.ReceiptLong, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Logs")
                        }
                        OutlinedButton(onClick = { onInspect(c) }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) {
                            Icon(Icons.Rounded.Info, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Inspect")
                        }
                        if (canOperate) {
                            val isBusy = busyId == c.id
                            fun act(verb: String) {
                                scope.launch {
                                    busyId = c.id
                                    runCatching { repo.containerAction(serverId, c.id, verb) }
                                        .onSuccess { fetch() }
                                        .onFailure { error = it.message }
                                    busyId = null
                                }
                            }
                            if (c.state == "running") {
                                FilledTonalButton(enabled = !isBusy, onClick = { act("stop") }) { Text("Stop") }
                                OutlinedButton(enabled = !isBusy, onClick = { act("pause") }) { Text("Pause") }
                            } else if (c.state == "paused") {
                                FilledTonalButton(enabled = !isBusy, onClick = { act("unpause") }) { Text("Unpause") }
                            } else {
                                FilledTonalButton(enabled = !isBusy, onClick = { act("start") }) { Text("Start") }
                            }
                            OutlinedButton(enabled = !isBusy, onClick = { act("restart") }) { Text("Restart") }
                            OutlinedButton(enabled = !isBusy, onClick = { act("kill") }) { Text("Kill") }
                            TextButton(enabled = !isBusy, onClick = { act("remove") }) {
                                Text("Remove", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContainerLogsDialog(
    repo: DockDeployRepository,
    serverId: String,
    container: Container,
    onDismiss: () -> Unit,
) {
    var lines by remember { mutableStateOf<List<ContainerLogLine>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var filter by remember { mutableStateOf("") }
    var follow by remember { mutableStateOf(true) }
    val scroll = rememberScrollState()
    val statusColors = LocalStatusColors.current

    LaunchedEffect(serverId, container.id) {
        try {
            repo.streamContainerLogs(serverId, container.id, tail = 250).collect { ev ->
                if (ev.type == "log") {
                    runCatching { repo.api.json.decodeFromString<ContainerLogLine>(ev.data) }.getOrNull()?.let { line ->
                        lines = (lines + line).takeLast(1000)
                    }
                } else if (ev.type == "error") {
                    error = ev.data
                }
            }
        } catch (e: Exception) {
            error = e.message
        }
    }

    LaunchedEffect(lines.size, follow) {
        if (follow) scroll.animateScrollTo(scroll.maxValue)
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("Logs • ${container.name}", style = MaterialTheme.typography.titleMedium)
                            Text(container.image, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, contentDescription = "Close") }
                    },
                    actions = {
                        FilterChip(selected = follow, onClick = { follow = !follow }, label = { Text("Follow") })
                    },
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).background(TerminalBg),
            ) {
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    placeholder = { Text("Filter log lines...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(8.dp),
                )
                if (error != null) {
                    AlertBanner(message = error!!, tone = BadgeTone.WARNING, modifier = Modifier.padding(8.dp))
                }
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(12.dp),
                ) {
                    val shown = if (filter.isBlank()) lines else lines.filter { it.text.contains(filter, ignoreCase = true) }
                    if (shown.isEmpty()) {
                        Text("Waiting for container output...", color = TerminalFg.copy(alpha = 0.6f), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    } else {
                        SelectionContainer {
                            Column {
                                shown.forEach { l ->
                                    Text(
                                        text = Formatters.stripAnsi(l.text),
                                        color = if (l.stream == "stderr") statusColors.danger else TerminalFg,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        lineHeight = 16.sp,
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
private fun MetricsTabContent(
    repo: DockDeployRepository,
    serverId: String,
    onLaunchTerminal: (() -> Unit)?,
) {
    val scope = rememberCoroutineScope()
    var metrics by remember { mutableStateOf<ServerMetrics?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    fun load() {
        scope.launch {
            loading = true
            error = null
            runCatching { repo.getServerMetrics(serverId) }
                .onSuccess { metrics = it }
                .onFailure { error = it.message }
            loading = false
        }
    }

    LaunchedEffect(serverId) { load() }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            metrics?.let { StatusBadge(label = statusLabel(it.health), tone = deploymentStatusTone(it.health)) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onLaunchTerminal != null) {
                    OutlinedButton(onClick = onLaunchTerminal) {
                        Icon(Icons.Rounded.Terminal, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("SSH")
                    }
                }
                FilledTonalButton(onClick = { load() }) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Refresh")
                }
            }
        }

        if (error != null) AlertBanner(message = error!!, tone = BadgeTone.DANGER)
        if (loading && metrics == null) {
            Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            val m = metrics ?: return@Column
            if (m.healthIssues.isNotEmpty()) {
                AlertBanner(message = m.healthIssues.joinToString("\n"), tone = BadgeTone.WARNING)
            }

            DockCard {
                Text("System", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                KeyValueRow("Hostname", m.system.hostname, mono = true)
                KeyValueRow("OS", m.system.os)
                KeyValueRow("Kernel", m.system.kernel, mono = true)
                KeyValueRow("Uptime", Formatters.formatUptime(m.system.uptimeSeconds))
            }

            DockCard {
                Text("CPU (${m.cpu.cores} cores)", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { (m.cpu.usagePercent / 100.0).toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                )
                Spacer(Modifier.height(6.dp))
                KeyValueRow("Usage", "${"%.1f".format(m.cpu.usagePercent)}%")
                KeyValueRow("Load Avg (1m / 5m / 15m)", "${"%.2f".format(m.cpu.load1)} / ${"%.2f".format(m.cpu.load5)} / ${"%.2f".format(m.cpu.load15)}", mono = true)
            }

            DockCard {
                Text("Memory & Disk", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { (m.memory.usedPercent / 100.0).toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                )
                Spacer(Modifier.height(4.dp))
                KeyValueRow("RAM", "${Formatters.formatBytes(m.memory.usedBytes)} / ${Formatters.formatBytes(m.memory.totalBytes)} (${"%.1f".format(m.memory.usedPercent)}%)")
                if (m.memory.swapTotalBytes > 0) {
                    KeyValueRow("Swap", "${Formatters.formatBytes(m.memory.swapUsedBytes)} / ${Formatters.formatBytes(m.memory.swapTotalBytes)}")
                }
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { (m.disk.usedPercent / 100.0).toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                )
                Spacer(Modifier.height(4.dp))
                KeyValueRow("Disk (${m.disk.mount})", "${Formatters.formatBytes(m.disk.usedBytes)} / ${Formatters.formatBytes(m.disk.totalBytes)} (${"%.1f".format(m.disk.usedPercent)}%)")
            }

            m.troubleshooting?.let { ts ->
                if (!ts.topProcessesCpu.isNullOrEmpty()) {
                    DockCard {
                        Text("Top CPU Processes", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(6.dp))
                        ts.topProcessesCpu.take(8).forEach { p ->
                            KeyValueRow("${p.pid} • ${p.command.take(28)}", "${"%.1f".format(p.cpu)}% CPU • ${"%.1f".format(p.memory)}% MEM", mono = true)
                        }
                    }
                }
                if (!ts.listeningSockets.isNullOrEmpty()) {
                    DockCard {
                        Text("Listening Ports", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(6.dp))
                        ts.listeningSockets.take(12).forEach { s ->
                            KeyValueRow("${s.protocol} ${s.local}", s.process, mono = true)
                        }
                    }
                }
                if (!ts.failedSystemdUnits.isNullOrEmpty()) {
                    AlertBanner(message = "Failed systemd units: ${ts.failedSystemdUnits.joinToString(", ")}", tone = BadgeTone.DANGER)
                }
            }
        }
    }
}

@Composable
private fun SecurityTabContent(
    repo: DockDeployRepository,
    serverId: String,
    canOperate: Boolean,
) {
    val scope = rememberCoroutineScope()
    var audit by remember { mutableStateOf<ServerSecurityAudit?>(null) }
    var loading by remember { mutableStateOf(true) }
    var scanning by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(serverId) {
        loading = true
        runCatching { repo.getServerSecurityAudit(serverId) }
            .onSuccess { audit = it }
            .onFailure { error = it.message }
        loading = false
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Security Posture & Attack Analysis", style = MaterialTheme.typography.titleMedium)
            if (canOperate) {
                Button(
                    enabled = !scanning,
                    onClick = {
                        scope.launch {
                            scanning = true
                            error = null
                            runCatching { repo.runServerSecurityScan(serverId) }
                                .onSuccess { audit = it }
                                .onFailure { error = it.message }
                            scanning = false
                        }
                    },
                ) {
                    Text(if (scanning) "Scanning..." else "Run Scan")
                }
            }
        }

        if (error != null) AlertBanner(message = error!!, tone = BadgeTone.DANGER)
        if (loading && audit == null) {
            Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            val a = audit ?: return@Column
            DockCard {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("Score: ${a.score}/100 (Grade ${a.grade})", style = MaterialTheme.typography.titleLarge)
                        Text(a.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    StatusBadge(label = statusLabel(a.status), tone = deploymentStatusTone(a.status))
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "Critical: ${a.criticalCount} • High: ${a.highCount} • Medium: ${a.mediumCount} • Low: ${a.lowCount}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            DockCard {
                Text("SSH & Web Attack Telemetry", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                KeyValueRow("SSH Failed Logins", a.sshAnalysis.failedAttemptsCount.toString())
                KeyValueRow("Invalid Users / Root Attempts", "${a.sshAnalysis.invalidUserAttempts} / ${a.sshAnalysis.rootLoginAttempts}")
                KeyValueRow("Web Scans / SQLi / Path Trav", "${a.webAttacks.scansDetected} / ${a.webAttacks.sqliAttempts} / ${a.webAttacks.pathTraversalAttempts}")
                KeyValueRow("Firewall", a.systemIntegrity.firewallStatus.ifBlank { "unknown" })
                a.sshAnalysis.topAttackerIps.take(5).forEach { ip ->
                    KeyValueRow("Top Attacker ${ip.ip}", "${ip.count} attempts", mono = true)
                }
            }

            if (a.findings.isNotEmpty()) {
                Text("Findings (${a.findings.size})", style = MaterialTheme.typography.titleSmall)
                a.findings.forEach { f ->
                    DockCard(contentPadding = 12.dp) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(f.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            StatusBadge(
                                label = f.severity.uppercase(),
                                tone = when (f.severity) {
                                    "critical", "high" -> BadgeTone.DANGER
                                    "medium" -> BadgeTone.WARNING
                                    else -> BadgeTone.INFO
                                },
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(f.description, style = MaterialTheme.typography.bodySmall)
                        if (f.remediation.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text("Remediation: ${f.remediation}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ImagesTabContent(
    repo: DockDeployRepository,
    serverId: String,
    canOperate: Boolean,
) {
    val scope = rememberCoroutineScope()
    var images by remember { mutableStateOf<List<ImageSummary>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var pruneNotice by remember { mutableStateOf<String?>(null) }
    var showPrune by remember { mutableStateOf(false) }

    fun load() {
        scope.launch {
            loading = true
            runCatching { repo.listImages(serverId) }
                .onSuccess { images = it }
                .onFailure { error = it.message }
            loading = false
        }
    }

    LaunchedEffect(serverId) { load() }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (canOperate) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                OutlinedButton(onClick = { showPrune = true }) {
                    Icon(Icons.Rounded.CleaningServices, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Prune Docker")
                }
            }
        }
        if (pruneNotice != null) AlertBanner(message = pruneNotice!!, tone = BadgeTone.SUCCESS, onDismiss = { pruneNotice = null })
        if (error != null) AlertBanner(message = error!!, tone = BadgeTone.DANGER)
        if (loading) {
            Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else if (images.isEmpty()) {
            EmptyStateView(icon = Icons.Rounded.Layers, title = "No images", description = "No Docker images found on this server.")
        } else {
            images.forEach { img ->
                DockCard(contentPadding = 12.dp) {
                    val tags = img.repoTags?.filter { it != "<none>:<none>" }?.joinToString(", ").orEmpty().ifBlank { "untagged (${img.id.take(16)})" }
                    Text(tags, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace))
                    Spacer(Modifier.height(4.dp))
                    KeyValueRow("Size", Formatters.formatBytes(img.size))
                    KeyValueRow("Created", Formatters.formatRelativeEpochSeconds(img.created))
                }
            }
        }
    }

    if (showPrune) {
        var target by remember { mutableStateOf("all") }
        var allImages by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showPrune = false },
            title = { Text("Prune Unused Docker Resources") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("all", "images", "containers", "volumes", "buildcache").forEach { opt ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = target == opt, onClick = { target = opt })
                            Text(opt.replaceFirstChar { it.uppercase() })
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = allImages, onCheckedChange = { allImages = it })
                        Text("Remove all unused images (not just dangling)", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    showPrune = false
                    scope.launch {
                        runCatching { repo.pruneDocker(serverId, target, allImages) }
                            .onSuccess { res ->
                                pruneNotice = "Reclaimed ${Formatters.formatBytes(res.spaceReclaimed)} (${res.imagesDeleted} images, ${res.containersDeleted} containers, ${res.volumesDeleted} volumes)"
                                load()
                            }
                            .onFailure { error = it.message }
                    }
                }) { Text("Prune") }
            },
            dismissButton = { TextButton(onClick = { showPrune = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun VolumesTabContent(
    repo: DockDeployRepository,
    serverId: String,
    onBrowseVolume: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var volumes by remember { mutableStateOf<List<VolumeSummary>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var exportPath by remember { mutableStateOf<String?>(null) }

    val archiveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/gzip")) { uri ->
        val p = exportPath ?: return@rememberLauncherForActivityResult
        exportPath = null
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        repo.downloadArchive(serverId, p, out)
                    }
                }
            }.onFailure { error = it.message }
        }
    }

    LaunchedEffect(serverId) {
        runCatching { repo.listVolumes(serverId) }
            .onSuccess { volumes = it }
            .onFailure { error = it.message }
        loading = false
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (error != null) AlertBanner(message = error!!, tone = BadgeTone.DANGER)
        if (loading) {
            Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else if (volumes.isEmpty()) {
            EmptyStateView(icon = Icons.Rounded.SdStorage, title = "No volumes", description = "This server has no named Docker volumes.")
        } else {
            volumes.forEach { vol ->
                DockCard(contentPadding = 12.dp) {
                    Text(vol.name, style = MaterialTheme.typography.titleSmall.copy(fontFamily = FontFamily.Monospace))
                    Spacer(Modifier.height(4.dp))
                    KeyValueRow("Driver", vol.driver)
                    KeyValueRow("Mountpoint", vol.mountpoint, mono = true)
                    if (vol.mountpoint.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { onBrowseVolume(vol.mountpoint) }) {
                                Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Browse Files")
                            }
                            OutlinedButton(onClick = {
                                exportPath = vol.mountpoint
                                archiveLauncher.launch("${vol.name}.tar.gz")
                            }) {
                                Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Export Archive")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NetworksTabContent(repo: DockDeployRepository, serverId: String) {
    var networks by remember { mutableStateOf<List<NetworkSummary>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(serverId) {
        runCatching { repo.listNetworks(serverId) }
            .onSuccess { networks = it }
            .onFailure { error = it.message }
        loading = false
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (error != null) AlertBanner(message = error!!, tone = BadgeTone.DANGER)
        if (loading) {
            Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else if (networks.isEmpty()) {
            EmptyStateView(icon = Icons.Rounded.Hub, title = "No networks", description = "No Docker networks found.")
        } else {
            networks.forEach { net ->
                DockCard(contentPadding = 12.dp) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(net.name, style = MaterialTheme.typography.titleSmall.copy(fontFamily = FontFamily.Monospace))
                        StatusBadge(label = net.scope, tone = BadgeTone.NEUTRAL)
                    }
                    Spacer(Modifier.height(4.dp))
                    KeyValueRow("Driver", net.driver)
                    KeyValueRow("Internal", if (net.internal) "Yes" else "No")
                }
            }
        }
    }
}

@Composable
private fun SystemTabContent(
    server: Server,
    caps: Capabilities?,
    canWrite: Boolean,
    onInstallDocker: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (canWrite && caps?.dockerSocketOk != true) {
            DockCard(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                Text("Docker Engine Not Running", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text("Install Docker Engine and Compose directly over SSH.", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(10.dp))
                Button(onClick = onInstallDocker) {
                    Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Install Docker")
                }
            }
        }
        DockCard {
            Text("Server Capabilities", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            if (caps != null) {
                KeyValueRow("OS", caps.os.ifBlank { "—" })
                KeyValueRow("Kernel", caps.kernel.ifBlank { "—" }, mono = true)
                KeyValueRow("Docker", caps.dockerVersion.ifBlank { "not installed" }, mono = true)
                KeyValueRow("Compose", caps.composeVersion.ifBlank { "—" }, mono = true)
                KeyValueRow("Sudo Mode", caps.sudoMode)
                KeyValueRow("Nginx", caps.nginxVersion.ifBlank { "not installed" }, mono = true)
                KeyValueRow("Certbot", caps.certbotVersion.ifBlank { "not installed" }, mono = true)
                KeyValueRow("Git", caps.gitVersion.ifBlank { "not installed" }, mono = true)
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
            KeyValueRow("Host Key", server.hostKeyFingerprint, mono = true)
            KeyValueRow("Docker Socket", server.dockerSocket, mono = true)
            KeyValueRow("Auth Method", server.authMethod)
            KeyValueRow("Sudo Stored", if (server.hasSudoPassword) "Yes" else "No")
        }
    }
}

@Composable
private fun RootExecDialog(
    repo: DockDeployRepository,
    serverId: String,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var cmd by remember { mutableStateOf("") }
    var sudoPass by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<ExecRootResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Run Root Command") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(value = cmd, onValueChange = { cmd = it }, label = { Text("Shell command") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = sudoPass, onValueChange = { sudoPass = it }, label = { Text("Sudo password (if not saved)") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                if (error != null) AlertBanner(message = error!!, tone = BadgeTone.DANGER)
                result?.let { r ->
                    StatusBadge(label = "Exit code ${r.exitCode}", tone = if (r.success) BadgeTone.SUCCESS else BadgeTone.DANGER)
                    SelectionContainer {
                        Text(
                            text = (r.stdout + if (r.stderr.isNotBlank()) "\n[stderr]\n${r.stderr}" else "").ifBlank { "(no output)" },
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !running && cmd.isNotBlank(),
                onClick = {
                    scope.launch {
                        running = true
                        error = null
                        runCatching { repo.execRoot(serverId, cmd, sudoPass.takeIf { it.isNotBlank() }, saveSudo = true) }
                            .onSuccess { result = it }
                            .onFailure { error = it.message }
                        running = false
                    }
                },
            ) { Text(if (running) "Running..." else "Execute") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun InstallDockerDialog(
    repo: DockDeployRepository,
    serverId: String,
    onDismiss: () -> Unit,
    onInstalled: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var method by remember { mutableStateOf("script") }
    var sudoPass by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<InstallDockerResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Install Docker Engine") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(selected = method == "script", onClick = { method = "script" }, shape = SegmentedButtonDefaults.itemShape(0, 2), label = { Text("get.docker.com") })
                    SegmentedButton(selected = method == "repo", onClick = { method = "repo" }, shape = SegmentedButtonDefaults.itemShape(1, 2), label = { Text("APT Repo") })
                }
                OutlinedTextField(value = sudoPass, onValueChange = { sudoPass = it }, label = { Text("Sudo password (if needed)") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                if (error != null) AlertBanner(message = error!!, tone = BadgeTone.DANGER)
                result?.let { r ->
                    StatusBadge(label = if (r.success) "Installed" else "Failed (${r.exitCode})", tone = if (r.success) BadgeTone.SUCCESS else BadgeTone.DANGER)
                    Text(r.stdout.takeLast(1200), style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp))
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !running,
                onClick = {
                    scope.launch {
                        running = true
                        error = null
                        runCatching { repo.installDocker(serverId, method, sudoPass.takeIf { it.isNotBlank() }, saveSudo = true) }
                            .onSuccess {
                                result = it
                                if (it.success) onInstalled()
                            }
                            .onFailure { error = it.message }
                        running = false
                    }
                },
            ) { Text(if (running) "Installing..." else "Install") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun ServerMembersDialog(
    repo: DockDeployRepository,
    serverId: String,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var members by remember { mutableStateOf<List<ServerMember>>(emptyList()) }
    var users by remember { mutableStateOf<List<User>>(emptyList()) }
    var selectedUserId by remember { mutableStateOf("") }
    var permission by remember { mutableStateOf("operate") }
    var error by remember { mutableStateOf<String?>(null) }

    fun load() {
        scope.launch {
            runCatching { repo.listServerMembers(serverId) }.onSuccess { members = it }.onFailure { error = it.message }
            runCatching { repo.listUsers() }.onSuccess {
                users = it
                if (selectedUserId.isBlank()) selectedUserId = it.firstOrNull()?.id.orEmpty()
            }
        }
    }

    LaunchedEffect(serverId) { load() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Server Access & Members") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (error != null) AlertBanner(message = error!!, tone = BadgeTone.DANGER)
                members.forEach { m ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(m.name.ifBlank { m.email }, style = MaterialTheme.typography.bodySmall)
                            Text("${m.email} • ${m.permission}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = {
                            scope.launch {
                                runCatching { repo.revokeServerAccess(serverId, m.userId) }
                                    .onSuccess { load() }
                                    .onFailure { error = it.message }
                            }
                        }) {
                            Icon(Icons.Rounded.PersonRemove, contentDescription = "Revoke", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                if (users.isNotEmpty()) {
                    HorizontalDivider()
                    Text("Grant Access", style = MaterialTheme.typography.titleSmall)
                    users.forEach { u ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = selectedUserId == u.id, onClick = { selectedUserId = u.id })
                            Text("${u.name} (${u.role})", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SegmentedButton(selected = permission == "read", onClick = { permission = "read" }, shape = SegmentedButtonDefaults.itemShape(0, 2), label = { Text("Read") })
                        SegmentedButton(selected = permission == "operate", onClick = { permission = "operate" }, shape = SegmentedButtonDefaults.itemShape(1, 2), label = { Text("Operate") })
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = selectedUserId.isNotBlank(),
                onClick = {
                    scope.launch {
                        runCatching { repo.grantServerAccess(serverId, selectedUserId, permission) }
                            .onSuccess { load() }
                            .onFailure { error = it.message }
                    }
                },
            ) { Text("Grant") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
