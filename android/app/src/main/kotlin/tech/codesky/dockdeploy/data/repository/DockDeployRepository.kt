package tech.codesky.dockdeploy.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import tech.codesky.dockdeploy.data.model.*
import tech.codesky.dockdeploy.data.network.ApiClient
import tech.codesky.dockdeploy.data.network.SseEvent
import java.io.OutputStream
import java.net.URLEncoder

@Serializable private data class ServersWrapper(val servers: List<Server> = emptyList())
@Serializable private data class ContainersWrapper(val containers: List<Container> = emptyList())
@Serializable private data class ImagesWrapper(val images: List<ImageSummary>? = null)
@Serializable private data class VolumesWrapper(val volumes: List<VolumeSummary>? = null)
@Serializable private data class NetworksWrapper(val networks: List<NetworkSummary>? = null)
@Serializable private data class MembersWrapper(val members: List<ServerMember> = emptyList())
@Serializable private data class DeploymentsWrapper(val deployments: List<Deployment> = emptyList())
@Serializable private data class RunsWrapper(val runs: List<Run> = emptyList())
@Serializable private data class EnvWrapper(val env: List<EnvVar>? = null)
@Serializable private data class DomainsWrapper(val domains: List<Domain> = emptyList())
@Serializable private data class GitCredentialsWrapper(val credentials: List<GitCredential>? = null)
@Serializable private data class RegistriesWrapper(val registries: List<Registry>? = null)
@Serializable private data class UsersWrapper(val users: List<User> = emptyList())
@Serializable private data class UserServerGrantsWrapper(val grants: List<UserServerGrant> = emptyList())
@Serializable private data class InvitationsWrapper(val invitations: List<Invitation> = emptyList())
@Serializable private data class TokensWrapper(val tokens: List<ApiTokenSummary> = emptyList())
@Serializable private data class SessionsWrapper(val sessions: List<SessionSummary> = emptyList())
@Serializable private data class ConversationsWrapper(val conversations: List<AIConversation>? = null)
@Serializable private data class AutoDetectServerWrapper(
    @SerialName("server_id") val serverId: String = "",
    val discovery: DiscoveryReport,
)
@Serializable private data class AutoDetectAllWrapper(val reports: List<DiscoveryReport> = emptyList())
@Serializable private data class AIConversationDetail(
    val conversation: AIConversation,
    val messages: List<AIMessage> = emptyList(),
)

/**
 * Single typed gateway to the entire DockDeploy REST, SSE, and WebSocket API.
 */
class DockDeployRepository(val api: ApiClient) {

    // --- Health & Auth ------------------------------------------------------

    suspend fun getHealth(overrideBaseUrl: String? = null): Health =
        api.get("/health", overrideBaseUrl = overrideBaseUrl)

    suspend fun getSetupStatus(overrideBaseUrl: String? = null): SetupStatus =
        api.get("/auth/setup", overrideBaseUrl = overrideBaseUrl)

    suspend fun setupAdmin(email: String, name: String, password: String): CurrentUser =
        api.postTyped("/auth/setup", SetupRequest(email, name, password))

    suspend fun login(email: String, password: String): LoginOutcome {
        val raw: JsonObject = api.postTyped("/auth/login", LoginRequest(email, password))
        val requires2fa = raw["requires_2fa"]?.jsonPrimitive?.booleanOrNull == true
        return if (requires2fa) {
            val tempToken = raw["temp_token"]?.jsonPrimitive?.content.orEmpty()
            LoginOutcome.RequiresTwoFactor(tempToken)
        } else {
            LoginOutcome.Authenticated(api.json.decodeFromJsonElement<CurrentUser>(raw))
        }
    }

    suspend fun login2FA(tempToken: String, code: String): CurrentUser =
        api.postTyped("/auth/login/2fa", Login2FARequest(tempToken, code))

    suspend fun getCurrentUser(): CurrentUser = api.get("/auth/me")

    suspend fun updateProfile(name: String): CurrentUser {
        @Serializable data class Req(val name: String)
        return api.patchTyped("/auth/me", Req(name))
    }

    suspend fun changePassword(currentPassword: String, newPassword: String) {
        @Serializable data class Req(
            @SerialName("current_password") val currentPassword: String,
            @SerialName("new_password") val newPassword: String,
        )
        api.postTyped<Req, Unit>("/auth/password", Req(currentPassword, newPassword))
    }

    suspend fun logout() {
        api.post<Unit>("/auth/logout")
    }

    suspend fun previewInvitation(token: String, overrideBaseUrl: String? = null): InvitationPreview =
        api.get("/auth/invitations/${encode(token)}", overrideBaseUrl = overrideBaseUrl)

    suspend fun acceptInvitation(token: String, name: String, password: String): CurrentUser =
        api.postTyped("/auth/invitations/${encode(token)}/accept", AcceptInvitationRequest(name, password))

    suspend fun listSessions(): List<SessionSummary> =
        api.get<SessionsWrapper>("/auth/sessions").sessions

    suspend fun revokeSession(sessionId: String) {
        api.delete<Unit>("/auth/sessions/$sessionId")
    }

    suspend fun get2FAStatus(): TwoFactorStatus = api.get("/auth/2fa/status")
    suspend fun setup2FA(): TwoFactorSetupResponse = api.post("/auth/2fa/setup")
    suspend fun enable2FA(code: String): TwoFactorEnableResponse {
        @Serializable data class Req(val code: String)
        return api.postTyped("/auth/2fa/enable", Req(code))
    }

    suspend fun disable2FA(password: String? = null, code: String? = null) {
        @Serializable data class Req(val password: String? = null, val code: String? = null)
        api.postTyped<Req, Unit>("/auth/2fa/disable", Req(password, code))
    }

    suspend fun regenerateRecoveryCodes(password: String): RecoveryCodesResponse {
        @Serializable data class Req(val password: String)
        return api.postTyped("/auth/2fa/recovery-codes", Req(password))
    }

    // --- Servers & Containers -----------------------------------------------

    suspend fun listServers(): List<Server> = api.get<ServersWrapper>("/servers").servers
    suspend fun getServer(serverId: String): Server = api.get("/servers/$serverId")

    suspend fun fingerprintServer(host: String, port: Int): FingerprintResponse {
        @Serializable data class Req(val host: String, val port: Int)
        return api.postTyped("/servers/fingerprint", Req(host, port))
    }

    suspend fun createServer(req: CreateServerRequest): CreateServerResult =
        api.postTyped("/servers", req)

    suspend fun updateServer(serverId: String, name: String, dockerSocket: String): Server {
        @Serializable data class Req(val name: String, @SerialName("docker_socket") val dockerSocket: String)
        return api.patchTyped("/servers/$serverId", Req(name, dockerSocket))
    }

    suspend fun deleteServer(serverId: String) {
        api.delete<Unit>("/servers/$serverId")
    }

    suspend fun probeServer(serverId: String): ProbeServerResult =
        api.post("/servers/$serverId/probe")

    suspend fun autoDetectServer(serverId: String): DiscoveryReport =
        api.post<AutoDetectServerWrapper>("/servers/$serverId/autodetect").discovery

    suspend fun autoDetectAllServers(): List<DiscoveryReport> =
        api.post<AutoDetectAllWrapper>("/servers/autodetect-all").reports

    suspend fun listServerMembers(serverId: String): List<ServerMember> =
        api.get<MembersWrapper>("/servers/$serverId/members").members

    suspend fun grantServerAccess(serverId: String, userId: String, permission: String) {
        @Serializable data class Req(@SerialName("user_id") val userId: String, val permission: String)
        api.postTyped<Req, Unit>("/servers/$serverId/members", Req(userId, permission))
    }

    suspend fun revokeServerAccess(serverId: String, userId: String) {
        api.delete<Unit>("/servers/$serverId/members/$userId")
    }

    suspend fun getDockerInfo(serverId: String): DockerInfo = api.get("/servers/$serverId/docker")

    suspend fun pruneDocker(serverId: String, target: String, allImages: Boolean = false): DockerPruneResult {
        @Serializable data class Req(val target: String, @SerialName("all_images") val allImages: Boolean)
        return api.postTyped("/servers/$serverId/docker/prune", Req(target, allImages))
    }

    suspend fun listImages(serverId: String): List<ImageSummary> =
        api.get<ImagesWrapper>("/servers/$serverId/images").images ?: emptyList()

    suspend fun listVolumes(serverId: String): List<VolumeSummary> =
        api.get<VolumesWrapper>("/servers/$serverId/volumes").volumes ?: emptyList()

    suspend fun listNetworks(serverId: String): List<NetworkSummary> =
        api.get<NetworksWrapper>("/servers/$serverId/networks").networks ?: emptyList()

    suspend fun listContainers(serverId: String): List<Container> =
        api.get<ContainersWrapper>("/servers/$serverId/containers").containers

    suspend fun inspectContainer(serverId: String, containerId: String): JsonObject =
        api.get("/servers/$serverId/containers/$containerId")

    suspend fun containerAction(serverId: String, containerId: String, action: String) {
        @Serializable data class Req(val action: String)
        api.postTyped<Req, Unit>("/servers/$serverId/containers/$containerId/actions", Req(action))
    }

    fun streamContainerLogs(serverId: String, containerId: String, tail: Int = 200): Flow<SseEvent> =
        api.streamSse("/servers/$serverId/containers/$containerId/logs?tail=$tail&follow=true")

    suspend fun getServerMetrics(serverId: String): ServerMetrics =
        api.get("/servers/$serverId/metrics")

    suspend fun getServerSecurityAudit(serverId: String): ServerSecurityAudit =
        api.get("/servers/$serverId/security")

    suspend fun runServerSecurityScan(serverId: String): ServerSecurityAudit =
        api.post("/servers/$serverId/security/scan")

    fun openTerminal(serverId: String, cols: Int, rows: Int, listener: WebSocketListener): WebSocket =
        api.openTerminalWebSocket(serverId, cols, rows, listener)

    suspend fun setServerSudoPassword(serverId: String, sudoPassword: String): Server {
        @Serializable data class Req(@SerialName("sudo_password") val sudoPassword: String)
        return api.postTyped("/servers/$serverId/sudo-password", Req(sudoPassword))
    }

    suspend fun execRoot(
        serverId: String,
        command: String,
        sudoPassword: String? = null,
        saveSudo: Boolean = false,
    ): ExecRootResult {
        @Serializable data class Req(
            val command: String,
            @SerialName("sudo_password") val sudoPassword: String? = null,
            @SerialName("save_sudo") val saveSudo: Boolean = false,
        )
        return api.postTyped("/servers/$serverId/exec-root", Req(command, sudoPassword, saveSudo))
    }

    suspend fun installDocker(
        serverId: String,
        method: String = "script",
        sudoPassword: String? = null,
        saveSudo: Boolean = false,
    ): InstallDockerResult {
        @Serializable data class Req(
            val method: String,
            @SerialName("sudo_password") val sudoPassword: String? = null,
            @SerialName("save_sudo") val saveSudo: Boolean = false,
        )
        return api.postTyped("/servers/$serverId/install-docker", Req(method, sudoPassword, saveSudo))
    }

    // --- Files & Storage ----------------------------------------------------

    suspend fun listFiles(serverId: String, path: String = "~", sudoPassword: String? = null): DirectoryListing {
        val headers = sudoPassword?.takeIf { it.isNotBlank() }?.let { mapOf("X-Sudo-Password" to it) } ?: emptyMap()
        return api.get("/servers/$serverId/files?path=${encode(path)}", headers = headers)
    }

    suspend fun getFileContent(serverId: String, path: String, sudoPassword: String? = null): FileContent {
        val headers = sudoPassword?.takeIf { it.isNotBlank() }?.let { mapOf("X-Sudo-Password" to it) } ?: emptyMap()
        return api.get("/servers/$serverId/files/content?path=${encode(path)}", headers = headers)
    }

    suspend fun saveFileContent(
        serverId: String,
        path: String,
        content: String,
        sudoPassword: String? = null,
        saveSudo: Boolean = false,
    ) {
        @Serializable data class Req(
            val path: String,
            val content: String,
            @SerialName("sudo_password") val sudoPassword: String? = null,
            @SerialName("save_sudo") val saveSudo: Boolean = false,
        )
        api.putTyped<Req, JsonObject>("/servers/$serverId/files/content", Req(path, content, sudoPassword, saveSudo))
    }

    suspend fun createDirectory(
        serverId: String,
        path: String,
        sudoPassword: String? = null,
        saveSudo: Boolean = false,
    ) {
        @Serializable data class Req(
            val path: String,
            @SerialName("sudo_password") val sudoPassword: String? = null,
            @SerialName("save_sudo") val saveSudo: Boolean = false,
        )
        api.postTyped<Req, JsonObject>("/servers/$serverId/files/mkdir", Req(path, sudoPassword, saveSudo))
    }

    suspend fun deleteFile(
        serverId: String,
        path: String,
        sudoPassword: String? = null,
        saveSudo: Boolean = false,
    ) {
        @Serializable data class Req(
            val path: String,
            @SerialName("sudo_password") val sudoPassword: String? = null,
            @SerialName("save_sudo") val saveSudo: Boolean = false,
        )
        api.deleteTyped<Req, JsonObject>("/servers/$serverId/files", Req(path, sudoPassword, saveSudo))
    }

    suspend fun copyFile(serverId: String, sourcePath: String, targetPath: String, sudoPassword: String? = null) {
        @Serializable data class Req(
            @SerialName("source_path") val sourcePath: String,
            @SerialName("target_path") val targetPath: String,
            @SerialName("sudo_password") val sudoPassword: String? = null,
        )
        api.postTyped<Req, JsonObject>("/servers/$serverId/files/copy", Req(sourcePath, targetPath, sudoPassword))
    }

    suspend fun moveFile(serverId: String, sourcePath: String, targetPath: String, sudoPassword: String? = null) {
        @Serializable data class Req(
            @SerialName("source_path") val sourcePath: String,
            @SerialName("target_path") val targetPath: String,
            @SerialName("sudo_password") val sudoPassword: String? = null,
        )
        api.postTyped<Req, JsonObject>("/servers/$serverId/files/move", Req(sourcePath, targetPath, sudoPassword))
    }

    suspend fun chmodFile(serverId: String, path: String, mode: String, sudoPassword: String? = null) {
        @Serializable data class Req(
            val path: String,
            val mode: String,
            @SerialName("sudo_password") val sudoPassword: String? = null,
        )
        api.postTyped<Req, JsonObject>("/servers/$serverId/files/chmod", Req(path, mode, sudoPassword))
    }

    suspend fun scanStorage(
        serverId: String,
        path: String = "/",
        minSizeMb: Int = 10,
        sudoPassword: String? = null,
    ): StorageScanResult {
        val headers = sudoPassword?.takeIf { it.isNotBlank() }?.let { mapOf("X-Sudo-Password" to it) } ?: emptyMap()
        return api.get("/servers/$serverId/files/scan?path=${encode(path)}&min_size_mb=$minSizeMb", headers = headers)
    }

    suspend fun uploadFile(serverId: String, fileName: String, bytes: ByteArray, targetDir: String): UploadResult =
        api.postMultipart("/servers/$serverId/files/upload", fileName, bytes, mapOf("dir" to targetDir))

    suspend fun downloadFile(serverId: String, path: String, output: OutputStream, sudoPassword: String? = null) {
        val headers = sudoPassword?.takeIf { it.isNotBlank() }?.let { mapOf("X-Sudo-Password" to it) } ?: emptyMap()
        api.downloadToStream("/servers/$serverId/files/download?path=${encode(path)}", output, headers)
    }

    suspend fun downloadArchive(serverId: String, path: String, output: OutputStream, sudoPassword: String? = null) {
        val headers = sudoPassword?.takeIf { it.isNotBlank() }?.let { mapOf("X-Sudo-Password" to it) } ?: emptyMap()
        api.downloadToStream("/servers/$serverId/files/archive?path=${encode(path)}", output, headers)
    }

    suspend fun transferFile(
        sourceServerId: String,
        sourcePath: String,
        targetServerId: String,
        targetPath: String,
    ): FileTransferResult {
        @Serializable data class Req(
            @SerialName("source_path") val sourcePath: String,
            @SerialName("target_server_id") val targetServerId: String,
            @SerialName("target_path") val targetPath: String,
        )
        return api.postTyped(
            "/servers/$sourceServerId/files/transfer",
            Req(sourcePath, targetServerId, targetPath),
        )
    }

    // --- Database Backups ---------------------------------------------------

    suspend fun listDatabaseBackups(serverId: String, backupDir: String? = null): ListDatabaseBackupsResult {
        val q = if (!backupDir.isNullOrBlank()) "?backup_dir=${encode(backupDir)}" else ""
        return api.get("/servers/$serverId/database-backups$q")
    }

    suspend fun createDatabaseBackup(serverId: String, req: CreateDatabaseBackupRequest): CreateDatabaseBackupResult =
        api.postTyped("/servers/$serverId/database-backups", req)

    suspend fun restoreDatabaseBackup(serverId: String, req: RestoreDatabaseBackupRequest): RestoreDatabaseBackupResult =
        api.postTyped("/servers/$serverId/database-backups/restore", req)

    suspend fun deleteDatabaseBackup(serverId: String, path: String, sudoPassword: String? = null) {
        @Serializable data class Req(val path: String, @SerialName("sudo_password") val sudoPassword: String? = null)
        api.deleteTyped<Req, JsonObject>("/servers/$serverId/database-backups", Req(path, sudoPassword))
    }

    // --- Deployments, Runs, Env, Webhooks -----------------------------------

    suspend fun listDeployments(): List<Deployment> =
        api.get<DeploymentsWrapper>("/deployments").deployments

    suspend fun getDeployment(deploymentId: String): Deployment =
        api.get("/deployments/$deploymentId")

    suspend fun createDeployment(req: CreateDeploymentRequest): Deployment =
        api.postTyped("/deployments", req)

    suspend fun deleteDeployment(deploymentId: String) {
        api.delete<JsonObject>("/deployments/$deploymentId")
    }

    suspend fun getDeploymentEnv(deploymentId: String): List<EnvVar> =
        api.get<EnvWrapper>("/deployments/$deploymentId/env").env ?: emptyList()

    suspend fun setDeploymentEnv(deploymentId: String, env: List<EnvVar>) {
        @Serializable data class Req(val env: List<EnvVar>)
        api.putTyped<Req, Unit>("/deployments/$deploymentId/env", Req(env))
    }

    suspend fun listRuns(deploymentId: String): List<Run> =
        api.get<RunsWrapper>("/deployments/$deploymentId/runs").runs

    suspend fun triggerDeploy(deploymentId: String, rollbackRunId: String? = null): Run {
        @Serializable data class Req(@SerialName("rollback_run_id") val rollbackRunId: String? = null)
        return api.postTyped("/deployments/$deploymentId/runs", Req(rollbackRunId))
    }

    suspend fun cancelRun(deploymentId: String, runId: String) {
        api.post<Unit>("/deployments/$deploymentId/runs/$runId/cancel")
    }

    fun streamRunLogs(deploymentId: String, runId: String): Flow<SseEvent> =
        api.streamSse("/deployments/$deploymentId/runs/$runId/logs")

    suspend fun getDeploymentWebhook(deploymentId: String): WebhookInfo =
        api.get("/deployments/$deploymentId/webhook")

    suspend fun rotateDeploymentWebhook(deploymentId: String): WebhookInfo =
        api.post("/deployments/$deploymentId/webhook/rotate")

    // --- Domains ------------------------------------------------------------

    suspend fun listDomains(serverId: String? = null, deploymentId: String? = null): List<Domain> {
        val params = buildList {
            if (!serverId.isNullOrBlank()) add("server_id=${encode(serverId)}")
            if (!deploymentId.isNullOrBlank()) add("deployment_id=${encode(deploymentId)}")
        }
        val qs = if (params.isNotEmpty()) "?${params.joinToString("&")}" else ""
        return api.get<DomainsWrapper>("/domains$qs").domains
    }

    suspend fun createDomain(req: CreateDomainRequest): Domain =
        api.postTyped("/domains", req)

    suspend fun deleteDomain(domainId: String) {
        api.delete<Unit>("/domains/$domainId")
    }

    suspend fun issueDomainSSL(domainId: String, sudoPassword: String? = null, saveSudo: Boolean = false): Domain {
        @Serializable data class Req(
            @SerialName("sudo_password") val sudoPassword: String? = null,
            @SerialName("save_sudo") val saveSudo: Boolean = false,
        )
        return api.postTyped("/domains/$domainId/ssl", Req(sudoPassword, saveSudo))
    }

    suspend fun syncDomain(domainId: String, sudoPassword: String? = null, saveSudo: Boolean = false): Domain {
        @Serializable data class Req(
            @SerialName("sudo_password") val sudoPassword: String? = null,
            @SerialName("save_sudo") val saveSudo: Boolean = false,
        )
        return api.postTyped("/domains/$domainId/sync", Req(sudoPassword, saveSudo))
    }

    // --- Credentials & Registries -------------------------------------------

    suspend fun listGitCredentials(): List<GitCredential> =
        api.get<GitCredentialsWrapper>("/git-credentials").credentials ?: emptyList()

    suspend fun createGitCredential(name: String, kind: String, username: String, secret: String): GitCredential {
        @Serializable data class Req(val name: String, val kind: String, val username: String, val secret: String)
        return api.postTyped("/git-credentials", Req(name, kind, username, secret))
    }

    suspend fun deleteGitCredential(id: String) {
        api.delete<Unit>("/git-credentials/$id")
    }

    suspend fun listRegistries(): List<Registry> =
        api.get<RegistriesWrapper>("/registries").registries ?: emptyList()

    suspend fun createRegistry(name: String, url: String, username: String, password: String): Registry {
        @Serializable data class Req(val name: String, val url: String, val username: String, val password: String)
        return api.postTyped("/registries", Req(name, url, username, password))
    }

    suspend fun deleteRegistry(id: String) {
        api.delete<Unit>("/registries/$id")
    }

    // --- AI Assistant -------------------------------------------------------

    suspend fun detectLocalAgents(): DetectionResult = api.get("/ai/detect")
    suspend fun getAISettings(): AISettings = api.get("/ai/settings")
    suspend fun updateAISettings(req: UpdateAISettingsRequest): AISettings =
        api.putTyped("/ai/settings", req)

    suspend fun testAIConnection(provider: String, model: String, baseUrl: String, apiKey: String? = null): String {
        @Serializable data class Req(
            val provider: String,
            val model: String,
            @SerialName("base_url") val baseUrl: String,
            @SerialName("api_key") val apiKey: String? = null,
        )
        @Serializable data class Resp(val message: String = "")
        return api.postTyped<Req, Resp>("/ai/test", Req(provider, model, baseUrl, apiKey)).message
    }

    suspend fun listAIConversations(): List<AIConversation> =
        api.get<ConversationsWrapper>("/ai/conversations").conversations ?: emptyList()

    suspend fun createAIConversation(title: String): AIConversation {
        @Serializable data class Req(val title: String)
        return api.postTyped("/ai/conversations", Req(title))
    }

    suspend fun getAIConversation(conversationId: String): Pair<AIConversation, List<AIMessage>> {
        val res = api.get<AIConversationDetail>("/ai/conversations/$conversationId")
        return res.conversation to res.messages
    }

    suspend fun deleteAIConversation(conversationId: String) {
        api.delete<Unit>("/ai/conversations/$conversationId")
    }

    fun streamAIChat(
        conversationId: String?,
        message: String,
        serverId: String? = null,
        deploymentId: String? = null,
        containerId: String? = null,
    ): Flow<SseEvent> {
        @Serializable data class Req(
            @SerialName("conversation_id") val conversationId: String? = null,
            val message: String,
            @SerialName("server_id") val serverId: String? = null,
            @SerialName("deployment_id") val deploymentId: String? = null,
            @SerialName("container_id") val containerId: String? = null,
        )
        val jsonBody = api.json.encodeToString(Req(conversationId, message, serverId, deploymentId, containerId))
        return api.streamSse("/ai/chat", method = "POST", jsonBody = jsonBody)
    }

    suspend fun diagnoseServer(serverId: String, containerId: String? = null): ServerDiagnosticReport {
        val qs = if (!containerId.isNullOrBlank()) "?container_id=${encode(containerId)}" else ""
        return api.get("/ai/diagnose/servers/$serverId$qs")
    }

    // --- Admin: Users, Invitations, Tokens, Audit ---------------------------

    suspend fun listUsers(): List<User> = api.get<UsersWrapper>("/users").users

    suspend fun createUser(
        email: String,
        name: String,
        password: String,
        role: String,
        serverGrants: List<ServerGrantInput> = emptyList(),
    ): User {
        @Serializable data class Req(
            val email: String,
            val name: String,
            val password: String,
            val role: String,
            @SerialName("server_grants") val serverGrants: List<ServerGrantInput> = emptyList(),
        )
        return api.postTyped("/users", Req(email, name, password, role, serverGrants))
    }

    suspend fun listUserServers(userId: String): List<UserServerGrant> =
        api.get<UserServerGrantsWrapper>("/users/$userId/servers").grants

    suspend fun setUserServers(userId: String, grants: List<ServerGrantInput>): List<UserServerGrant> {
        @Serializable data class Req(val grants: List<ServerGrantInput>)
        return api.putTyped<Req, UserServerGrantsWrapper>("/users/$userId/servers", Req(grants)).grants
    }

    suspend fun updateUser(userId: String, name: String, role: String, status: String): User {
        @Serializable data class Req(val name: String, val role: String, val status: String)
        return api.patchTyped("/users/$userId", Req(name, role, status))
    }

    suspend fun deleteUser(userId: String) {
        api.delete<Unit>("/users/$userId")
    }

    suspend fun listInvitations(): List<Invitation> =
        api.get<InvitationsWrapper>("/invitations").invitations

    suspend fun createInvitation(
        email: String,
        name: String,
        role: String,
        serverGrants: List<ServerGrantInput> = emptyList(),
    ): CreatedInvitation {
        @Serializable data class Req(
            val email: String,
            val name: String,
            val role: String,
            @SerialName("server_grants") val serverGrants: List<ServerGrantInput> = emptyList(),
        )
        return api.postTyped("/invitations", Req(email, name, role, serverGrants))
    }

    suspend fun deleteInvitation(id: String) {
        api.delete<Unit>("/invitations/$id")
    }

    suspend fun listTokens(): List<ApiTokenSummary> =
        api.get<TokensWrapper>("/tokens").tokens

    suspend fun createToken(name: String, expiresInDays: Int): CreatedToken {
        @Serializable data class Req(val name: String, @SerialName("expires_in_days") val expiresInDays: Int)
        return api.postTyped("/tokens", Req(name, expiresInDays))
    }

    suspend fun deleteToken(id: String) {
        api.delete<Unit>("/tokens/$id")
    }

    suspend fun listAudit(limit: Int = 50, before: String? = null): AuditPage {
        val q = buildString {
            append("?limit=$limit")
            if (!before.isNullOrBlank()) append("&before=${encode(before)}")
        }
        return api.get("/audit$q")
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
