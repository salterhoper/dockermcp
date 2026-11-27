package tech.codesky.dockdeploy.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tech.codesky.dockdeploy.data.model.CurrentUser
import tech.codesky.dockdeploy.data.preferences.ThemeMode
import tech.codesky.dockdeploy.data.repository.DockDeployRepository
import tech.codesky.dockdeploy.ui.ai.AIAssistantDialog
import tech.codesky.dockdeploy.ui.auth.AppGateState
import tech.codesky.dockdeploy.ui.components.EmptyStateView
import tech.codesky.dockdeploy.ui.deployments.DeploymentDetailScreen
import tech.codesky.dockdeploy.ui.deployments.DeploymentsListScreen
import tech.codesky.dockdeploy.ui.domains.DomainsScreen
import tech.codesky.dockdeploy.ui.overview.OverviewScreen
import tech.codesky.dockdeploy.ui.servers.ServerDetailScreen
import tech.codesky.dockdeploy.ui.servers.ServersListScreen
import tech.codesky.dockdeploy.ui.settings.SettingsScreen

enum class TopDestination(
    val label: String,
    val icon: ImageVector,
) {
    OVERVIEW("Overview", Icons.Rounded.Dashboard),
    SERVERS("Servers", Icons.Rounded.Dns),
    DEPLOYMENTS("Deploys", Icons.Rounded.RocketLaunch),
    DOMAINS("Domains", Icons.Rounded.Language),
    SETTINGS("Settings", Icons.Rounded.Settings),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DockDeployAuthenticatedShell(
    state: AppGateState.Authenticated,
    repo: DockDeployRepository,
    widthClass: WindowWidthSizeClass,
    themeMode: ThemeMode,
    dynamicColor: Boolean,
    onSetThemeMode: (ThemeMode) -> Unit,
    onSetDynamicColor: (Boolean) -> Unit,
    onSwitchHost: () -> Unit,
    onRefreshUser: () -> Unit,
    onLogout: () -> Unit,
    onSessionExpired: () -> Unit,
) {
    var currentTab by rememberSaveable { mutableStateOf(TopDestination.OVERVIEW) }
    var selectedServerId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedDeploymentId by rememberSaveable { mutableStateOf<String?>(null) }
    var aiOpenForServer by rememberSaveable { mutableStateOf<Pair<Boolean, String?>>(false to null) }

    val isExpanded = widthClass == WindowWidthSizeClass.Expanded

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            TopDestination.entries.forEach { dest ->
                item(
                    icon = { Icon(dest.icon, contentDescription = dest.label) },
                    label = { Text(dest.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    selected = currentTab == dest,
                    onClick = {
                        currentTab = dest
                        if (dest != TopDestination.SERVERS && !isExpanded) selectedServerId = null
                        if (dest != TopDestination.DEPLOYMENTS && !isExpanded) selectedDeploymentId = null
                    },
                )
            }
        },
    ) {
        Scaffold(
            topBar = {
                val hidingTopBarForDetail = !isExpanded && (
                    (currentTab == TopDestination.SERVERS && selectedServerId != null) ||
                        (currentTab == TopDestination.DEPLOYMENTS && selectedDeploymentId != null)
                    )
                if (!hidingTopBarForDetail) {
                    TopAppBar(
                        title = {
                            Column {
                                Text(
                                    text = when (currentTab) {
                                        TopDestination.OVERVIEW -> "DockDeploy"
                                        TopDestination.SERVERS -> "Servers"
                                        TopDestination.DEPLOYMENTS -> "Deployments"
                                        TopDestination.DOMAINS -> "Domains"
                                        TopDestination.SETTINGS -> "Settings"
                                    },
                                    style = MaterialTheme.typography.titleLarge,
                                )
                                Text(
                                    text = state.hostUrl.removePrefix("https://").removePrefix("http://"),
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        },
                        actions = {
                            IconButton(onClick = { aiOpenForServer = true to selectedServerId }) {
                                Icon(Icons.Rounded.AutoAwesome, contentDescription = "AI Assistant")
                            }
                            IconButton(onClick = onSwitchHost) {
                                Icon(Icons.Rounded.SwapHoriz, contentDescription = "Switch Host")
                            }
                        },
                    )
                }
            },
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                when (currentTab) {
                    TopDestination.OVERVIEW -> OverviewScreen(
                        repo = repo,
                        hostUrl = state.hostUrl,
                        widthClass = widthClass,
                        onNavigateServers = { currentTab = TopDestination.SERVERS },
                        onNavigateDeployments = { currentTab = TopDestination.DEPLOYMENTS },
                        onNavigateDomains = { currentTab = TopDestination.DOMAINS },
                        onOpenAiAssistant = { aiOpenForServer = true to null },
                        onSessionExpired = onSessionExpired,
                    )

                    TopDestination.SERVERS -> {
                        if (isExpanded) {
                            Row(modifier = Modifier.fillMaxSize()) {
                                Box(modifier = Modifier.weight(0.42f).fillMaxHeight()) {
                                    ServersListScreen(
                                        repo = repo,
                                        user = state.user,
                                        widthClass = WindowWidthSizeClass.Compact,
                                        selectedServerId = selectedServerId,
                                        onSelectServer = { selectedServerId = it.id },
                                        onSessionExpired = onSessionExpired,
                                    )
                                }
                                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                Box(modifier = Modifier.weight(0.58f).fillMaxHeight()) {
                                    val sid = selectedServerId
                                    if (sid != null) {
                                        ServerDetailScreen(
                                            repo = repo,
                                            user = state.user,
                                            serverId = sid,
                                            showBack = false,
                                            onBack = {},
                                            onOpenAiForServer = { aiOpenForServer = true to it },
                                            onDeleted = { selectedServerId = null },
                                            onSessionExpired = onSessionExpired,
                                        )
                                    } else {
                                        Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                                            EmptyStateView(
                                                icon = Icons.Rounded.Dns,
                                                title = "Select a Server",
                                                description = "Choose a server from the list to manage containers, metrics, security audits, files, and SSH terminal.",
                                            )
                                        }
                                    }
                                }
                            }
                        } else {
                            val sid = selectedServerId
                            if (sid != null) {
                                ServerDetailScreen(
                                    repo = repo,
                                    user = state.user,
                                    serverId = sid,
                                    showBack = true,
                                    onBack = { selectedServerId = null },
                                    onOpenAiForServer = { aiOpenForServer = true to it },
                                    onDeleted = { selectedServerId = null },
                                    onSessionExpired = onSessionExpired,
                                )
                            } else {
                                ServersListScreen(
                                    repo = repo,
                                    user = state.user,
                                    widthClass = widthClass,
                                    onSelectServer = { selectedServerId = it.id },
                                    onSessionExpired = onSessionExpired,
                                )
                            }
                        }
                    }

                    TopDestination.DEPLOYMENTS -> {
                        if (isExpanded) {
                            Row(modifier = Modifier.fillMaxSize()) {
                                Box(modifier = Modifier.weight(0.42f).fillMaxHeight()) {
                                    DeploymentsListScreen(
                                        repo = repo,
                                        user = state.user,
                                        widthClass = WindowWidthSizeClass.Compact,
                                        selectedDeploymentId = selectedDeploymentId,
                                        onSelectDeployment = { selectedDeploymentId = it.id },
                                        onSessionExpired = onSessionExpired,
                                    )
                                }
                                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                Box(modifier = Modifier.weight(0.58f).fillMaxHeight()) {
                                    val did = selectedDeploymentId
                                    if (did != null) {
                                        DeploymentDetailScreen(
                                            repo = repo,
                                            user = state.user,
                                            deploymentId = did,
                                            showBack = false,
                                            onBack = {},
                                            onDeleted = { selectedDeploymentId = null },
                                            onSessionExpired = onSessionExpired,
                                        )
                                    } else {
                                        Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                                            EmptyStateView(
                                                icon = Icons.Rounded.RocketLaunch,
                                                title = "Select a Deployment",
                                                description = "Choose a deployment to view live build logs, rollback runs, env vars, domains, and webhooks.",
                                            )
                                        }
                                    }
                                }
                            }
                        } else {
                            val did = selectedDeploymentId
                            if (did != null) {
                                DeploymentDetailScreen(
                                    repo = repo,
                                    user = state.user,
                                    deploymentId = did,
                                    showBack = true,
                                    onBack = { selectedDeploymentId = null },
                                    onDeleted = { selectedDeploymentId = null },
                                    onSessionExpired = onSessionExpired,
                                )
                            } else {
                                DeploymentsListScreen(
                                    repo = repo,
                                    user = state.user,
                                    widthClass = widthClass,
                                    onSelectDeployment = { selectedDeploymentId = it.id },
                                    onSessionExpired = onSessionExpired,
                                )
                            }
                        }
                    }

                    TopDestination.DOMAINS -> DomainsScreen(
                        repo = repo,
                        user = state.user,
                        widthClass = widthClass,
                        onSessionExpired = onSessionExpired,
                    )

                    TopDestination.SETTINGS -> SettingsScreen(
                        repo = repo,
                        user = state.user,
                        hostUrl = state.hostUrl,
                        themeMode = themeMode,
                        dynamicColor = dynamicColor,
                        onSetThemeMode = onSetThemeMode,
                        onSetDynamicColor = onSetDynamicColor,
                        onSwitchHost = onSwitchHost,
                        onRefreshUser = onRefreshUser,
                        onLogout = onLogout,
                    )
                }
            }
        }
    }

    if (aiOpenForServer.first) {
        AIAssistantDialog(
            repo = repo,
            user = state.user,
            initialServerId = aiOpenForServer.second,
            onDismiss = { aiOpenForServer = false to null },
        )
    }
}
