package tech.codesky.dockdeploy

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import tech.codesky.dockdeploy.ui.DockDeployAuthenticatedShell
import tech.codesky.dockdeploy.ui.auth.*
import tech.codesky.dockdeploy.ui.theme.DockDeployTheme

class MainActivity : ComponentActivity() {

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as DockDeployApplication

        setContent {
            val sessionVm: SessionViewModel = viewModel(
                factory = object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T {
                        return SessionViewModel(app.preferences, app.repository) as T
                    }
                },
            )

            val gate by sessionVm.gate.collectAsState()
            val authUi by sessionVm.authUi.collectAsState()
            val savedHosts by sessionVm.savedHosts.collectAsState()
            val themeMode by sessionVm.themeMode.collectAsState()
            val dynamicColor by sessionVm.useDynamicColor.collectAsState()
            val windowSizeClass = calculateWindowSizeClass(this)

            DockDeployTheme(themeMode = themeMode, dynamicColor = dynamicColor) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    when (val current = gate) {
                        AppGateState.Booting -> {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    CircularProgressIndicator()
                                    Text(
                                        text = "Connecting to DockDeploy...",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }

                        is AppGateState.NeedHost -> {
                            ConnectHostScreen(
                                savedHosts = savedHosts,
                                uiState = authUi,
                                onConnect = sessionVm::connectToHost,
                                onRemoveSavedHost = sessionVm::removeSavedHost,
                            )
                        }

                        is AppGateState.NeedSetup -> {
                            SetupAdminScreen(
                                hostUrl = current.hostUrl,
                                health = current.health,
                                uiState = authUi,
                                onSwitchHost = sessionVm::switchHost,
                                onSetup = sessionVm::setupAdmin,
                            )
                        }

                        is AppGateState.NeedLogin -> {
                            LoginScreen(
                                hostUrl = current.hostUrl,
                                health = current.health,
                                notice = current.notice,
                                uiState = authUi,
                                onSwitchHost = sessionVm::switchHost,
                                onLogin = sessionVm::login,
                                onVerify2FA = sessionVm::loginWith2FA,
                                onCancel2FA = sessionVm::cancel2FA,
                                onLoginToken = sessionVm::loginWithApiToken,
                                onPreviewInvite = sessionVm::previewInvitation,
                                onAcceptInvite = sessionVm::acceptInvitation,
                                onClearInvite = sessionVm::clearInvitation,
                            )
                        }

                        is AppGateState.Authenticated -> {
                            DockDeployAuthenticatedShell(
                                state = current,
                                repo = app.repository,
                                widthClass = windowSizeClass.widthSizeClass,
                                themeMode = themeMode,
                                dynamicColor = dynamicColor,
                                onSetThemeMode = sessionVm::setThemeMode,
                                onSetDynamicColor = sessionVm::setDynamicColor,
                                onSwitchHost = sessionVm::switchHost,
                                onRefreshUser = sessionVm::refreshUser,
                                onLogout = sessionVm::logout,
                                onSessionExpired = sessionVm::onSessionExpired,
                            )
                        }
                    }
                }
            }
        }
    }
}
