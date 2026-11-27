package tech.codesky.dockdeploy.ui.servers

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import tech.codesky.dockdeploy.data.model.*
import tech.codesky.dockdeploy.data.network.ApiException
import tech.codesky.dockdeploy.data.repository.DockDeployRepository
import tech.codesky.dockdeploy.ui.components.*
import tech.codesky.dockdeploy.util.Formatters

@Composable
fun ServerBackupsTab(
    repo: DockDeployRepository,
    serverId: String,
    canWrite: Boolean,
) {
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<ListDatabaseBackupsResult?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var statusMsg by remember { mutableStateOf<String?>(null) }

    var showCreateModal by remember { mutableStateOf(false) }
    var restoreTarget by remember { mutableStateOf<DatabaseBackupFile?>(null) }
    var deleteTarget by remember { mutableStateOf<DatabaseBackupFile?>(null) }

    fun load() {
        scope.launch {
            loading = true
            error = null
            try {
                result = repo.listDatabaseBackups(serverId)
            } catch (e: ApiException) {
                error = e.message
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(serverId) { load() }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Dir: ${result?.backupDir ?: "/var/backups/dockdeploy/databases"}",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (canWrite) {
                Button(onClick = { showCreateModal = true }) {
                    Icon(Icons.Rounded.Backup, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("New Backup")
                }
            }
        }

        if (statusMsg != null) AlertBanner(message = statusMsg!!, tone = BadgeTone.SUCCESS, onDismiss = { statusMsg = null })
        if (error != null) AlertBanner(message = error!!, tone = BadgeTone.DANGER, onDismiss = { error = null })

        if (loading) {
            Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            val data = result
            if (data != null) {
                if (data.containers.isNotEmpty()) {
                    Text("Detected Database Containers", style = MaterialTheme.typography.titleSmall)
                    data.containers.forEach { db ->
                        DockCard(contentPadding = 12.dp) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(db.name, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        "${db.engine.uppercase()} • ${db.image}",
                                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                StatusBadge(label = db.engine, tone = BadgeTone.INFO)
                            }
                        }
                    }
                }

                Text("Backup Archives (${data.backups.size})", style = MaterialTheme.typography.titleSmall)
                if (data.backups.isEmpty()) {
                    EmptyStateView(
                        icon = Icons.Rounded.Storage,
                        title = "No database backups yet",
                        description = "Create a dump from any PostgreSQL, MySQL, MongoDB, Redis, or SQLite database.",
                    )
                } else {
                    data.backups.forEach { file ->
                        DockCard(contentPadding = 12.dp) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            file.filename,
                                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                                        )
                                        Text(
                                            "${file.engine} • ${file.databaseName} • ${Formatters.formatBytes(file.sizeBytes)}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    StatusBadge(label = file.engine, tone = BadgeTone.NEUTRAL)
                                }
                                if (canWrite) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(onClick = { restoreTarget = file }) {
                                            Icon(Icons.Rounded.Restore, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("Restore")
                                        }
                                        TextButton(onClick = { deleteTarget = file }) {
                                            Icon(Icons.Rounded.DeleteOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("Delete", color = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreateModal) {
        DatabaseBackupDialog(
            containers = result?.containers ?: emptyList(),
            onDismiss = { showCreateModal = false },
            onSubmit = { req ->
                scope.launch {
                    try {
                        val res = repo.createDatabaseBackup(serverId, req)
                        if (res.success) {
                            statusMsg = "Backup created (${res.durationMs}ms)"
                            showCreateModal = false
                            load()
                        } else {
                            error = res.stderr.ifBlank { "Backup exited with code ${res.exitCode}" }
                        }
                    } catch (e: ApiException) {
                        error = e.message
                    }
                }
            },
        )
    }

    if (restoreTarget != null) {
        DatabaseRestoreDialog(
            backup = restoreTarget!!,
            containers = result?.containers ?: emptyList(),
            onDismiss = { restoreTarget = null },
            onSubmit = { req ->
                scope.launch {
                    try {
                        val res = repo.restoreDatabaseBackup(serverId, req)
                        if (res.success) {
                            statusMsg = "Database restored (${res.durationMs}ms)"
                            restoreTarget = null
                        } else {
                            error = res.stderr.ifBlank { "Restore exited with code ${res.exitCode}" }
                        }
                    } catch (e: ApiException) {
                        error = e.message
                    }
                }
            },
        )
    }

    if (deleteTarget != null) {
        ConfirmActionDialog(
            title = "Delete Backup?",
            message = "Delete ${deleteTarget!!.filename} permanently from the server?",
            confirmLabel = "Delete",
            destructive = true,
            onDismiss = { deleteTarget = null },
            onConfirm = {
                val path = deleteTarget!!.path
                deleteTarget = null
                scope.launch {
                    runCatching { repo.deleteDatabaseBackup(serverId, path) }
                        .onSuccess { load() }
                        .onFailure { error = it.message }
                }
            },
        )
    }
}

@Composable
private fun DatabaseBackupDialog(
    containers: List<DiscoveredDatabaseContainer>,
    onDismiss: () -> Unit,
    onSubmit: (CreateDatabaseBackupRequest) -> Unit,
) {
    val engines = listOf("postgres", "mysql", "mongo", "redis", "sqlite")
    var engine by remember { mutableStateOf(containers.firstOrNull()?.engine ?: "postgres") }
    var mode by remember { mutableStateOf("container") }
    var containerName by remember { mutableStateOf(containers.firstOrNull()?.name.orEmpty()) }
    var databaseName by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var sqlitePath by remember { mutableStateOf("") }
    var sudoPassword by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create Database Backup") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Engine", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    engines.take(3).forEach { e ->
                        FilterChip(selected = engine == e, onClick = { engine = e }, label = { Text(e) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    engines.drop(3).forEach { e ->
                        FilterChip(selected = engine == e, onClick = { engine = e }, label = { Text(e) })
                    }
                }

                if (engine != "sqlite") {
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SegmentedButton(selected = mode == "container", onClick = { mode = "container" }, shape = SegmentedButtonDefaults.itemShape(0, 2), label = { Text("Container") })
                        SegmentedButton(selected = mode == "host", onClick = { mode = "host" }, shape = SegmentedButtonDefaults.itemShape(1, 2), label = { Text("Host") })
                    }
                    if (mode == "container") {
                        OutlinedTextField(value = containerName, onValueChange = { containerName = it }, label = { Text("Container Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                    OutlinedTextField(value = databaseName, onValueChange = { databaseName = it }, label = { Text("Database Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("Username (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Password (optional)") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                } else {
                    OutlinedTextField(value = sqlitePath, onValueChange = { sqlitePath = it }, label = { Text("SQLite File Path") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }

                OutlinedTextField(value = sudoPassword, onValueChange = { sudoPassword = it }, label = { Text("Sudo Password (if needed)") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            Button(onClick = {
                onSubmit(
                    CreateDatabaseBackupRequest(
                        engine = engine,
                        mode = mode,
                        containerName = containerName.takeIf { it.isNotBlank() },
                        databaseName = databaseName.takeIf { it.isNotBlank() },
                        username = username.takeIf { it.isNotBlank() },
                        password = password.takeIf { it.isNotBlank() },
                        sqlitePath = sqlitePath.takeIf { it.isNotBlank() },
                        sudoPassword = sudoPassword.takeIf { it.isNotBlank() },
                        saveSudo = true,
                    ),
                )
            }) { Text("Run Backup") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun DatabaseRestoreDialog(
    backup: DatabaseBackupFile,
    containers: List<DiscoveredDatabaseContainer>,
    onDismiss: () -> Unit,
    onSubmit: (RestoreDatabaseBackupRequest) -> Unit,
) {
    var mode by remember { mutableStateOf("container") }
    var containerName by remember { mutableStateOf(containers.firstOrNull { it.engine == backup.engine }?.name.orEmpty()) }
    var databaseName by remember { mutableStateOf(backup.databaseName) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var dropExisting by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Restore ${backup.filename}") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(selected = mode == "container", onClick = { mode = "container" }, shape = SegmentedButtonDefaults.itemShape(0, 2), label = { Text("Container") })
                    SegmentedButton(selected = mode == "host", onClick = { mode = "host" }, shape = SegmentedButtonDefaults.itemShape(1, 2), label = { Text("Host") })
                }
                if (mode == "container") {
                    OutlinedTextField(value = containerName, onValueChange = { containerName = it }, label = { Text("Target Container") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                OutlinedTextField(value = databaseName, onValueChange = { databaseName = it }, label = { Text("Target Database") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("Username (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Password (optional)") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = dropExisting, onCheckedChange = { dropExisting = it })
                    Text("Drop existing objects before restore", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                onSubmit(
                    RestoreDatabaseBackupRequest(
                        engine = backup.engine,
                        mode = mode,
                        containerName = containerName.takeIf { it.isNotBlank() },
                        databaseName = databaseName.takeIf { it.isNotBlank() },
                        username = username.takeIf { it.isNotBlank() },
                        password = password.takeIf { it.isNotBlank() },
                        backupPath = backup.path,
                        dropExisting = dropExisting,
                    ),
                )
            }) { Text("Restore") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
