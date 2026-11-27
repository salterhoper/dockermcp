package tech.codesky.dockdeploy.ui.servers

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.codesky.dockdeploy.data.model.*
import tech.codesky.dockdeploy.data.network.ApiException
import tech.codesky.dockdeploy.data.repository.DockDeployRepository
import tech.codesky.dockdeploy.ui.components.*
import tech.codesky.dockdeploy.util.Formatters

@Composable
fun ServerFilesTab(
    repo: DockDeployRepository,
    serverId: String,
    initialPath: String,
    canWrite: Boolean,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var currentPath by remember(initialPath) { mutableStateOf(initialPath) }
    var pathInput by remember(initialPath) { mutableStateOf(initialPath) }
    var sudoPassword by remember { mutableStateOf<String?>(null) }
    var showSudoPrompt by remember { mutableStateOf(false) }

    var listing by remember { mutableStateOf<DirectoryListing?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    var editingFile by remember { mutableStateOf<FileContent?>(null) }
    var showMkdir by remember { mutableStateOf(false) }
    var showStorageScan by remember { mutableStateOf(false) }
    var scanResult by remember { mutableStateOf<StorageScanResult?>(null) }
    var scanning by remember { mutableStateOf(false) }

    var actionEntry by remember { mutableStateOf<FileEntry?>(null) }
    var copyMoveMode by remember { mutableStateOf<Pair<FileEntry, String>?>(null) } // entry to "copy"|"move"
    var chmodEntry by remember { mutableStateOf<FileEntry?>(null) }
    var transferEntry by remember { mutableStateOf<FileEntry?>(null) }
    var deleteEntry by remember { mutableStateOf<FileEntry?>(null) }
    var pendingDownloadPath by remember { mutableStateOf<Pair<String, Boolean>?>(null) } // path to isArchive

    fun loadDir(path: String = currentPath, sudo: String? = sudoPassword) {
        scope.launch {
            loading = true
            error = null
            try {
                val res = repo.listFiles(serverId, path, sudo)
                listing = res
                currentPath = res.path
                pathInput = res.path
            } catch (e: ApiException) {
                if (e.requiresSudoPassword) showSudoPrompt = true
                else error = e.message
            } catch (e: Exception) {
                error = e.message ?: "Failed to list directory."
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(serverId, initialPath) {
        loadDir(initialPath, sudoPassword)
    }

    val uploadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            loading = true
            error = null
            try {
                val (fileName, bytes) = withContext(Dispatchers.IO) {
                    val name = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (cursor.moveToFirst() && idx >= 0) cursor.getString(idx) else null
                    } ?: "upload.bin"
                    val data = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw IllegalStateException("Unable to read selected file.")
                    name to data
                }
                val uploaded = repo.uploadFile(serverId, fileName, bytes, currentPath)
                statusMessage = "Uploaded ${uploaded.path} (${Formatters.formatBytes(uploaded.size)})"
                loadDir(currentPath)
            } catch (e: Exception) {
                error = e.message ?: "Upload failed."
                loading = false
            }
        }
    }

    val saveDownloadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri: Uri? ->
        val target = pendingDownloadPath ?: return@rememberLauncherForActivityResult
        pendingDownloadPath = null
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            loading = true
            error = null
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        if (target.second) {
                            repo.downloadArchive(serverId, target.first, out, sudoPassword)
                        } else {
                            repo.downloadFile(serverId, target.first, out, sudoPassword)
                        }
                    } ?: throw IllegalStateException("Cannot open destination file.")
                }
                statusMessage = "Saved ${target.first} to device."
            } catch (e: Exception) {
                error = e.message ?: "Download failed."
            } finally {
                loading = false
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Path bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            IconButton(
                onClick = { listing?.parent?.takeIf { it.isNotBlank() }?.let { loadDir(it) } },
                enabled = !listing?.parent.isNullOrBlank(),
            ) {
                Icon(Icons.Rounded.ArrowUpward, contentDescription = "Parent directory")
            }
            OutlinedTextField(
                value = pathInput,
                onValueChange = { pathInput = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.weight(1f),
            )
            FilledTonalIconButton(onClick = { loadDir(pathInput) }) {
                Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = "Go to path")
            }
        }

        // Action strip
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = { loadDir("~") }) {
                Icon(Icons.Rounded.Home, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Home")
            }
            OutlinedButton(onClick = { loadDir("/") }) {
                Icon(Icons.Rounded.Dns, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("/")
            }
            OutlinedButton(onClick = {
                showStorageScan = true
                scope.launch {
                    scanning = true
                    runCatching { repo.scanStorage(serverId, currentPath, 10, sudoPassword) }
                        .onSuccess { scanResult = it }
                        .onFailure { error = it.message }
                    scanning = false
                }
            }) {
                Icon(Icons.Rounded.PieChart, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Scan Disk")
            }
            if (canWrite) {
                OutlinedButton(onClick = { showMkdir = true }) {
                    Icon(Icons.Rounded.CreateNewFolder, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("New Folder")
                }
                OutlinedButton(onClick = { uploadLauncher.launch(arrayOf("*/*")) }) {
                    Icon(Icons.Rounded.UploadFile, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Upload")
                }
            }
        }

        if (statusMessage != null) {
            AlertBanner(message = statusMessage!!, tone = BadgeTone.SUCCESS, onDismiss = { statusMessage = null })
        }
        if (error != null) {
            AlertBanner(message = error!!, tone = BadgeTone.DANGER, onDismiss = { error = null })
        }

        if (loading) {
            Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            val dir = listing
            if (dir != null) {
                Text(
                    text = "${dir.totalDirs} folders • ${dir.totalFiles} files • ${Formatters.formatBytes(dir.totalBytes)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (dir.entries.isEmpty()) {
                    EmptyStateView(
                        icon = Icons.Rounded.FolderOpen,
                        title = "Empty directory",
                        description = "There are no files in ${dir.path}.",
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        dir.entries.forEach { entry ->
                            DockCard(
                                contentPadding = 12.dp,
                                onClick = {
                                    if (entry.isDir) {
                                        loadDir(entry.path)
                                    } else {
                                        scope.launch {
                                            loading = true
                                            try {
                                                editingFile = repo.getFileContent(serverId, entry.path, sudoPassword)
                                            } catch (e: ApiException) {
                                                if (e.requiresSudoPassword) showSudoPrompt = true
                                                else error = e.message
                                            } finally {
                                                loading = false
                                            }
                                        }
                                    }
                                },
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Icon(
                                        imageVector = when {
                                            entry.isDir -> Icons.Rounded.Folder
                                            entry.isSymlink -> Icons.Rounded.Link
                                            else -> Icons.Rounded.Description
                                        },
                                        contentDescription = null,
                                        tint = if (entry.isDir) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = entry.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = "${entry.permissions.ifBlank { entry.mode }} • ${if (entry.isDir) "dir" else Formatters.formatBytes(entry.size)}",
                                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    IconButton(onClick = { actionEntry = entry }) {
                                        Icon(Icons.Rounded.MoreVert, contentDescription = "File actions")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSudoPrompt) {
        SudoPromptDialog(
            onDismiss = { showSudoPrompt = false },
            onConfirm = { pass, _ ->
                sudoPassword = pass
                showSudoPrompt = false
                loadDir(currentPath, pass)
            },
        )
    }

    if (actionEntry != null) {
        val entry = actionEntry!!
        AlertDialog(
            onDismissRequest = { actionEntry = null },
            title = { Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (!entry.isDir) {
                        TextButton(
                            onClick = {
                                actionEntry = null
                                scope.launch {
                                    runCatching { repo.getFileContent(serverId, entry.path, sudoPassword) }
                                        .onSuccess { editingFile = it }
                                        .onFailure { error = it.message }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("View / Edit Text")
                        }
                        TextButton(
                            onClick = {
                                actionEntry = null
                                pendingDownloadPath = entry.path to false
                                saveDownloadLauncher.launch(entry.name)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Download File")
                        }
                    } else {
                        TextButton(
                            onClick = {
                                actionEntry = null
                                pendingDownloadPath = entry.path to true
                                saveDownloadLauncher.launch("${entry.name}.tar.gz")
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Rounded.FolderZip, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Export .tar.gz Archive")
                        }
                    }
                    if (canWrite) {
                        TextButton(
                            onClick = {
                                actionEntry = null
                                copyMoveMode = entry to "copy"
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Rounded.FileCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Copy")
                        }
                        TextButton(
                            onClick = {
                                actionEntry = null
                                copyMoveMode = entry to "move"
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.DriveFileMove, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Move / Rename")
                        }
                        TextButton(
                            onClick = {
                                actionEntry = null
                                chmodEntry = entry
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Rounded.Security, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Permissions (chmod)")
                        }
                        TextButton(
                            onClick = {
                                actionEntry = null
                                transferEntry = entry
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Rounded.SwapHoriz, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Transfer to Another Server")
                        }
                        TextButton(
                            onClick = {
                                actionEntry = null
                                deleteEntry = entry
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Rounded.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Delete", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { actionEntry = null }) { Text("Close") }
            },
        )
    }

    if (editingFile != null) {
        FileEditorDialog(
            file = editingFile!!,
            canWrite = canWrite,
            onDismiss = { editingFile = null },
            onSave = { updatedText ->
                scope.launch {
                    try {
                        repo.saveFileContent(serverId, editingFile!!.path, updatedText, sudoPassword)
                        statusMessage = "Saved ${editingFile!!.name}"
                        editingFile = null
                        loadDir(currentPath)
                    } catch (e: ApiException) {
                        error = e.message
                    }
                }
            },
        )
    }

    if (showMkdir) {
        var folderName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showMkdir = false },
            title = { Text("New Folder") },
            text = {
                OutlinedTextField(
                    value = folderName,
                    onValueChange = { folderName = it },
                    label = { Text("Folder name or full path") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Button(
                    enabled = folderName.isNotBlank(),
                    onClick = {
                        val target = if (folderName.startsWith("/")) folderName else "${currentPath.trimEnd('/')}/$folderName"
                        showMkdir = false
                        scope.launch {
                            runCatching { repo.createDirectory(serverId, target, sudoPassword) }
                                .onSuccess { loadDir(currentPath) }
                                .onFailure { error = it.message }
                        }
                    },
                ) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showMkdir = false }) { Text("Cancel") } },
        )
    }

    if (copyMoveMode != null) {
        val (entry, op) = copyMoveMode!!
        var target by remember { mutableStateOf(entry.path) }
        AlertDialog(
            onDismissRequest = { copyMoveMode = null },
            title = { Text(if (op == "copy") "Copy Item" else "Move / Rename Item") },
            text = {
                OutlinedTextField(
                    value = target,
                    onValueChange = { target = it },
                    label = { Text("Destination path") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Button(
                    enabled = target.isNotBlank(),
                    onClick = {
                        copyMoveMode = null
                        scope.launch {
                            runCatching {
                                if (op == "copy") repo.copyFile(serverId, entry.path, target, sudoPassword)
                                else repo.moveFile(serverId, entry.path, target, sudoPassword)
                            }.onSuccess { loadDir(currentPath) }.onFailure { error = it.message }
                        }
                    },
                ) { Text("Confirm") }
            },
            dismissButton = { TextButton(onClick = { copyMoveMode = null }) { Text("Cancel") } },
        )
    }

    if (chmodEntry != null) {
        val entry = chmodEntry!!
        var mode by remember { mutableStateOf("0644") }
        AlertDialog(
            onDismissRequest = { chmodEntry = null },
            title = { Text("Change Permissions") },
            text = {
                OutlinedTextField(
                    value = mode,
                    onValueChange = { mode = it },
                    label = { Text("Octal mode (e.g. 0755)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Button(
                    enabled = mode.isNotBlank(),
                    onClick = {
                        chmodEntry = null
                        scope.launch {
                            runCatching { repo.chmodFile(serverId, entry.path, mode.trim(), sudoPassword) }
                                .onSuccess { loadDir(currentPath) }
                                .onFailure { error = it.message }
                        }
                    },
                ) { Text("Apply") }
            },
            dismissButton = { TextButton(onClick = { chmodEntry = null }) { Text("Cancel") } },
        )
    }

    if (transferEntry != null) {
        val entry = transferEntry!!
        var servers by remember { mutableStateOf<List<Server>>(emptyList()) }
        var targetServerId by remember { mutableStateOf("") }
        var targetPath by remember { mutableStateOf(entry.path) }
        var transferring by remember { mutableStateOf(false) }

        LaunchedEffect(entry) {
            servers = runCatching { repo.listServers().filter { it.id != serverId } }.getOrDefault(emptyList())
            targetServerId = servers.firstOrNull()?.id.orEmpty()
        }

        AlertDialog(
            onDismissRequest = { transferEntry = null },
            title = { Text("Cross-Server Transfer") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Source: ${entry.path}", style = MaterialTheme.typography.bodySmall)
                    if (servers.isEmpty()) {
                        Text("No other servers available.", style = MaterialTheme.typography.bodySmall)
                    } else {
                        servers.forEach { sv ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                RadioButton(selected = targetServerId == sv.id, onClick = { targetServerId = sv.id })
                                Text(sv.name, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        OutlinedTextField(
                            value = targetPath,
                            onValueChange = { targetPath = it },
                            label = { Text("Target Path") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = !transferring && targetServerId.isNotBlank() && targetPath.isNotBlank(),
                    onClick = {
                        scope.launch {
                            transferring = true
                            runCatching { repo.transferFile(serverId, entry.path, targetServerId, targetPath) }
                                .onSuccess { res ->
                                    statusMessage = "Transferred ${Formatters.formatBytes(res.bytesCopied)} in ${res.durationMs}ms"
                                    transferEntry = null
                                }
                                .onFailure { error = it.message }
                            transferring = false
                        }
                    },
                ) { Text(if (transferring) "Transferring..." else "Transfer") }
            },
            dismissButton = { TextButton(onClick = { transferEntry = null }) { Text("Cancel") } },
        )
    }

    if (deleteEntry != null) {
        val entry = deleteEntry!!
        ConfirmActionDialog(
            title = "Delete ${entry.name}?",
            message = "This will permanently delete ${entry.path} from the remote server.",
            confirmLabel = "Delete",
            destructive = true,
            onDismiss = { deleteEntry = null },
            onConfirm = {
                deleteEntry = null
                scope.launch {
                    runCatching { repo.deleteFile(serverId, entry.path, sudoPassword) }
                        .onSuccess { loadDir(currentPath) }
                        .onFailure { error = it.message }
                }
            },
        )
    }

    if (showStorageScan) {
        AlertDialog(
            onDismissRequest = { showStorageScan = false },
            title = { Text("Storage Scan • $currentPath") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (scanning) {
                        CircularProgressIndicator()
                    } else if (scanResult != null) {
                        val dirs = scanResult!!.topDirectories ?: scanResult!!.topLargeDirs ?: emptyList()
                        val files = scanResult!!.largestFiles ?: scanResult!!.topLargeFiles ?: emptyList()
                        Text("Largest Directories", style = MaterialTheme.typography.titleSmall)
                        dirs.take(10).forEach { item ->
                            KeyValueRow(
                                item.path.ifBlank { item.name.orEmpty() },
                                item.humanSize ?: item.sizeHuman ?: Formatters.formatBytes(item.sizeBytes ?: item.size ?: 0),
                                mono = true,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("Largest Files", style = MaterialTheme.typography.titleSmall)
                        files.take(10).forEach { item ->
                            KeyValueRow(
                                item.path.ifBlank { item.name.orEmpty() },
                                item.humanSize ?: item.sizeHuman ?: Formatters.formatBytes(item.sizeBytes ?: item.size ?: 0),
                                mono = true,
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showStorageScan = false }) { Text("Close") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileEditorDialog(
    file: FileContent,
    canWrite: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var content by remember(file.path) { mutableStateOf(file.content) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(file.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                file.path,
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Rounded.Close, contentDescription = "Close editor")
                        }
                    },
                    actions = {
                        if (canWrite && !file.isBinary) {
                            TextButton(onClick = { onSave(content) }) {
                                Icon(Icons.Rounded.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Save")
                            }
                        }
                    },
                )
            },
        ) { padding ->
            if (file.isBinary) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Binary file (${Formatters.formatBytes(file.size)}). Use Download to inspect locally.")
                }
            } else {
                OutlinedTextField(
                    value = content,
                    onValueChange = { if (canWrite) content = it },
                    readOnly = !canWrite,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(12.dp),
                )
            }
        }
    }
}
