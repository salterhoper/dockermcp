package tech.codesky.dockdeploy.ui.deployments

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tech.codesky.dockdeploy.data.model.*
import tech.codesky.dockdeploy.data.network.ApiException
import tech.codesky.dockdeploy.data.repository.DockDeployRepository
import tech.codesky.dockdeploy.ui.components.*
import tech.codesky.dockdeploy.ui.domains.AddDomainDialog
import tech.codesky.dockdeploy.ui.domains.NginxConfigDialog
import tech.codesky.dockdeploy.ui.theme.LocalStatusColors
import tech.codesky.dockdeploy.ui.theme.TerminalBg
import tech.codesky.dockdeploy.ui.theme.TerminalFg
import tech.codesky.dockdeploy.util.Formatters

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeploymentsListScreen(
    repo: DockDeployRepository,
    user: CurrentUser,
    widthClass: WindowWidthSizeClass,
    selectedDeploymentId: String? = null,
    onSelectDeployment: (Deployment) -> Unit,
    onSessionExpired: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var deployments by remember { mutableStateOf<List<Deployment>>(emptyList()) }
    var servers by remember { mutableStateOf<List<Server>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var search by remember { mutableStateOf("") }
    var showCreate by remember { mutableStateOf(false) }
    var autoDetecting by remember { mutableStateOf(false) }

    val canWrite = user.can("deployment:write")

    suspend fun load(isRefresh: Boolean) {
        if (isRefresh) refreshing = true else loading = true
        error = null
        try {
            deployments = repo.listDeployments()
            servers = repo.listServers()
        } catch (e: ApiException) {
            if (e.isAuthFailure) onSessionExpired() else error = e.message
        } finally {
            loading = false
            refreshing = false
        }
    }

    LaunchedEffect(Unit) { load(false) }

    val filtered = remember(deployments, search) {
        val q = search.trim().lowercase()
        if (q.isEmpty()) deployments
        else deployments.filter {
            it.name.lowercase().contains(q) ||
                it.repoUrl.lowercase().contains(q) ||
                it.imageRef.lowercase().contains(q)
        }
    }

    val columns = if (widthClass == WindowWidthSizeClass.Compact) 1 else 2

    Scaffold(
        floatingActionButton = {
            if (canWrite && servers.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { showCreate = true },
                    icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                    text = { Text("New Deployment") },
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
                        placeholder = { Text("Filter deployments...") },
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

                if (error != null) AlertBanner(message = error!!, tone = BadgeTone.DANGER, onDismiss = { error = null })

                when {
                    loading -> Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    filtered.isEmpty() -> EmptyStateView(
                        icon = Icons.Rounded.RocketLaunch,
                        title = if (servers.isEmpty()) "Connect a server first" else "No deployments yet",
                        description = if (servers.isEmpty()) {
                            "Deployments build and run on a connected server."
                        } else {
                            "Point DockDeploy at a Git repository, Compose file, or Docker image."
                        },
                        actionLabel = if (canWrite && servers.isNotEmpty()) "New Deployment" else null,
                        onAction = if (canWrite && servers.isNotEmpty()) { { showCreate = true } } else null,
                    )
                    else -> {
                        ResponsiveGrid(items = filtered, columns = columns) { dep ->
                            DockCard(
                                onClick = { onSelectDeployment(dep) },
                                borderColor = if (dep.id == selectedDeploymentId) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.Top,
                                ) {
                                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                        Text(dep.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            text = dep.repoUrl.ifBlank { dep.imageRef }.ifBlank { dep.slug },
                                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    StatusBadge(label = statusLabel(dep.status), tone = deploymentStatusTone(dep.status))
                                }
                                HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outlineVariant)
                                KeyValueRow("Server", dep.serverName ?: "—")
                                KeyValueRow("Source", statusLabel(dep.sourceType))
                                KeyValueRow("Port", dep.hostPort?.toString() ?: "—", mono = true)
                                KeyValueRow("Updated", Formatters.formatRelative(dep.updatedAt))
                            }
                        }
                        Spacer(Modifier.height(64.dp))
                    }
                }
            }
        }
    }

    if (showCreate) {
        NewDeploymentWizardDialog(
            repo = repo,
            servers = servers,
            onDismiss = { showCreate = false },
            onCreated = { created ->
                showCreate = false
                scope.launch { load(true) }
                onSelectDeployment(created)
            },
        )
    }
}

@Composable
private fun NewDeploymentWizardDialog(
    repo: DockDeployRepository,
    servers: List<Server>,
    onDismiss: () -> Unit,
    onCreated: (Deployment) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var serverId by remember { mutableStateOf(servers.firstOrNull()?.id.orEmpty()) }
    var name by remember { mutableStateOf("") }
    var sourceType by remember { mutableStateOf("git_dockerfile") }
    var repoUrl by remember { mutableStateOf("") }
    var gitRef by remember { mutableStateOf("main") }
    var credentialId by remember { mutableStateOf<String?>(null) }
    var dockerfilePath by remember { mutableStateOf("Dockerfile") }
    var buildContext by remember { mutableStateOf(".") }
    var composePath by remember { mutableStateOf("docker-compose.yml") }
    var composeContent by remember { mutableStateOf("services:\n  web:\n    image: nginx:alpine\n    restart: unless-stopped\n") }
    var imageRef by remember { mutableStateOf("") }
    var buildStrategy by remember { mutableStateOf("remote") }
    var registryId by remember { mutableStateOf<String?>(null) }
    var imageName by remember { mutableStateOf("") }
    var containerPort by remember { mutableStateOf("80") }
    var envText by remember { mutableStateOf("") }

    var gitCredentials by remember { mutableStateOf<List<GitCredential>>(emptyList()) }
    var registries by remember { mutableStateOf<List<Registry>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        gitCredentials = runCatching { repo.listGitCredentials() }.getOrDefault(emptyList())
        registries = runCatching { repo.listRegistries() }.getOrDefault(emptyList())
    }

    fun parseEnv(): List<EnvVar> {
        val secretRegex = Regex("(PASSWORD|SECRET|TOKEN|KEY|CREDENTIAL|PRIVATE|DSN|_URL$)", RegexOption.IGNORE_CASE)
        return envText.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains("=") }
            .map { line ->
                val idx = line.indexOf('=')
                val k = line.substring(0, idx).trim()
                val v = line.substring(idx + 1).trim()
                EnvVar(key = k, value = v, isSecret = secretRegex.containsMatchIn(k))
            }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Deployment") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (error != null) AlertBanner(message = error!!, tone = BadgeTone.DANGER)

                Text("Target Server", style = MaterialTheme.typography.labelMedium)
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    servers.forEach { sv ->
                        FilterChip(selected = serverId == sv.id, onClick = { serverId = sv.id }, label = { Text(sv.name) })
                    }
                }

                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Deployment Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())

                Text("Source", style = MaterialTheme.typography.labelMedium)
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        "git_dockerfile" to "Git Dockerfile",
                        "git_compose" to "Git Compose",
                        "raw_compose" to "Compose File",
                        "image" to "Image",
                    ).forEach { (id, label) ->
                        FilterChip(selected = sourceType == id, onClick = { sourceType = id }, label = { Text(label) })
                    }
                }

                if (sourceType == "git_dockerfile" || sourceType == "git_compose") {
                    OutlinedTextField(value = repoUrl, onValueChange = { repoUrl = it }, label = { Text("Git Repository URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = gitRef, onValueChange = { gitRef = it }, label = { Text("Branch / Tag / Ref") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (gitCredentials.isNotEmpty()) {
                        Text("Git Credential (optional)", style = MaterialTheme.typography.labelSmall)
                        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(selected = credentialId == null, onClick = { credentialId = null }, label = { Text("None") })
                            gitCredentials.forEach { c ->
                                FilterChip(selected = credentialId == c.id, onClick = { credentialId = c.id }, label = { Text(c.name) })
                            }
                        }
                    }
                }

                if (sourceType == "git_dockerfile") {
                    OutlinedTextField(value = dockerfilePath, onValueChange = { dockerfilePath = it }, label = { Text("Dockerfile Path") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = buildContext, onValueChange = { buildContext = it }, label = { Text("Build Context") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SegmentedButton(selected = buildStrategy == "remote", onClick = { buildStrategy = "remote" }, shape = SegmentedButtonDefaults.itemShape(0, 2), label = { Text("Build on Server") })
                        SegmentedButton(selected = buildStrategy == "registry", onClick = { buildStrategy = "registry" }, shape = SegmentedButtonDefaults.itemShape(1, 2), label = { Text("Registry Build") })
                    }
                    if (buildStrategy == "registry") {
                        registries.forEach { reg ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = registryId == reg.id, onClick = { registryId = reg.id })
                                Text("${reg.name} (${reg.url})", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        OutlinedTextField(value = imageName, onValueChange = { imageName = it }, label = { Text("Target Image Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                }

                if (sourceType == "git_compose") {
                    OutlinedTextField(value = composePath, onValueChange = { composePath = it }, label = { Text("Compose File Path") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }

                if (sourceType == "raw_compose") {
                    OutlinedTextField(
                        value = composeContent,
                        onValueChange = { composeContent = it },
                        label = { Text("docker-compose.yml") },
                        minLines = 5,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                if (sourceType == "image") {
                    OutlinedTextField(value = imageRef, onValueChange = { imageRef = it }, label = { Text("Image Reference (e.g. nginx:alpine)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }

                OutlinedTextField(
                    value = containerPort,
                    onValueChange = { containerPort = it },
                    label = { Text("Container Port") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = envText,
                    onValueChange = { envText = it },
                    label = { Text("Environment (KEY=value per line)") },
                    minLines = 3,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                enabled = !busy && serverId.isNotBlank() && name.isNotBlank(),
                onClick = {
                    scope.launch {
                        busy = true
                        error = null
                        try {
                            val usesGit = sourceType == "git_dockerfile" || sourceType == "git_compose"
                            val created = repo.createDeployment(
                                CreateDeploymentRequest(
                                    serverId = serverId,
                                    name = name.trim(),
                                    sourceType = sourceType,
                                    repoUrl = if (usesGit) repoUrl.trim() else null,
                                    gitRef = if (usesGit) gitRef.trim() else null,
                                    gitCredentialId = if (usesGit) credentialId else null,
                                    dockerfilePath = if (sourceType == "git_dockerfile") dockerfilePath else null,
                                    buildContext = if (sourceType == "git_dockerfile") buildContext else null,
                                    composePath = if (sourceType == "git_compose") composePath else null,
                                    composeContent = if (sourceType == "raw_compose") composeContent else null,
                                    imageRef = if (sourceType == "image") imageRef.trim() else null,
                                    buildStrategy = if (sourceType == "git_dockerfile") buildStrategy else "remote",
                                    registryId = if (sourceType == "git_dockerfile" && buildStrategy == "registry") registryId else null,
                                    imageName = if (sourceType == "git_dockerfile" && buildStrategy == "registry") imageName.takeIf { it.isNotBlank() } else null,
                                    containerPort = containerPort.toIntOrNull() ?: 80,
                                    env = parseEnv(),
                                ),
                            )
                            onCreated(created)
                        } catch (e: ApiException) {
                            error = e.message + if (e.fields.isNotEmpty()) "\n" + e.fields.entries.joinToString("\n") { "${it.key}: ${it.value}" } else ""
                        } finally {
                            busy = false
                        }
                    }
                },
            ) {
                Text(if (busy) "Creating..." else "Create")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private enum class DeploymentTab(val label: String) {
    ACTIVITY("Activity"),
    ENV("Environment"),
    DOMAINS("Domains"),
    WEBHOOK("Push to Deploy"),
    CONFIG("Configuration"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeploymentDetailScreen(
    repo: DockDeployRepository,
    user: CurrentUser,
    deploymentId: String,
    showBack: Boolean,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
    onSessionExpired: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var deployment by remember(deploymentId) { mutableStateOf<Deployment?>(null) }
    var runs by remember(deploymentId) { mutableStateOf<List<Run>>(emptyList()) }
    var selectedRunId by remember(deploymentId) { mutableStateOf<String?>(null) }
    var selectedTab by remember(deploymentId) { mutableStateOf(DeploymentTab.ACTIVITY) }
    var loading by remember(deploymentId) { mutableStateOf(true) }
    var error by remember(deploymentId) { mutableStateOf<String?>(null) }
    var deploying by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val canDeploy = user.can("deployment:deploy")
    val canWrite = user.can("deployment:write")
    val canDelete = user.can("deployment:delete")
    val canDomainWrite = user.can("domain:write")

    suspend fun loadAll() {
        try {
            deployment = repo.getDeployment(deploymentId)
            runs = repo.listRuns(deploymentId)
            error = null
        } catch (e: ApiException) {
            if (e.isAuthFailure) onSessionExpired() else error = e.message
        } finally {
            loading = false
        }
    }

    LaunchedEffect(deploymentId) {
        loadAll()
        while (true) {
            val hasLive = runs.any { it.status == "queued" || it.status == "running" } || deployment?.status == "deploying"
            delay(if (hasLive) 3000 else 12000)
            runCatching {
                deployment = repo.getDeployment(deploymentId)
                runs = repo.listRuns(deploymentId)
            }
        }
    }

    val liveRun = runs.firstOrNull { it.status == "queued" || it.status == "running" }
    val shownRun = runs.firstOrNull { it.id == selectedRunId } ?: liveRun ?: runs.firstOrNull()

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text(deployment?.name ?: "Deployment", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    deployment?.let {
                        Text(statusLabel(it.sourceType), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
            navigationIcon = {
                if (showBack) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
            },
            actions = {
                if (canDeploy && deployment != null) {
                    FilledTonalButton(
                        enabled = !deploying && liveRun == null,
                        onClick = {
                            scope.launch {
                                deploying = true
                                runCatching { repo.triggerDeploy(deploymentId) }
                                    .onSuccess { run ->
                                        selectedRunId = run.id
                                        loadAll()
                                    }
                                    .onFailure { error = it.message }
                                deploying = false
                            }
                        },
                        modifier = Modifier.padding(end = 4.dp),
                    ) {
                        Icon(Icons.Rounded.RocketLaunch, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (liveRun != null) "Deploying" else if (runs.isEmpty()) "Deploy" else "Redeploy")
                    }
                }
                if (canDelete) {
                    IconButton(onClick = { showDeleteConfirm = true }) {
                        Icon(Icons.Rounded.DeleteOutline, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                    }
                }
            },
        )

        deployment?.let { d ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatusBadge(label = statusLabel(d.status), tone = deploymentStatusTone(d.status))
                Text(d.serverName ?: "server", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (d.hostPort != null) {
                    Text(
                        "127.0.0.1:${d.hostPort} -> ${d.containerPort}",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (error != null) {
            Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                AlertBanner(message = error!!, tone = BadgeTone.DANGER, onDismiss = { error = null })
            }
        }

        PrimaryScrollableTabRow(selectedTabIndex = selectedTab.ordinal, edgePadding = 12.dp) {
            DeploymentTab.entries.forEach { tab ->
                Tab(selected = selectedTab == tab, onClick = { selectedTab = tab }, text = { Text(tab.label, maxLines = 1) })
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            if (loading && deployment == null) {
                Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else if (deployment != null) {
                val dep = deployment!!
                when (selectedTab) {
                    DeploymentTab.ACTIVITY -> DeploymentActivityTab(
                        repo = repo,
                        deploymentId = deploymentId,
                        runs = runs,
                        shownRun = shownRun,
                        canDeploy = canDeploy,
                        onSelectRun = { selectedRunId = it.id },
                        onCancelRun = { runId ->
                            scope.launch {
                                runCatching { repo.cancelRun(deploymentId, runId) }
                                    .onSuccess { loadAll() }
                                    .onFailure { error = it.message }
                            }
                        },
                        onRollback = { runId ->
                            scope.launch {
                                runCatching { repo.triggerDeploy(deploymentId, rollbackRunId = runId) }
                                    .onSuccess { run ->
                                        selectedRunId = run.id
                                        loadAll()
                                    }
                                    .onFailure { error = it.message }
                            }
                        },
                    )
                    DeploymentTab.ENV -> DeploymentEnvTab(repo = repo, deploymentId = deploymentId, canWrite = canWrite)
                    DeploymentTab.DOMAINS -> DeploymentDomainsTab(repo = repo, deployment = dep, canWrite = canDomainWrite)
                    DeploymentTab.WEBHOOK -> DeploymentWebhookTab(repo = repo, deploymentId = deploymentId, canWrite = canWrite)
                    DeploymentTab.CONFIG -> DeploymentConfigTab(dep)
                }
            }
        }
    }

    if (showDeleteConfirm && deployment != null) {
        ConfirmActionDialog(
            title = "Delete ${deployment!!.name}?",
            message = "Running containers remain on the server. Only the deployment record and run history are removed.",
            confirmLabel = "Delete",
            destructive = true,
            onDismiss = { showDeleteConfirm = false },
            onConfirm = {
                showDeleteConfirm = false
                scope.launch {
                    runCatching { repo.deleteDeployment(deploymentId) }
                        .onSuccess { onDeleted() }
                        .onFailure { error = it.message }
                }
            },
        )
    }
}

@Composable
private fun DeploymentActivityTab(
    repo: DockDeployRepository,
    deploymentId: String,
    runs: List<Run>,
    shownRun: Run?,
    canDeploy: Boolean,
    onSelectRun: (Run) -> Unit,
    onCancelRun: (String) -> Unit,
    onRollback: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (shownRun != null) {
            DockCard(contentPadding = 12.dp) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Run #${shownRun.number}", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${shownRun.trigger} • ${Formatters.formatDuration(shownRun.startedAt, shownRun.finishedAt)}" +
                                if (shownRun.commitSha.isNotBlank()) " • ${shownRun.commitSha.take(8)}" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    StatusBadge(label = statusLabel(shownRun.status), tone = deploymentStatusTone(shownRun.status))
                }
                if (shownRun.status == "queued" && canDeploy) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { onCancelRun(shownRun.id) }) { Text("Cancel Run") }
                }
                if (shownRun.error.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    AlertBanner(message = shownRun.error, tone = BadgeTone.DANGER)
                }
            }

            RunLogStreamBox(repo = repo, deploymentId = deploymentId, runId = shownRun.id)
        } else {
            EmptyStateView(
                icon = Icons.Rounded.History,
                title = "No runs yet",
                description = "Tap Deploy above to build and launch this application.",
            )
        }

        if (runs.isNotEmpty()) {
            Text("Run History (${runs.size})", style = MaterialTheme.typography.titleSmall)
            runs.forEach { run ->
                DockCard(
                    contentPadding = 12.dp,
                    onClick = { onSelectRun(run) },
                    borderColor = if (run.id == shownRun?.id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                ) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("#${run.number} • ${run.trigger}", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "${Formatters.formatRelative(run.queuedAt)} • ${Formatters.formatDuration(run.startedAt, run.finishedAt)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            StatusBadge(label = statusLabel(run.status), tone = deploymentStatusTone(run.status))
                            if (canDeploy && run.status == "succeeded" && run.imageRef.isNotBlank()) {
                                OutlinedButton(
                                    onClick = { onRollback(run.id) },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                    modifier = Modifier.height(30.dp),
                                ) {
                                    Text("Rollback", style = MaterialTheme.typography.labelSmall)
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
private fun RunLogStreamBox(
    repo: DockDeployRepository,
    deploymentId: String,
    runId: String,
) {
    var lines by remember(runId) { mutableStateOf<List<RunLogLine>>(emptyList()) }
    var streamStatus by remember(runId) { mutableStateOf<String?>(null) }
    val scroll = rememberScrollState()
    val statusColors = LocalStatusColors.current

    LaunchedEffect(deploymentId, runId) {
        lines = emptyList()
        streamStatus = null
        runCatching {
            repo.streamRunLogs(deploymentId, runId).collect { ev ->
                if (ev.type == "log") {
                    runCatching { repo.api.json.decodeFromString<RunLogLine>(ev.data) }.getOrNull()?.let { entry ->
                        lines = (lines + entry).takeLast(1500)
                    }
                } else if (ev.type == "end") {
                    streamStatus = "Stream finished"
                }
            }
        }.onFailure { streamStatus = it.message }
    }

    LaunchedEffect(lines.size) {
        scroll.animateScrollTo(scroll.maxValue)
    }

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = TerminalBg,
        modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 340.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth().verticalScroll(scroll).padding(12.dp)) {
            if (lines.isEmpty()) {
                Text(
                    streamStatus ?: "Streaming build logs...",
                    color = TerminalFg.copy(alpha = 0.6f),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                )
            } else {
                SelectionContainer {
                    Column {
                        lines.forEach { l ->
                            Text(
                                text = Formatters.stripAnsi(l.line),
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

@Composable
private fun DeploymentEnvTab(
    repo: DockDeployRepository,
    deploymentId: String,
    canWrite: Boolean,
) {
    val scope = rememberCoroutineScope()
    var vars by remember { mutableStateOf<List<EnvVar>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var savedMsg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(deploymentId) {
        runCatching { repo.getDeploymentEnv(deploymentId) }
            .onSuccess { vars = it }
            .onFailure { error = it.message }
        loading = false
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (savedMsg != null) AlertBanner(message = savedMsg!!, tone = BadgeTone.SUCCESS, onDismiss = { savedMsg = null })
        if (error != null) AlertBanner(message = error!!, tone = BadgeTone.DANGER, onDismiss = { error = null })

        if (loading) {
            CircularProgressIndicator()
        } else {
            vars.forEachIndexed { idx, v ->
                DockCard(contentPadding = 12.dp) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = v.key,
                                onValueChange = { k -> vars = vars.toMutableList().also { it[idx] = v.copy(key = k) } },
                                label = { Text("KEY") },
                                readOnly = !canWrite,
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            if (canWrite) {
                                IconButton(onClick = { vars = vars.toMutableList().also { it.removeAt(idx) } }) {
                                    Icon(Icons.Rounded.DeleteOutline, contentDescription = "Remove", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                        OutlinedTextField(
                            value = v.value,
                            onValueChange = { value -> vars = vars.toMutableList().also { it[idx] = v.copy(value = value) } },
                            label = { Text("VALUE") },
                            readOnly = !canWrite,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = v.isSecret,
                                enabled = canWrite,
                                onCheckedChange = { sec -> vars = vars.toMutableList().also { it[idx] = v.copy(isSecret = sec) } },
                            )
                            Text("Encrypt at rest (secret)", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            if (canWrite) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vars = vars + EnvVar("", "", false) }) {
                        Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Add Variable")
                    }
                    Button(
                        enabled = !saving,
                        onClick = {
                            scope.launch {
                                saving = true
                                error = null
                                runCatching { repo.setDeploymentEnv(deploymentId, vars.filter { it.key.isNotBlank() }) }
                                    .onSuccess { savedMsg = "Environment variables saved. Redeploy to apply." }
                                    .onFailure { error = it.message }
                                saving = false
                            }
                        },
                    ) {
                        Text(if (saving) "Saving..." else "Save Env")
                    }
                }
            }
        }
    }
}

@Composable
private fun DeploymentDomainsTab(
    repo: DockDeployRepository,
    deployment: Deployment,
    canWrite: Boolean,
) {
    val scope = rememberCoroutineScope()
    var domains by remember { mutableStateOf<List<Domain>>(emptyList()) }
    var showAdd by remember { mutableStateOf(false) }
    var viewConfig by remember { mutableStateOf<Domain?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun load() {
        scope.launch {
            runCatching { repo.listDomains(deploymentId = deployment.id) }
                .onSuccess { domains = it }
                .onFailure { error = it.message }
        }
    }

    LaunchedEffect(deployment.id) { load() }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (canWrite) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(onClick = { showAdd = true }) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Attach Domain")
                }
            }
        }
        if (error != null) AlertBanner(message = error!!, tone = BadgeTone.DANGER)
        if (domains.isEmpty()) {
            EmptyStateView(icon = Icons.Rounded.Language, title = "No domains attached", description = "Attach a domain to put Nginx and TLS in front of this deployment.")
        } else {
            domains.forEach { d ->
                DockCard(contentPadding = 12.dp) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(d.hostname, style = MaterialTheme.typography.titleSmall.copy(fontFamily = FontFamily.Monospace))
                        StatusBadge(label = statusLabel(d.status), tone = deploymentStatusTone(d.status))
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { viewConfig = d }) { Text("Nginx Config") }
                        if (canWrite) {
                            OutlinedButton(onClick = {
                                scope.launch { runCatching { repo.syncDomain(d.id) }.onSuccess { load() }.onFailure { error = it.message } }
                            }) { Text("Sync") }
                            OutlinedButton(onClick = {
                                scope.launch { runCatching { repo.issueDomainSSL(d.id) }.onSuccess { load() }.onFailure { error = it.message } }
                            }) { Text("SSL") }
                        }
                    }
                }
            }
        }
    }

    if (showAdd) {
        AddDomainDialog(
            repo = repo,
            defaultServerId = deployment.serverId,
            defaultDeploymentId = deployment.id,
            defaultUpstreamPort = deployment.hostPort?.toString().orEmpty(),
            onDismiss = { showAdd = false },
            onCreated = { showAdd = false; load() },
        )
    }

    if (viewConfig != null) {
        NginxConfigDialog(domain = viewConfig!!, onDismiss = { viewConfig = null })
    }
}

@Composable
private fun DeploymentWebhookTab(
    repo: DockDeployRepository,
    deploymentId: String,
    canWrite: Boolean,
) {
    val scope = rememberCoroutineScope()
    var webhook by remember { mutableStateOf<WebhookInfo?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(deploymentId) {
        runCatching { repo.getDeploymentWebhook(deploymentId) }
            .onSuccess { webhook = it }
            .onFailure { error = it.message }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (error != null) AlertBanner(message = error!!, tone = BadgeTone.DANGER)
        webhook?.let { w ->
            CopyableCodeBox(text = w.webhookUrl, label = "Webhook URL (POST)")
            CopyableCodeBox(text = w.webhookSecret, label = "HMAC-SHA256 / Bearer Secret")
            if (canWrite) {
                OutlinedButton(onClick = {
                    scope.launch {
                        runCatching { repo.rotateDeploymentWebhook(deploymentId) }
                            .onSuccess { webhook = it }
                            .onFailure { error = it.message }
                    }
                }) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Rotate Secret")
                }
            }
        }
    }
}

@Composable
private fun DeploymentConfigTab(d: Deployment) {
    DockCard {
        Text("Deployment Configuration", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        KeyValueRow("Slug", d.slug, mono = true)
        KeyValueRow("Source Type", d.sourceType)
        if (d.repoUrl.isNotBlank()) KeyValueRow("Repository", d.repoUrl, mono = true)
        if (d.gitRef.isNotBlank()) KeyValueRow("Git Ref", d.gitRef, mono = true)
        if (d.dockerfilePath.isNotBlank()) KeyValueRow("Dockerfile", d.dockerfilePath, mono = true)
        if (d.composePath.isNotBlank()) KeyValueRow("Compose Path", d.composePath, mono = true)
        if (d.imageRef.isNotBlank()) KeyValueRow("Image Ref", d.imageRef, mono = true)
        KeyValueRow("Build Strategy", d.buildStrategy)
        KeyValueRow("Container Port", d.containerPort.toString(), mono = true)
        KeyValueRow("Workdir", d.workdir, mono = true)
    }
}
