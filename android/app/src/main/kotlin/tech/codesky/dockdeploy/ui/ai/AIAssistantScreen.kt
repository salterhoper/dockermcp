package tech.codesky.dockdeploy.ui.ai

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import tech.codesky.dockdeploy.data.model.*
import tech.codesky.dockdeploy.data.network.ApiException
import tech.codesky.dockdeploy.data.repository.DockDeployRepository
import tech.codesky.dockdeploy.ui.components.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AIAssistantDialog(
    repo: DockDeployRepository,
    user: CurrentUser,
    initialServerId: String? = null,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) } // 0 = Chat, 1 = Diagnostics, 2 = Settings
    var conversations by remember { mutableStateOf<List<AIConversation>>(emptyList()) }
    var activeConvId by remember { mutableStateOf<String?>(null) }
    var messages by remember { mutableStateOf<List<AIMessage>>(emptyList()) }
    var streamingReply by remember { mutableStateOf("") }
    var streamingAction by remember { mutableStateOf<SafeguardAction?>(null) }
    var sending by remember { mutableStateOf(false) }

    var servers by remember { mutableStateOf<List<Server>>(emptyList()) }
    var contextServerId by remember(initialServerId) { mutableStateOf(initialServerId) }
    var inputText by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    val chatScroll = rememberScrollState()

    fun refreshConversations() {
        scope.launch {
            runCatching { repo.listAIConversations() }.onSuccess { conversations = it }
            runCatching { repo.listServers() }.onSuccess { servers = it }
        }
    }

    fun loadConversation(id: String) {
        scope.launch {
            activeConvId = id
            runCatching { repo.getAIConversation(id) }
                .onSuccess { (_, msgs) -> messages = msgs }
                .onFailure { error = it.message }
        }
    }

    LaunchedEffect(Unit) { refreshConversations() }
    LaunchedEffect(messages.size, streamingReply) { chatScroll.animateScrollTo(chatScroll.maxValue) }

    fun sendMessage() {
        val text = inputText.trim()
        if (text.isBlank() || sending) return
        inputText = ""
        error = null
        sending = true
        streamingReply = ""
        streamingAction = null

        // Optimistically show user message
        messages = messages + AIMessage(id = "tmp-${System.currentTimeMillis()}", role = "user", content = text)

        scope.launch {
            try {
                repo.streamAIChat(
                    conversationId = activeConvId,
                    message = text,
                    serverId = contextServerId,
                ).collect { ev ->
                    when (ev.type) {
                        "conversation" -> {
                            runCatching { repo.api.json.decodeFromString<AIConversation>(ev.data) }.getOrNull()?.let { c ->
                                activeConvId = c.id
                            }
                        }
                        "chunk" -> {
                            runCatching { repo.api.json.decodeFromString<JsonObject>(ev.data) }.getOrNull()?.let { obj ->
                                val chunkText = obj["text"]?.jsonPrimitive?.content.orEmpty()
                                streamingReply += chunkText
                            }
                        }
                        "action" -> {
                            runCatching { repo.api.json.decodeFromString<SafeguardAction>(ev.data) }.getOrNull()?.let { act ->
                                streamingAction = act
                            }
                        }
                        "error" -> {
                            val msg = runCatching { repo.api.json.decodeFromString<JsonObject>(ev.data)["message"]?.jsonPrimitive?.content }.getOrNull()
                            error = msg ?: ev.data
                        }
                        "done" -> {
                            val convId = activeConvId
                            if (convId != null) {
                                runCatching { repo.getAIConversation(convId) }.onSuccess { (_, msgs) ->
                                    messages = msgs
                                }
                            }
                            streamingReply = ""
                            streamingAction = null
                            refreshConversations()
                        }
                    }
                }
            } catch (e: Exception) {
                error = e.message ?: "AI chat stream failed."
            } finally {
                sending = false
            }
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            modifier = Modifier.fillMaxSize().imePadding(),
            topBar = {
                TopAppBar(
                    title = { Text("AI Ops Assistant") },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, contentDescription = "Close") }
                    },
                    actions = {
                        if (tab == 0) {
                            IconButton(onClick = {
                                activeConvId = null
                                messages = emptyList()
                            }) {
                                Icon(Icons.Rounded.AddComment, contentDescription = "New Chat")
                            }
                        }
                    },
                )
            },
        ) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                PrimaryTabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Chat") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Diagnose") })
                    Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Providers") })
                }

                when (tab) {
                    0 -> Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        // Conversation + Server context strip
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            FilterChip(
                                selected = contextServerId == null,
                                onClick = { contextServerId = null },
                                label = { Text("Global Context") },
                            )
                            servers.forEach { sv ->
                                FilterChip(
                                    selected = contextServerId == sv.id,
                                    onClick = { contextServerId = sv.id },
                                    label = { Text(sv.name) },
                                )
                            }
                        }

                        if (conversations.isNotEmpty()) {
                            Row(
                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                conversations.take(8).forEach { c ->
                                    InputChip(
                                        selected = activeConvId == c.id,
                                        onClick = { loadConversation(c.id) },
                                        label = { Text(c.title.take(22), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                        trailingIcon = {
                                            IconButton(
                                                onClick = {
                                                    scope.launch {
                                                        runCatching { repo.deleteAIConversation(c.id) }
                                                        if (activeConvId == c.id) {
                                                            activeConvId = null
                                                            messages = emptyList()
                                                        }
                                                        refreshConversations()
                                                    }
                                                },
                                                modifier = Modifier.size(16.dp),
                                            ) {
                                                Icon(Icons.Rounded.Close, contentDescription = "Delete chat", modifier = Modifier.size(14.dp))
                                            }
                                        },
                                    )
                                }
                            }
                        }

                        if (error != null) {
                            AlertBanner(message = error!!, tone = BadgeTone.DANGER, modifier = Modifier.padding(12.dp), onDismiss = { error = null })
                        }

                        Column(
                            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(chatScroll).padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            if (messages.isEmpty() && streamingReply.isBlank()) {
                                EmptyStateView(
                                    icon = Icons.Rounded.AutoAwesome,
                                    title = "Ask DockDeploy AI",
                                    description = "Debug container crashes, inspect server metrics, or request guarded operations with approval cards.",
                                )
                            } else {
                                messages.forEach { msg ->
                                    val isUser = msg.role == "user"
                                    DockCard(
                                        containerColor = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                                        contentPadding = 12.dp,
                                    ) {
                                        Text(
                                            text = if (isUser) "You" else "Assistant",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        Text(msg.content, style = MaterialTheme.typography.bodySmall)
                                        msg.metadata?.safeguardAction?.let { act ->
                                            Spacer(Modifier.height(8.dp))
                                            SafeguardActionCard(repo = repo, user = user, action = act)
                                        }
                                    }
                                }
                                if (streamingReply.isNotBlank()) {
                                    DockCard(contentPadding = 12.dp) {
                                        Text("Assistant (streaming...)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                        Spacer(Modifier.height(4.dp))
                                        Text(streamingReply, style = MaterialTheme.typography.bodySmall)
                                        streamingAction?.let { act ->
                                            Spacer(Modifier.height(8.dp))
                                            SafeguardActionCard(repo = repo, user = user, action = act)
                                        }
                                    }
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth().padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedTextField(
                                value = inputText,
                                onValueChange = { inputText = it },
                                placeholder = { Text("Ask about containers, logs, or deploys...") },
                                maxLines = 3,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                keyboardActions = KeyboardActions(onSend = { sendMessage() }),
                                modifier = Modifier.weight(1f),
                            )
                            FilledIconButton(onClick = { sendMessage() }, enabled = !sending && inputText.isNotBlank()) {
                                Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Send")
                            }
                        }
                    }

                    1 -> ServerDiagnosePane(repo = repo, servers = servers, initialServerId = contextServerId)
                    2 -> AISettingsPane(repo = repo)
                }
            }
        }
    }
}

@Composable
private fun SafeguardActionCard(
    repo: DockDeployRepository,
    user: CurrentUser,
    action: SafeguardAction,
) {
    val scope = rememberCoroutineScope()
    var executing by remember { mutableStateOf(false) }
    var completed by remember { mutableStateOf(false) }
    var output by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val allowed = user.can(action.permission)
    val tone = when (action.dangerLevel) {
        "high" -> BadgeTone.DANGER
        "medium" -> BadgeTone.WARNING
        else -> BadgeTone.SUCCESS
    }

    DockCard(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentPadding = 12.dp,
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(action.title.ifBlank { action.type }, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            StatusBadge(label = "${action.dangerLevel} risk", tone = tone)
        }
        Spacer(Modifier.height(4.dp))
        Text(action.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (error != null) {
            Spacer(Modifier.height(6.dp))
            Text(error!!, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }
        if (output != null) {
            Spacer(Modifier.height(6.dp))
            CopyableCodeBox(text = output!!)
        }
        Spacer(Modifier.height(8.dp))
        Button(
            enabled = allowed && !executing && !completed,
            onClick = {
                scope.launch {
                    executing = true
                    error = null
                    try {
                        val srvId = action.serverId ?: action.payload?.get("server_id")?.jsonPrimitive?.content
                        when {
                            action.type.startsWith("container:") -> {
                                val op = action.type.removePrefix("container:")
                                val cid = action.resourceId ?: action.payload?.get("container_id")?.jsonPrimitive?.content.orEmpty()
                                repo.containerAction(srvId!!, cid, op)
                                output = "Container $op executed."
                            }
                            action.type == "server:exec_root" -> {
                                val cmd = action.payload?.get("command")?.jsonPrimitive?.content.orEmpty()
                                val res = repo.execRoot(srvId!!, cmd)
                                output = "exit=${res.exitCode}\n${res.stdout}\n${res.stderr}".trim()
                            }
                            action.type == "server:probe" -> {
                                repo.probeServer(srvId!!)
                                output = "Server probe completed."
                            }
                            action.type == "server:install_docker" -> {
                                repo.installDocker(srvId!!)
                                output = "Docker install initiated."
                            }
                            action.type == "deployment:deploy" -> {
                                val did = action.resourceId ?: action.payload?.get("deployment_id")?.jsonPrimitive?.content.orEmpty()
                                repo.triggerDeploy(did)
                                output = "Deployment queued."
                            }
                            action.type == "domain:sync" -> {
                                val dmid = action.resourceId ?: action.payload?.get("domain_id")?.jsonPrimitive?.content.orEmpty()
                                repo.syncDomain(dmid)
                                output = "Domain synced."
                            }
                            action.type == "domain:ssl" -> {
                                val dmid = action.resourceId ?: action.payload?.get("domain_id")?.jsonPrimitive?.content.orEmpty()
                                repo.issueDomainSSL(dmid)
                                output = "SSL issuance triggered."
                            }
                        }
                        completed = true
                    } catch (e: Exception) {
                        error = e.message ?: "Execution failed."
                    } finally {
                        executing = false
                    }
                }
            },
        ) {
            Text(
                when {
                    !allowed -> "Missing ${action.permission}"
                    completed -> "Executed"
                    executing -> "Executing..."
                    else -> "Approve & Execute"
                },
            )
        }
    }
}

@Composable
private fun ServerDiagnosePane(
    repo: DockDeployRepository,
    servers: List<Server>,
    initialServerId: String?,
) {
    val scope = rememberCoroutineScope()
    var selectedId by remember { mutableStateOf(initialServerId ?: servers.firstOrNull()?.id.orEmpty()) }
    var report by remember { mutableStateOf<ServerDiagnosticReport?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            servers.forEach { sv ->
                FilterChip(selected = selectedId == sv.id, onClick = { selectedId = sv.id }, label = { Text(sv.name) })
            }
        }
        Button(
            enabled = !loading && selectedId.isNotBlank(),
            onClick = {
                scope.launch {
                    loading = true
                    error = null
                    runCatching { repo.diagnoseServer(selectedId) }
                        .onSuccess { report = it }
                        .onFailure { error = it.message }
                    loading = false
                }
            },
        ) {
            Text(if (loading) "Collecting Diagnostics..." else "Collect Server Diagnostic")
        }
        if (error != null) AlertBanner(message = error!!, tone = BadgeTone.DANGER)
        report?.let { r ->
            if (r.healthIssues.isNotEmpty()) {
                AlertBanner(message = r.healthIssues.joinToString("\n"), tone = BadgeTone.WARNING)
            }
            DockCard {
                Text(r.summaryMarkdown.ifBlank { "No issues detected." }, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
            }
        }
    }
}

@Composable
fun AISettingsPane(repo: DockDeployRepository) {
    val scope = rememberCoroutineScope()
    var provider by remember { mutableStateOf("openai") }
    var model by remember { mutableStateOf("gpt-4o") }
    var baseUrl by remember { mutableStateOf("https://api.openai.com/v1") }
    var apiKey by remember { mutableStateOf("") }
    var hasStoredKey by remember { mutableStateOf(false) }
    var customPrompt by remember { mutableStateOf("") }
    var detected by remember { mutableStateOf<DetectionResult?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        runCatching { repo.getAISettings() }.onSuccess { s ->
            provider = s.provider
            model = s.model
            baseUrl = s.baseUrl
            hasStoredKey = s.hasApiKey
            customPrompt = s.systemPromptCustom
        }
        runCatching { repo.detectLocalAgents() }.onSuccess { detected = it }
    }

    val presets = listOf(
        "openai" to "https://api.openai.com/v1",
        "anthropic" to "https://api.anthropic.com/v1",
        "gemini" to "https://generativelanguage.googleapis.com/v1beta/openai",
        "deepseek" to "https://api.deepseek.com/v1",
        "openrouter" to "https://openrouter.ai/api/v1",
        "ollama" to "http://localhost:11434/v1",
        "custom" to "",
    )

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        detected?.agents?.takeIf { it.isNotEmpty() }?.let { agents ->
            DockCard(contentPadding = 12.dp) {
                Text("Detected Local Agents on Controller", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                agents.forEach { a ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(a.name, style = MaterialTheme.typography.bodySmall)
                        StatusBadge(label = a.status, tone = if (a.available) BadgeTone.SUCCESS else BadgeTone.NEUTRAL)
                    }
                }
            }
        }

        if (status != null) AlertBanner(message = status!!, tone = BadgeTone.SUCCESS, onDismiss = { status = null })
        if (error != null) AlertBanner(message = error!!, tone = BadgeTone.DANGER, onDismiss = { error = null })

        Text("Provider", style = MaterialTheme.typography.labelMedium)
        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            presets.forEach { (p, url) ->
                FilterChip(
                    selected = provider == p,
                    onClick = {
                        provider = p
                        if (url.isNotBlank()) baseUrl = url
                    },
                    label = { Text(p) },
                )
            }
        }

        OutlinedTextField(value = model, onValueChange = { model = it }, label = { Text("Model") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = baseUrl, onValueChange = { baseUrl = it }, label = { Text("Base URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text(if (hasStoredKey) "API Key (leave blank to keep stored)" else "API Key") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(value = customPrompt, onValueChange = { customPrompt = it }, label = { Text("Custom System Instructions") }, minLines = 2, modifier = Modifier.fillMaxWidth())

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                scope.launch {
                    error = null
                    runCatching { repo.testAIConnection(provider, model, baseUrl, apiKey.takeIf { it.isNotBlank() }) }
                        .onSuccess { status = it }
                        .onFailure { error = it.message }
                }
            }) { Text("Test Connection") }

            Button(onClick = {
                scope.launch {
                    error = null
                    runCatching {
                        repo.updateAISettings(
                            UpdateAISettingsRequest(
                                provider = provider,
                                model = model,
                                baseUrl = baseUrl,
                                apiKey = apiKey.takeIf { it.isNotBlank() },
                                systemPromptCustom = customPrompt,
                            ),
                        )
                    }.onSuccess {
                        hasStoredKey = it.hasApiKey
                        apiKey = ""
                        status = "AI settings saved."
                    }.onFailure { error = it.message }
                }
            }) { Text("Save Settings") }
        }
    }
}
