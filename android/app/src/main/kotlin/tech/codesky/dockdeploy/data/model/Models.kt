package tech.codesky.dockdeploy.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// --- Health & Auth ----------------------------------------------------------

@Serializable
data class Health(
    val status: String = "unknown",
    val version: String = "",
    @SerialName("uptime_seconds") val uptimeSeconds: Long = 0,
    val database: String = "unknown",
)

@Serializable
data class SetupStatus(
    val needed: Boolean = false,
)

@Serializable
data class User(
    val id: String,
    val email: String,
    val name: String,
    val role: String,
    val status: String,
    @SerialName("last_login_at") val lastLoginAt: String? = null,
    @SerialName("totp_enabled") val totpEnabled: Boolean = false,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
)

@Serializable
data class CurrentUser(
    val id: String,
    val email: String,
    val name: String,
    val role: String,
    val status: String,
    @SerialName("last_login_at") val lastLoginAt: String? = null,
    @SerialName("totp_enabled") val totpEnabled: Boolean = false,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
    val permissions: List<String> = emptyList(),
) {
    fun can(permission: String): Boolean = permissions.contains(permission)
    val isAdmin: Boolean get() = role == "admin"
}

sealed interface LoginOutcome {
    data class Authenticated(val user: CurrentUser) : LoginOutcome
    data class RequiresTwoFactor(val tempToken: String) : LoginOutcome
}

@Serializable
data class LoginRequest(
    val email: String,
    val password: String,
)

@Serializable
data class Login2FARequest(
    @SerialName("temp_token") val tempToken: String,
    val code: String,
)

@Serializable
data class SetupRequest(
    val email: String,
    val name: String,
    val password: String,
)

@Serializable
data class InvitationPreview(
    val email: String,
    val name: String = "",
    val role: String,
)

@Serializable
data class AcceptInvitationRequest(
    val name: String,
    val password: String,
)

@Serializable
data class SessionSummary(
    val id: String,
    val current: Boolean = false,
    val ip: String? = null,
    @SerialName("user_agent") val userAgent: String? = null,
    @SerialName("last_used_at") val lastUsedAt: String = "",
    @SerialName("created_at") val createdAt: String = "",
)

@Serializable
data class TwoFactorStatus(
    val enabled: Boolean = false,
)

@Serializable
data class TwoFactorSetupResponse(
    val secret: String,
    @SerialName("otpauth_url") val otpauthUrl: String,
)

@Serializable
data class TwoFactorEnableResponse(
    val success: Boolean = false,
    @SerialName("recovery_codes") val recoveryCodes: List<String> = emptyList(),
)

@Serializable
data class RecoveryCodesResponse(
    @SerialName("recovery_codes") val recoveryCodes: List<String> = emptyList(),
)

// --- Servers & Docker -------------------------------------------------------

@Serializable
data class Capabilities(
    val os: String = "",
    val kernel: String = "",
    @SerialName("docker_version") val dockerVersion: String = "",
    @SerialName("docker_socket_ok") val dockerSocketOk: Boolean = false,
    @SerialName("compose_command") val composeCommand: String = "",
    @SerialName("compose_version") val composeVersion: String = "",
    @SerialName("sudo_mode") val sudoMode: String = "none",
    @SerialName("nginx_version") val nginxVersion: String = "",
    @SerialName("nginx_layout") val nginxLayout: String = "",
    @SerialName("certbot_version") val certbotVersion: String = "",
    @SerialName("git_version") val gitVersion: String = "",
    val warnings: List<String>? = null,
)

@Serializable
data class Server(
    val id: String,
    val name: String,
    val host: String,
    val port: Int = 22,
    val username: String = "",
    @SerialName("auth_method") val authMethod: String = "key",
    @SerialName("host_key_fingerprint") val hostKeyFingerprint: String = "",
    @SerialName("docker_socket") val dockerSocket: String = "/var/run/docker.sock",
    val status: String = "unknown",
    @SerialName("status_message") val statusMessage: String = "",
    val capabilities: JsonObject? = null,
    @SerialName("last_seen_at") val lastSeenAt: String? = null,
    @SerialName("has_sudo_password") val hasSudoPassword: Boolean = false,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
)

@Serializable
data class FingerprintResponse(
    val fingerprint: String,
    @SerialName("key_type") val keyType: String = "",
)

@Serializable
data class CreateServerRequest(
    val name: String,
    val host: String,
    val port: Int,
    val username: String,
    @SerialName("auth_method") val authMethod: String,
    val password: String? = null,
    @SerialName("private_key") val privateKey: String? = null,
    val passphrase: String? = null,
    @SerialName("sudo_password") val sudoPassword: String? = null,
    @SerialName("docker_socket") val dockerSocket: String? = null,
    @SerialName("host_key_fingerprint") val hostKeyFingerprint: String,
)

@Serializable
data class CreateServerResult(
    val server: Server,
    val capabilities: Capabilities? = null,
    val discovery: DiscoveryReport? = null,
)

@Serializable
data class ProbeServerResult(
    val server: Server,
    val capabilities: Capabilities? = null,
    val reachable: Boolean = false,
)

@Serializable
data class ServerMember(
    @SerialName("server_id") val serverId: String = "",
    @SerialName("user_id") val userId: String,
    val email: String,
    val name: String,
    val permission: String,
    @SerialName("created_at") val createdAt: String = "",
)

@Serializable
data class ContainerPort(
    val host: String? = null,
    @SerialName("host_port") val hostPort: Int? = null,
    @SerialName("container_port") val containerPort: Int = 0,
    val protocol: String = "tcp",
)

@Serializable
data class Container(
    val id: String,
    val name: String,
    val image: String,
    @SerialName("image_id") val imageId: String = "",
    val state: String,
    val status: String = "",
    val health: String? = null,
    @SerialName("created_at") val createdAt: String = "",
    val ports: List<ContainerPort> = emptyList(),
    val networks: List<String> = emptyList(),
    @SerialName("managed_by") val managedBy: String? = null,
    @SerialName("deployment_id") val deploymentId: String? = null,
    @SerialName("compose_project") val composeProject: String? = null,
)

@Serializable
data class DockerInfo(
    @SerialName("server_version") val serverVersion: String = "",
    @SerialName("operating_system") val operatingSystem: String = "",
    val architecture: String = "",
    val cpus: Int = 0,
    @SerialName("memory_bytes") val memoryBytes: Long = 0,
    val containers: Int = 0,
    @SerialName("containers_running") val containersRunning: Int = 0,
    @SerialName("containers_stopped") val containersStopped: Int = 0,
    val images: Int = 0,
)

@Serializable
data class ImageSummary(
    @SerialName("Id") val id: String = "",
    @SerialName("RepoTags") val repoTags: List<String>? = null,
    @SerialName("Size") val size: Long = 0,
    @SerialName("Created") val created: Long = 0,
)

@Serializable
data class VolumeSummary(
    @SerialName("Name") val name: String = "",
    @SerialName("Driver") val driver: String = "",
    @SerialName("Mountpoint") val mountpoint: String = "",
    @SerialName("CreatedAt") val createdAt: String? = null,
)

@Serializable
data class NetworkSummary(
    @SerialName("Id") val id: String = "",
    @SerialName("Name") val name: String = "",
    @SerialName("Driver") val driver: String = "",
    @SerialName("Scope") val scope: String = "",
    @SerialName("Internal") val internal: Boolean = false,
)

@Serializable
data class DockerPruneResult(
    val target: String = "",
    @SerialName("images_deleted") val imagesDeleted: Int = 0,
    @SerialName("containers_deleted") val containersDeleted: Int = 0,
    @SerialName("volumes_deleted") val volumesDeleted: Int = 0,
    @SerialName("build_cache_deleted") val buildCacheDeleted: Int = 0,
    @SerialName("space_reclaimed") val spaceReclaimed: Long = 0,
    val details: List<String>? = null,
)

// --- Metrics & Security -----------------------------------------------------

@Serializable
data class CPUMetrics(
    @SerialName("usage_percent") val usagePercent: Double = 0.0,
    val cores: Int = 0,
    val load1: Double = 0.0,
    val load5: Double = 0.0,
    val load15: Double = 0.0,
)

@Serializable
data class MemoryMetrics(
    @SerialName("total_bytes") val totalBytes: Long = 0,
    @SerialName("used_bytes") val usedBytes: Long = 0,
    @SerialName("available_bytes") val availableBytes: Long = 0,
    @SerialName("used_percent") val usedPercent: Double = 0.0,
    @SerialName("swap_total_bytes") val swapTotalBytes: Long = 0,
    @SerialName("swap_used_bytes") val swapUsedBytes: Long = 0,
    @SerialName("swap_used_percent") val swapUsedPercent: Double = 0.0,
)

@Serializable
data class DiskMetrics(
    val filesystem: String = "",
    val mount: String = "",
    @SerialName("total_bytes") val totalBytes: Long = 0,
    @SerialName("used_bytes") val usedBytes: Long = 0,
    @SerialName("free_bytes") val freeBytes: Long = 0,
    @SerialName("used_percent") val usedPercent: Double = 0.0,
)

@Serializable
data class DockerMetrics(
    val status: String = "",
    @SerialName("server_version") val serverVersion: String = "",
    @SerialName("containers_total") val containersTotal: Int = 0,
    @SerialName("containers_running") val containersRunning: Int = 0,
    @SerialName("containers_paused") val containersPaused: Int = 0,
    @SerialName("containers_stopped") val containersStopped: Int = 0,
    @SerialName("images_count") val imagesCount: Int = 0,
)

@Serializable
data class SystemInfo(
    val hostname: String = "",
    val os: String = "",
    val kernel: String = "",
    @SerialName("uptime_seconds") val uptimeSeconds: Long = 0,
    @SerialName("server_time") val serverTime: String = "",
)

@Serializable
data class ProcessInfo(
    val user: String = "",
    val pid: Int = 0,
    val cpu: Double = 0.0,
    val memory: Double = 0.0,
    val command: String = "",
)

@Serializable
data class ListeningSocket(
    val protocol: String = "",
    val local: String = "",
    val process: String = "",
)

@Serializable
data class TroubleshootingMetrics(
    @SerialName("top_processes_cpu") val topProcessesCpu: List<ProcessInfo>? = null,
    @SerialName("top_processes_mem") val topProcessesMem: List<ProcessInfo>? = null,
    val partitions: List<DiskMetrics>? = null,
    @SerialName("listening_sockets") val listeningSockets: List<ListeningSocket>? = null,
    @SerialName("failed_systemd_units") val failedSystemdUnits: List<String>? = null,
    @SerialName("oom_errors") val oomErrors: List<String>? = null,
    @SerialName("tcp_connection_count") val tcpConnectionCount: Int? = null,
)

@Serializable
data class ServerMetrics(
    @SerialName("server_id") val serverId: String = "",
    @SerialName("collected_at") val collectedAt: String = "",
    val health: String = "healthy",
    @SerialName("health_issues") val healthIssues: List<String> = emptyList(),
    val system: SystemInfo = SystemInfo(),
    val cpu: CPUMetrics = CPUMetrics(),
    val memory: MemoryMetrics = MemoryMetrics(),
    val disk: DiskMetrics = DiskMetrics(),
    val docker: DockerMetrics = DockerMetrics(),
    val troubleshooting: TroubleshootingMetrics? = null,
)

@Serializable
data class SecurityFinding(
    val id: String = "",
    val title: String = "",
    val category: String = "",
    val severity: String = "info",
    val description: String = "",
    val evidence: String = "",
    val remediation: String = "",
)

@Serializable
data class IPCount(
    val ip: String = "",
    val count: Int = 0,
)

@Serializable
data class SSHAttackAnalysis(
    @SerialName("failed_attempts_count") val failedAttemptsCount: Int = 0,
    @SerialName("top_attacker_ips") val topAttackerIps: List<IPCount> = emptyList(),
    @SerialName("invalid_user_attempts") val invalidUserAttempts: Int = 0,
    @SerialName("root_login_attempts") val rootLoginAttempts: Int = 0,
    @SerialName("recent_attacks") val recentAttacks: List<String> = emptyList(),
)

@Serializable
data class ExposedPort(
    val protocol: String = "",
    val port: Int = 0,
    @SerialName("bind_addr") val bindAddr: String = "",
    val process: String = "",
    @SerialName("is_public") val isPublic: Boolean = false,
    @SerialName("is_risky") val isRisky: Boolean = false,
    @SerialName("risk_note") val riskNote: String = "",
)

@Serializable
data class ContainerSecurityRisk(
    @SerialName("container_id") val containerId: String = "",
    val name: String = "",
    val image: String = "",
    @SerialName("is_privileged") val isPrivileged: Boolean = false,
    @SerialName("host_network") val hostNetwork: Boolean = false,
    @SerialName("host_pid") val hostPid: Boolean = false,
    @SerialName("root_user") val rootUser: Boolean = false,
    @SerialName("socket_mounted") val socketMounted: Boolean = false,
    val risks: List<String> = emptyList(),
)

@Serializable
data class WebAttackSummary(
    @SerialName("scans_detected") val scansDetected: Int = 0,
    @SerialName("sqli_attempts") val sqliAttempts: Int = 0,
    @SerialName("path_traversal_attempts") val pathTraversalAttempts: Int = 0,
    @SerialName("command_injection_attempts") val commandInjectionAttempts: Int = 0,
    @SerialName("recent_web_attacks") val recentWebAttacks: List<String> = emptyList(),
)

@Serializable
data class SystemIntegritySummary(
    @SerialName("firewall_status") val firewallStatus: String = "",
    @SerialName("suspicious_processes") val suspiciousProcesses: List<String> = emptyList(),
    @SerialName("world_writable_files") val worldWritableFiles: List<String> = emptyList(),
    @SerialName("suid_binaries_count") val suidBinariesCount: Int = 0,
)

@Serializable
data class ServerSecurityAudit(
    @SerialName("server_id") val serverId: String = "",
    @SerialName("server_name") val serverName: String = "",
    @SerialName("scanned_at") val scannedAt: String = "",
    val score: Int = 0,
    val grade: String = "A",
    val status: String = "secure",
    val summary: String = "",
    @SerialName("critical_count") val criticalCount: Int = 0,
    @SerialName("high_count") val highCount: Int = 0,
    @SerialName("medium_count") val mediumCount: Int = 0,
    @SerialName("low_count") val lowCount: Int = 0,
    @SerialName("info_count") val infoCount: Int = 0,
    val findings: List<SecurityFinding> = emptyList(),
    @SerialName("ssh_analysis") val sshAnalysis: SSHAttackAnalysis = SSHAttackAnalysis(),
    @SerialName("exposed_ports") val exposedPorts: List<ExposedPort> = emptyList(),
    @SerialName("container_risks") val containerRisks: List<ContainerSecurityRisk> = emptyList(),
    @SerialName("web_attacks") val webAttacks: WebAttackSummary = WebAttackSummary(),
    @SerialName("system_integrity") val systemIntegrity: SystemIntegritySummary = SystemIntegritySummary(),
)

// --- Files, Root Exec, Docker Install, Discovery, Backups -------------------

@Serializable
data class FileEntry(
    val name: String,
    val path: String,
    val size: Long = 0,
    val mode: String = "",
    @SerialName("is_dir") val isDir: Boolean = false,
    @SerialName("is_symlink") val isSymlink: Boolean = false,
    @SerialName("mod_time") val modTime: String = "",
    val permissions: String = "",
)

@Serializable
data class DirectoryListing(
    val path: String,
    val parent: String = "",
    val entries: List<FileEntry> = emptyList(),
    @SerialName("total_files") val totalFiles: Int = 0,
    @SerialName("total_dirs") val totalDirs: Int = 0,
    @SerialName("total_bytes") val totalBytes: Long = 0,
)

@Serializable
data class FileContent(
    val name: String,
    val path: String,
    val size: Long = 0,
    val permissions: String = "",
    @SerialName("mod_time") val modTime: String = "",
    @SerialName("is_binary") val isBinary: Boolean = false,
    val content: String = "",
)

@Serializable
data class LargeItem(
    val path: String = "",
    val name: String? = null,
    val size: Long? = null,
    @SerialName("size_bytes") val sizeBytes: Long? = null,
    @SerialName("human_size") val humanSize: String? = null,
    @SerialName("size_human") val sizeHuman: String? = null,
    @SerialName("is_dir") val isDir: Boolean = false,
)

@Serializable
data class StorageScanResult(
    @SerialName("target_path") val targetPath: String = "/",
    @SerialName("top_directories") val topDirectories: List<LargeItem>? = null,
    @SerialName("largest_files") val largestFiles: List<LargeItem>? = null,
    @SerialName("top_large_dirs") val topLargeDirs: List<LargeItem>? = null,
    @SerialName("top_large_files") val topLargeFiles: List<LargeItem>? = null,
)

@Serializable
data class FileTransferResult(
    @SerialName("source_server_id") val sourceServerId: String,
    @SerialName("target_server_id") val targetServerId: String,
    @SerialName("source_path") val sourcePath: String,
    @SerialName("target_path") val targetPath: String,
    @SerialName("bytes_copied") val bytesCopied: Long = 0,
    @SerialName("duration_ms") val durationMs: Long = 0,
)

@Serializable
data class UploadResult(
    val path: String,
    val size: Long = 0,
    val status: String = "uploaded",
)

@Serializable
data class ExecRootResult(
    val stdout: String = "",
    val stderr: String = "",
    @SerialName("exit_code") val exitCode: Int = 0,
    val success: Boolean = false,
)

@Serializable
data class InstallDockerResult(
    val stdout: String = "",
    val stderr: String = "",
    @SerialName("exit_code") val exitCode: Int = 0,
    val success: Boolean = false,
    val capabilities: Capabilities? = null,
)

@Serializable
data class DiscoveredDomain(
    val id: String,
    val hostname: String,
    @SerialName("upstream_port") val upstreamPort: Int = 80,
    @SerialName("ssl_mode") val sslMode: String = "none",
    val action: String = "existing",
)

@Serializable
data class DiscoveredDeployment(
    val id: String,
    val name: String,
    val slug: String = "",
    @SerialName("source_type") val sourceType: String = "",
    val status: String = "",
    val action: String = "existing",
)

@Serializable
data class DiscoveredCredential(
    val id: String,
    val name: String,
    val kind: String = "",
    val username: String = "",
    val action: String = "existing",
)

@Serializable
data class DiscoveryReport(
    @SerialName("server_id") val serverId: String = "",
    @SerialName("server_name") val serverName: String = "",
    val domains: List<DiscoveredDomain> = emptyList(),
    val deployments: List<DiscoveredDeployment> = emptyList(),
    val credentials: List<DiscoveredCredential> = emptyList(),
    val errors: List<String>? = null,
)

@Serializable
data class DatabaseBackupFile(
    val filename: String,
    val path: String,
    @SerialName("size_bytes") val sizeBytes: Long = 0,
    val engine: String,
    @SerialName("database_name") val databaseName: String = "",
    @SerialName("created_at") val createdAt: String = "",
)

@Serializable
data class DiscoveredDatabaseContainer(
    val id: String,
    val name: String,
    val image: String = "",
    val engine: String,
    val status: String = "",
)

@Serializable
data class ListDatabaseBackupsResult(
    @SerialName("backup_dir") val backupDir: String = "/var/backups/dockdeploy/databases",
    val backups: List<DatabaseBackupFile> = emptyList(),
    val containers: List<DiscoveredDatabaseContainer> = emptyList(),
)

@Serializable
data class CreateDatabaseBackupRequest(
    val engine: String,
    val mode: String = "container",
    @SerialName("container_name") val containerName: String? = null,
    @SerialName("database_name") val databaseName: String? = null,
    val username: String? = null,
    val password: String? = null,
    @SerialName("auth_database") val authDatabase: String? = null,
    @SerialName("sqlite_path") val sqlitePath: String? = null,
    @SerialName("backup_dir") val backupDir: String? = null,
    @SerialName("sudo_password") val sudoPassword: String? = null,
    @SerialName("save_sudo") val saveSudo: Boolean = false,
)

@Serializable
data class CreateDatabaseBackupResult(
    val success: Boolean = false,
    @SerialName("backup_file") val backupFile: DatabaseBackupFile? = null,
    val stdout: String = "",
    val stderr: String = "",
    @SerialName("exit_code") val exitCode: Int = 0,
    @SerialName("duration_ms") val durationMs: Long = 0,
)

@Serializable
data class RestoreDatabaseBackupRequest(
    val engine: String,
    val mode: String = "container",
    @SerialName("container_name") val containerName: String? = null,
    @SerialName("database_name") val databaseName: String? = null,
    val username: String? = null,
    val password: String? = null,
    @SerialName("auth_database") val authDatabase: String? = null,
    @SerialName("sqlite_path") val sqlitePath: String? = null,
    @SerialName("backup_path") val backupPath: String,
    @SerialName("drop_existing") val dropExisting: Boolean = false,
    @SerialName("sudo_password") val sudoPassword: String? = null,
    @SerialName("save_sudo") val saveSudo: Boolean = false,
)

@Serializable
data class RestoreDatabaseBackupResult(
    val success: Boolean = false,
    val stdout: String = "",
    val stderr: String = "",
    @SerialName("exit_code") val exitCode: Int = 0,
    @SerialName("duration_ms") val durationMs: Long = 0,
)

// --- Deployments, Domains, Credentials --------------------------------------

@Serializable
data class EnvVar(
    val key: String,
    val value: String,
    @SerialName("is_secret") val isSecret: Boolean = false,
)

@Serializable
data class Deployment(
    val id: String,
    @SerialName("server_id") val serverId: String,
    @SerialName("server_name") val serverName: String? = null,
    val name: String,
    val slug: String = "",
    @SerialName("source_type") val sourceType: String,
    @SerialName("repo_url") val repoUrl: String = "",
    @SerialName("git_ref") val gitRef: String = "",
    @SerialName("git_credential_id") val gitCredentialId: String? = null,
    @SerialName("dockerfile_path") val dockerfilePath: String = "Dockerfile",
    @SerialName("build_context") val buildContext: String = ".",
    @SerialName("compose_path") val composePath: String = "docker-compose.yml",
    @SerialName("compose_content") val composeContent: String = "",
    @SerialName("image_ref") val imageRef: String = "",
    @SerialName("build_strategy") val buildStrategy: String = "remote",
    @SerialName("registry_id") val registryId: String? = null,
    @SerialName("image_name") val imageName: String = "",
    val workdir: String = "",
    @SerialName("host_port") val hostPort: Int? = null,
    @SerialName("container_port") val containerPort: Int = 80,
    val status: String = "never_deployed",
    @SerialName("current_run_id") val currentRunId: String? = null,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
)

@Serializable
data class CreateDeploymentRequest(
    @SerialName("server_id") val serverId: String,
    val name: String,
    @SerialName("source_type") val sourceType: String,
    @SerialName("repo_url") val repoUrl: String? = null,
    @SerialName("git_ref") val gitRef: String? = null,
    @SerialName("git_credential_id") val gitCredentialId: String? = null,
    @SerialName("dockerfile_path") val dockerfilePath: String? = null,
    @SerialName("build_context") val buildContext: String? = null,
    @SerialName("compose_path") val composePath: String? = null,
    @SerialName("compose_content") val composeContent: String? = null,
    @SerialName("image_ref") val imageRef: String? = null,
    @SerialName("build_strategy") val buildStrategy: String = "remote",
    @SerialName("registry_id") val registryId: String? = null,
    @SerialName("image_name") val imageName: String? = null,
    @SerialName("container_port") val containerPort: Int = 80,
    val env: List<EnvVar> = emptyList(),
)

@Serializable
data class Run(
    val id: String,
    @SerialName("deployment_id") val deploymentId: String,
    val number: Int,
    val trigger: String,
    val status: String,
    @SerialName("commit_sha") val commitSha: String = "",
    @SerialName("image_ref") val imageRef: String = "",
    val error: String = "",
    @SerialName("queued_at") val queuedAt: String = "",
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("finished_at") val finishedAt: String? = null,
)

@Serializable
data class WebhookInfo(
    @SerialName("webhook_url") val webhookUrl: String,
    @SerialName("webhook_secret") val webhookSecret: String,
)

@Serializable
data class GitCredential(
    val id: String,
    val name: String,
    val provider: String = "",
    val kind: String = "token",
    val username: String = "",
    @SerialName("created_at") val createdAt: String = "",
)

@Serializable
data class Registry(
    val id: String,
    val name: String,
    val url: String,
    val username: String = "",
    @SerialName("created_at") val createdAt: String = "",
)

@Serializable
data class Domain(
    val id: String,
    @SerialName("deployment_id") val deploymentId: String? = null,
    @SerialName("server_id") val serverId: String,
    val hostname: String,
    @SerialName("upstream_port") val upstreamPort: Int = 80,
    @SerialName("ssl_mode") val sslMode: String = "none",
    val websocket: Boolean = false,
    @SerialName("config_rendered") val configRendered: String = "",
    @SerialName("cert_expires_at") val certExpiresAt: String? = null,
    val status: String = "pending",
    @SerialName("status_message") val statusMessage: String = "",
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
    @SerialName("server_name") val serverName: String? = null,
    @SerialName("deployment_name") val deploymentName: String? = null,
)

@Serializable
data class CreateDomainRequest(
    @SerialName("server_id") val serverId: String,
    @SerialName("deployment_id") val deploymentId: String? = null,
    val hostname: String,
    @SerialName("upstream_port") val upstreamPort: Int,
    @SerialName("ssl_mode") val sslMode: String = "letsencrypt",
    val websocket: Boolean = false,
    @SerialName("sudo_password") val sudoPassword: String? = null,
    @SerialName("save_sudo") val saveSudo: Boolean = true,
)

// --- AI Assistant -----------------------------------------------------------

@Serializable
data class DetectedAgent(
    val id: String,
    val name: String,
    val type: String = "",
    val available: Boolean = false,
    val status: String = "",
    val endpoint: String? = null,
    val command: String? = null,
    val version: String? = null,
    val models: List<String>? = null,
    @SerialName("default_model") val defaultModel: String? = null,
    val description: String = "",
)

@Serializable
data class DetectionResult(
    val agents: List<DetectedAgent> = emptyList(),
    @SerialName("recommended_id") val recommendedId: String? = null,
    @SerialName("has_local_agents") val hasLocalAgents: Boolean = false,
)

@Serializable
data class AISettings(
    @SerialName("user_id") val userId: String = "",
    val provider: String = "openai",
    val model: String = "gpt-4o",
    @SerialName("base_url") val baseUrl: String = "",
    val temperature: Double = 0.7,
    @SerialName("system_prompt_custom") val systemPromptCustom: String = "",
    @SerialName("has_api_key") val hasApiKey: Boolean = false,
)

@Serializable
data class UpdateAISettingsRequest(
    val provider: String,
    val model: String,
    @SerialName("base_url") val baseUrl: String,
    @SerialName("api_key") val apiKey: String? = null,
    val temperature: Double = 0.7,
    @SerialName("system_prompt_custom") val systemPromptCustom: String = "",
)

@Serializable
data class AIConversation(
    val id: String,
    @SerialName("user_id") val userId: String = "",
    val title: String,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
)

@Serializable
data class SafeguardAction(
    val id: String = "",
    val type: String = "",
    val title: String = "",
    val description: String = "",
    @SerialName("danger_level") val dangerLevel: String = "low",
    val permission: String = "self",
    @SerialName("server_id") val serverId: String? = null,
    @SerialName("resource_id") val resourceId: String? = null,
    val payload: Map<String, JsonElement>? = null,
)

@Serializable
data class AIMessageMetadata(
    @SerialName("server_id") val serverId: String? = null,
    @SerialName("deployment_id") val deploymentId: String? = null,
    @SerialName("container_id") val containerId: String? = null,
    @SerialName("safeguard_action") val safeguardAction: SafeguardAction? = null,
)

@Serializable
data class AIMessage(
    val id: String,
    @SerialName("conversation_id") val conversationId: String = "",
    val role: String,
    val content: String,
    val metadata: AIMessageMetadata? = null,
    @SerialName("created_at") val createdAt: String = "",
)

@Serializable
data class ServerDiagnosticReport(
    @SerialName("server_id") val serverId: String = "",
    @SerialName("server_name") val serverName: String = "",
    @SerialName("collected_at") val collectedAt: String = "",
    @SerialName("health_issues") val healthIssues: List<String> = emptyList(),
    @SerialName("summary_markdown") val summaryMarkdown: String = "",
)

// --- Admin: Tokens, Invitations, Audit --------------------------------------

@Serializable
data class ServerGrantInput(
    @SerialName("server_id") val serverId: String,
    val permission: String = "operate",
)

@Serializable
data class UserServerGrant(
    @SerialName("server_id") val serverId: String,
    @SerialName("server_name") val serverName: String = "",
    val host: String = "",
    val permission: String = "operate",
    @SerialName("created_at") val createdAt: String = "",
)

@Serializable
data class ApiTokenSummary(
    val id: String,
    val name: String,
    val prefix: String,
    @SerialName("last_used_at") val lastUsedAt: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("created_at") val createdAt: String = "",
)

@Serializable
data class CreatedToken(
    val id: String,
    val name: String,
    val prefix: String,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("created_at") val createdAt: String = "",
    val token: String,
)

@Serializable
data class Invitation(
    val id: String,
    val email: String,
    val name: String = "",
    val role: String,
    @SerialName("invited_by") val invitedBy: String? = null,
    @SerialName("expires_at") val expiresAt: String = "",
    @SerialName("created_at") val createdAt: String = "",
)

@Serializable
data class CreatedInvitation(
    val id: String,
    val email: String,
    val name: String = "",
    val role: String,
    @SerialName("expires_at") val expiresAt: String = "",
    @SerialName("created_at") val createdAt: String = "",
    val url: String,
)

@Serializable
data class AuditEntry(
    val id: Long,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("actor_email") val actorEmail: String = "",
    val action: String = "",
    @SerialName("resource_type") val resourceType: String = "",
    @SerialName("resource_id") val resourceId: String = "",
    val status: Int = 0,
    val ip: String = "",
    val meta: Map<String, JsonElement>? = null,
    @SerialName("created_at") val createdAt: String = "",
)

@Serializable
data class AuditPage(
    val entries: List<AuditEntry> = emptyList(),
    val before: String? = null,
)

// --- Streaming Events -------------------------------------------------------

@Serializable
data class ContainerLogLine(
    val stream: String = "stdout",
    val text: String = "",
)

@Serializable
data class RunLogLine(
    val seq: Long = 0,
    val stream: String = "stdout",
    val line: String = "",
    val ts: String = "",
)
