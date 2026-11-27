package tech.codesky.dockdeploy.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import tech.codesky.dockdeploy.data.model.*
import tech.codesky.dockdeploy.data.network.ApiException
import tech.codesky.dockdeploy.data.network.HostUrlNormalizer
import tech.codesky.dockdeploy.data.preferences.AppPreferences
import tech.codesky.dockdeploy.data.preferences.SavedHost
import tech.codesky.dockdeploy.data.preferences.ThemeMode
import tech.codesky.dockdeploy.data.repository.DockDeployRepository

sealed interface AppGateState {
    data object Booting : AppGateState
    data class NeedHost(val error: String? = null) : AppGateState
    data class NeedSetup(val hostUrl: String, val health: Health?) : AppGateState
    data class NeedLogin(
        val hostUrl: String,
        val health: Health?,
        val notice: String? = null,
    ) : AppGateState
    data class Authenticated(
        val hostUrl: String,
        val user: CurrentUser,
        val health: Health?,
    ) : AppGateState
}

data class AuthUiState(
    val isBusy: Boolean = false,
    val error: String? = null,
    val fieldErrors: Map<String, String> = emptyMap(),
    val twoFactorTempToken: String? = null,
    val invitationPreview: InvitationPreview? = null,
    val invitationToken: String? = null,
    val probedHealth: Health? = null,
)

class SessionViewModel(
    private val prefs: AppPreferences,
    private val repo: DockDeployRepository,
) : ViewModel() {

    private val _gate = MutableStateFlow<AppGateState>(AppGateState.Booting)
    val gate: StateFlow<AppGateState> = _gate.asStateFlow()

    private val _authUi = MutableStateFlow(AuthUiState())
    val authUi: StateFlow<AuthUiState> = _authUi.asStateFlow()

    val savedHosts: StateFlow<List<SavedHost>> = prefs.savedHosts
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val themeMode: StateFlow<ThemeMode> = prefs.themeMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, ThemeMode.DARK)

    val useDynamicColor: StateFlow<Boolean> = prefs.useDynamicColor
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    init {
        bootstrap()
    }

    fun bootstrap() {
        viewModelScope.launch {
            _gate.value = AppGateState.Booting
            val host = prefs.activeHostUrl.first()
            if (host.isNullOrBlank()) {
                _gate.value = AppGateState.NeedHost()
                return@launch
            }
            repo.api.setTargetHost(host)
            checkHostAndSession(host)
        }
    }

    fun connectToHost(rawInput: String) {
        val normalized = HostUrlNormalizer.normalize(rawInput).getOrElse { err ->
            _authUi.update { it.copy(error = err.message ?: "Invalid host URL.") }
            return
        }

        viewModelScope.launch {
            _authUi.update { it.copy(isBusy = true, error = null, fieldErrors = emptyMap(), probedHealth = null) }
            try {
                val health = repo.getHealth(overrideBaseUrl = normalized.baseUrl)
                repo.api.setTargetHost(normalized.baseUrl)
                prefs.setActiveHost(normalized.baseUrl, health.version)
                _authUi.update { it.copy(isBusy = false, probedHealth = health) }
                checkHostAndSession(normalized.baseUrl, preloadedHealth = health)
            } catch (e: ApiException) {
                _authUi.update {
                    it.copy(
                        isBusy = false,
                        error = "Could not reach DockDeploy at ${normalized.baseUrl}: ${e.message}",
                    )
                }
            } catch (e: Exception) {
                _authUi.update {
                    it.copy(
                        isBusy = false,
                        error = e.message ?: "Connection failed.",
                    )
                }
            }
        }
    }

    private suspend fun checkHostAndSession(hostUrl: String, preloadedHealth: Health? = null) {
        val health = preloadedHealth ?: runCatching { repo.getHealth() }.getOrNull()
        try {
            val me = repo.getCurrentUser()
            _authUi.value = AuthUiState()
            _gate.value = AppGateState.Authenticated(hostUrl, me, health)
        } catch (e: ApiException) {
            if (e.isAuthFailure) {
                val setupNeeded = runCatching { repo.getSetupStatus().needed }.getOrDefault(false)
                _authUi.value = AuthUiState(probedHealth = health)
                _gate.value = if (setupNeeded) {
                    AppGateState.NeedSetup(hostUrl, health)
                } else {
                    AppGateState.NeedLogin(hostUrl, health)
                }
            } else {
                _authUi.value = AuthUiState(error = e.message, probedHealth = health)
                _gate.value = AppGateState.NeedLogin(
                    hostUrl = hostUrl,
                    health = health,
                    notice = "Server unreachable or returned an error (${e.message}). You can retry or switch hosts.",
                )
            }
        }
    }

    fun switchHost() {
        viewModelScope.launch {
            _authUi.value = AuthUiState()
            _gate.value = AppGateState.NeedHost()
        }
    }

    fun removeSavedHost(url: String) {
        viewModelScope.launch {
            prefs.removeSavedHost(url)
            HostUrlNormalizer.normalize(url).getOrNull()?.let {
                repo.api.cookieJar.clearHost(it.httpUrl.host)
            }
        }
    }

    fun login(email: String, password: String) {
        val host = currentHostUrl() ?: return
        viewModelScope.launch {
            _authUi.update { it.copy(isBusy = true, error = null, fieldErrors = emptyMap()) }
            try {
                when (val outcome = repo.login(email.trim(), password)) {
                    is LoginOutcome.RequiresTwoFactor -> {
                        _authUi.update {
                            it.copy(isBusy = false, twoFactorTempToken = outcome.tempToken)
                        }
                    }
                    is LoginOutcome.Authenticated -> {
                        prefs.setApiToken(null)
                        repo.api.apiToken = null
                        val health = _authUi.value.probedHealth ?: runCatching { repo.getHealth() }.getOrNull()
                        _authUi.value = AuthUiState()
                        _gate.value = AppGateState.Authenticated(host, outcome.user, health)
                    }
                }
            } catch (e: ApiException) {
                _authUi.update {
                    it.copy(
                        isBusy = false,
                        error = if (e.fields.isEmpty()) e.message else null,
                        fieldErrors = e.fields,
                    )
                }
            }
        }
    }

    fun loginWith2FA(code: String) {
        val host = currentHostUrl() ?: return
        val tempToken = _authUi.value.twoFactorTempToken ?: return
        viewModelScope.launch {
            _authUi.update { it.copy(isBusy = true, error = null, fieldErrors = emptyMap()) }
            try {
                val user = repo.login2FA(tempToken, code.trim())
                prefs.setApiToken(null)
                repo.api.apiToken = null
                val health = _authUi.value.probedHealth ?: runCatching { repo.getHealth() }.getOrNull()
                _authUi.value = AuthUiState()
                _gate.value = AppGateState.Authenticated(host, user, health)
            } catch (e: ApiException) {
                _authUi.update {
                    it.copy(
                        isBusy = false,
                        error = if (e.fields.isEmpty()) e.message else null,
                        fieldErrors = e.fields,
                    )
                }
            }
        }
    }

    fun cancel2FA() {
        _authUi.update { it.copy(twoFactorTempToken = null, error = null, fieldErrors = emptyMap()) }
    }

    fun loginWithApiToken(token: String) {
        val host = currentHostUrl() ?: return
        val clean = token.trim()
        if (clean.isBlank()) {
            _authUi.update { it.copy(error = "Enter an API token.") }
            return
        }
        viewModelScope.launch {
            _authUi.update { it.copy(isBusy = true, error = null, fieldErrors = emptyMap()) }
            repo.api.apiToken = clean
            try {
                val user = repo.getCurrentUser()
                prefs.setApiToken(clean)
                val health = _authUi.value.probedHealth ?: runCatching { repo.getHealth() }.getOrNull()
                _authUi.value = AuthUiState()
                _gate.value = AppGateState.Authenticated(host, user, health)
            } catch (e: ApiException) {
                repo.api.apiToken = null
                _authUi.update { it.copy(isBusy = false, error = e.message) }
            }
        }
    }

    fun setupAdmin(name: String, email: String, password: String) {
        val host = currentHostUrl() ?: return
        viewModelScope.launch {
            _authUi.update { it.copy(isBusy = true, error = null, fieldErrors = emptyMap()) }
            try {
                val user = repo.setupAdmin(email.trim(), name.trim(), password)
                val health = _authUi.value.probedHealth ?: runCatching { repo.getHealth() }.getOrNull()
                _authUi.value = AuthUiState()
                _gate.value = AppGateState.Authenticated(host, user, health)
            } catch (e: ApiException) {
                _authUi.update {
                    it.copy(
                        isBusy = false,
                        error = if (e.fields.isEmpty()) e.message else null,
                        fieldErrors = e.fields,
                    )
                }
            }
        }
    }

    fun previewInvitation(rawInput: String) {
        val token = rawInput.trim().substringAfterLast("/invite/").substringBefore('?').trim()
        if (token.isBlank()) {
            _authUi.update { it.copy(error = "Enter an invitation token or invite link.") }
            return
        }
        viewModelScope.launch {
            _authUi.update { it.copy(isBusy = true, error = null, fieldErrors = emptyMap()) }
            try {
                val preview = repo.previewInvitation(token)
                _authUi.update {
                    it.copy(isBusy = false, invitationPreview = preview, invitationToken = token)
                }
            } catch (e: ApiException) {
                _authUi.update { it.copy(isBusy = false, error = e.message) }
            }
        }
    }

    fun acceptInvitation(name: String, password: String) {
        val host = currentHostUrl() ?: return
        val token = _authUi.value.invitationToken ?: return
        viewModelScope.launch {
            _authUi.update { it.copy(isBusy = true, error = null, fieldErrors = emptyMap()) }
            try {
                val user = repo.acceptInvitation(token, name.trim(), password)
                val health = _authUi.value.probedHealth ?: runCatching { repo.getHealth() }.getOrNull()
                _authUi.value = AuthUiState()
                _gate.value = AppGateState.Authenticated(host, user, health)
            } catch (e: ApiException) {
                _authUi.update {
                    it.copy(
                        isBusy = false,
                        error = if (e.fields.isEmpty()) e.message else null,
                        fieldErrors = e.fields,
                    )
                }
            }
        }
    }

    fun clearInvitation() {
        _authUi.update { it.copy(invitationPreview = null, invitationToken = null, error = null, fieldErrors = emptyMap()) }
    }

    fun clearError() {
        _authUi.update { it.copy(error = null, fieldErrors = emptyMap()) }
    }

    fun refreshUser() {
        val current = _gate.value as? AppGateState.Authenticated ?: return
        viewModelScope.launch {
            runCatching { repo.getCurrentUser() }
                .onSuccess { updated ->
                    _gate.value = current.copy(user = updated)
                }
                .onFailure { err ->
                    if (err is ApiException && err.isAuthFailure) {
                        onSessionExpired()
                    }
                }
        }
    }

    fun logout() {
        val host = currentHostUrl()
        viewModelScope.launch {
            runCatching { repo.logout() }
            prefs.setApiToken(null)
            repo.api.apiToken = null
            if (!host.isNullOrBlank()) {
                HostUrlNormalizer.normalize(host).getOrNull()?.let {
                    repo.api.cookieJar.clearHost(it.httpUrl.host)
                }
                _authUi.value = AuthUiState()
                _gate.value = AppGateState.NeedLogin(host, null)
            } else {
                _gate.value = AppGateState.NeedHost()
            }
        }
    }

    fun onSessionExpired() {
        val host = currentHostUrl()
        viewModelScope.launch {
            prefs.setApiToken(null)
            repo.api.apiToken = null
            if (!host.isNullOrBlank()) {
                _gate.value = AppGateState.NeedLogin(host, null, notice = "Your session expired. Sign in again.")
            } else {
                _gate.value = AppGateState.NeedHost()
            }
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { prefs.setThemeMode(mode) }
    }

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch { prefs.setDynamicColor(enabled) }
    }

    private fun currentHostUrl(): String? = when (val g = _gate.value) {
        is AppGateState.Authenticated -> g.hostUrl
        is AppGateState.NeedLogin -> g.hostUrl
        is AppGateState.NeedSetup -> g.hostUrl
        else -> repo.api.baseUrl.takeIf { it.isNotBlank() }
    }
}
