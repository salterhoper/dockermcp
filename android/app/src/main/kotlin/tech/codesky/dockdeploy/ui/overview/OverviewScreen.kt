package tech.codesky.dockdeploy.ui.overview

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import tech.codesky.dockdeploy.data.model.Deployment
import tech.codesky.dockdeploy.data.model.Domain
import tech.codesky.dockdeploy.data.model.Health
import tech.codesky.dockdeploy.data.model.Server
import tech.codesky.dockdeploy.data.network.ApiException
import tech.codesky.dockdeploy.data.repository.DockDeployRepository
import tech.codesky.dockdeploy.ui.components.*
import tech.codesky.dockdeploy.util.Formatters

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverviewScreen(
    repo: DockDeployRepository,
    hostUrl: String,
    widthClass: WindowWidthSizeClass,
    onNavigateServers: () -> Unit,
    onNavigateDeployments: () -> Unit,
    onNavigateDomains: () -> Unit,
    onOpenAiAssistant: () -> Unit,
    onSessionExpired: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var health by remember { mutableStateOf<Health?>(null) }
    var servers by remember { mutableStateOf<List<Server>>(emptyList()) }
    var deployments by remember { mutableStateOf<List<Deployment>>(emptyList()) }
    var domains by remember { mutableStateOf<List<Domain>>(emptyList()) }

    suspend fun load(isRefresh: Boolean) {
        if (isRefresh) refreshing = true else loading = true
        error = null
        try {
            val h = scope.async { repo.getHealth() }
            val s = scope.async { repo.listServers() }
            val d = scope.async { repo.listDeployments() }
            val dm = scope.async { repo.listDomains() }
            health = h.await()
            servers = s.await()
            deployments = d.await()
            domains = dm.await()
        } catch (e: ApiException) {
            if (e.isAuthFailure) onSessionExpired() else error = e.message
        } catch (e: Exception) {
            error = e.message ?: "Failed to load overview."
        } finally {
            loading = false
            refreshing = false
        }
    }

    LaunchedEffect(hostUrl) { load(false) }

    val statCols = when (widthClass) {
        WindowWidthSizeClass.Expanded -> 4
        else -> 2
    }
    val resourceCols = when (widthClass) {
        WindowWidthSizeClass.Compact -> 1
        else -> 3
    }

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = { scope.launch { load(true) } },
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Host header strip
            DockCard(contentPadding = 14.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Connected Control Plane",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = hostUrl,
                            style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                        )
                    }
                    FilledTonalButton(
                        onClick = onOpenAiAssistant,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Icon(Icons.Rounded.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("AI Ops")
                    }
                }
            }

            if (error != null) {
                AlertBanner(message = error!!, tone = BadgeTone.DANGER)
            }

            if (loading && health == null) {
                Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                val h = health
                val statCards = listOf<@Composable () -> Unit>(
                    {
                        StatTile(
                            icon = Icons.Rounded.MonitorHeart,
                            label = "Control Plane",
                            value = if (h?.status == "ok") "Healthy" else "Degraded",
                            tone = if (h?.status == "ok") BadgeTone.SUCCESS else BadgeTone.DANGER,
                        )
                    },
                    {
                        StatTile(
                            icon = Icons.Rounded.Storage,
                            label = "Database",
                            value = if (h?.database == "ok") "Connected" else "Unreachable",
                            tone = if (h?.database == "ok") BadgeTone.SUCCESS else BadgeTone.DANGER,
                        )
                    },
                    {
                        StatTile(
                            icon = Icons.Rounded.Schedule,
                            label = "Uptime",
                            value = h?.let { Formatters.formatUptime(it.uptimeSeconds) } ?: "—",
                        )
                    },
                    {
                        StatTile(
                            icon = Icons.Rounded.Tag,
                            label = "Version",
                            value = h?.version?.ifBlank { "dev" } ?: "—",
                        )
                    },
                )

                ResponsiveGrid(items = statCards, columns = statCols) { card -> card() }

                Text("Managed Resources", style = MaterialTheme.typography.titleMedium)

                val onlineServers = servers.count { it.status == "online" }
                val runningDeploys = deployments.count { it.status == "running" }
                val activeDomains = domains.count { it.status == "active" }

                val resourceCards = listOf<@Composable () -> Unit>(
                    {
                        ResourceSummaryCard(
                            icon = Icons.Rounded.Dns,
                            title = "Servers",
                            count = servers.size,
                            detail = "$onlineServers online via agentless SSH",
                            buttonText = "Manage Servers",
                            onClick = onNavigateServers,
                        )
                    },
                    {
                        ResourceSummaryCard(
                            icon = Icons.Rounded.RocketLaunch,
                            title = "Deployments",
                            count = deployments.size,
                            detail = "$runningDeploys running • Git & Compose apps",
                            buttonText = "View Deployments",
                            onClick = onNavigateDeployments,
                        )
                    },
                    {
                        ResourceSummaryCard(
                            icon = Icons.Rounded.Language,
                            title = "Domains",
                            count = domains.size,
                            detail = "$activeDomains active • Nginx & TLS",
                            buttonText = "Configure Domains",
                            onClick = onNavigateDomains,
                        )
                    },
                )

                ResponsiveGrid(items = resourceCards, columns = resourceCols) { card -> card() }

                if (servers.isEmpty()) {
                    DockCard(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(Icons.Rounded.Flag, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Text("Getting Started", style = MaterialTheme.typography.titleMedium)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "This instance has no servers yet. Connect a machine over SSH and DockDeploy will discover running containers, Nginx virtual hosts, and Compose stacks.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(10.dp))
                        listOf(
                            "1. Add a server with SSH credentials and verify its host key.",
                            "2. Inspect containers, metrics, files, and security audits.",
                            "3. Create deployments from Git repositories or Compose files.",
                            "4. Attach domains and issue Let's Encrypt TLS certificates.",
                        ).forEach { step ->
                            Text(step, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ResourceSummaryCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    count: Int,
    detail: String,
    buttonText: String,
    onClick: () -> Unit,
) {
    DockCard(onClick = onClick) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(text = count.toString(), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(4.dp))
        Text(text = detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
            Text(buttonText)
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Rounded.ArrowOutward, contentDescription = null, modifier = Modifier.size(16.dp))
        }
    }
}
