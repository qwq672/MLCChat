package ai.mlc.mlcchat

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController


@ExperimentalMaterial3Api
@Composable
fun StartView(
    navController: NavController,
    appViewModel: AppViewModel
) {
    val localFocusManager = LocalFocusManager.current
    var showCustomUrlDialog by rememberSaveable { mutableStateOf(false) }
    var customUrl by rememberSaveable { mutableStateOf("https://hf-mirror.com/mlc-ai/") }

    // SAF tree picker
    val safLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            // persist permission so we can read recursively
            val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            appViewModel.getApplication<android.app.Application>().contentResolver.takePersistableUriPermission(uri, flags)
            appViewModel.importFromTreeUri(uri)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "MLCChat", color = MaterialTheme.colorScheme.onPrimary) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primary),
                actions = {
                    IconButton(onClick = { appViewModel.scanLocalModels() }) {
                        Icon(
                            imageVector = Icons.Outlined.Search,
                            contentDescription = "scan local models",
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                    IconButton(onClick = { safLauncher.launch(null) }) {
                        Icon(
                            imageVector = Icons.Outlined.Folder,
                            contentDescription = "import model directory via SAF",
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                    IconButton(onClick = { showCustomUrlDialog = true }) {
                        Icon(
                            imageVector = Icons.Outlined.Add,
                            contentDescription = "add custom HF-Mirror URL",
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                    IconButton(onClick = { navController.navigate("benchmark") }) {
                        Icon(
                            imageVector = Icons.Outlined.Timeline,
                            contentDescription = "benchmark",
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                    IconButton(onClick = { navController.navigate("settings") }) {
                        Icon(
                            imageVector = Icons.Outlined.Settings,
                            contentDescription = "settings",
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            )
        },
        modifier = Modifier.pointerInput(Unit) {
            detectTapGestures(onTap = {
                localFocusManager.clearFocus()
            })
        }
    )
    { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 10.dp)
        ) {
            // Info banner
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 8.dp)
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text(
                        text = "Models available for download",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Text(
                        text = "Source: hf-mirror.com (China mirror of HuggingFace)\nTap ${'\u25BC'} to start download. After download tap chat icon to begin.\nLocal imports supported via scan or SAF picker.",
                        fontSize = 11.sp,
                        lineHeight = 14.sp
                    )
                    Text(
                        text = "Note: APK only contains modelLib 'gemma2_q4f16_1'. Other model libs (qwen2_5, llama, phi, smollm, mistral) will download but chat may fail unless .so is recompiled with their model libs.",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        lineHeight = 12.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            Text(text = "Model List (${appViewModel.modelList.size})", modifier = Modifier.padding(top = 4.dp))
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(
                    items = appViewModel.modelList,
                    key = { modelState -> modelState.id }
                ) { modelState ->
                    ModelView(
                        navController = navController,
                        modelState = modelState,
                        appViewModel = appViewModel
                    )
                }
            }
        }
        if (appViewModel.isShowingAlert()) {
            AppAlertDialog(
                onDismissRequest = { appViewModel.dismissAlert() },
                onConfirmation = { appViewModel.copyError() },
                error = appViewModel.errorMessage()
            )
        }
        if (showCustomUrlDialog) {
            AlertDialog(
                onDismissRequest = { showCustomUrlDialog = false },
                title = { Text("Add custom model URL") },
                text = {
                    Column {
                        Text(
                            "Enter a HF-Mirror model repo URL.",
                            fontSize = 12.sp
                        )
                        Text(
                            "Example: https://hf-mirror.com/mlc-ai/Qwen2.5-0.5B-Instruct-q4f16_1-MLC",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        OutlinedTextField(
                            value = customUrl,
                            onValueChange = { customUrl = it },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        appViewModel.addCustomModelUrl(customUrl)
                        showCustomUrlDialog = false
                    }) { Text("Add") }
                },
                dismissButton = {
                    TextButton(onClick = { showCustomUrlDialog = false }) { Text("Cancel") }
                }
            )
        }
    }
}

@ExperimentalMaterial3Api
@Composable
fun AppAlertDialog(
    onDismissRequest: () -> Unit,
    onConfirmation: () -> Unit,
    error: String,
) {
    AlertDialog(
        title = { Text(text = "Notice") },
        text = {
            SelectionContainer {
                Text(text = error, fontSize = 12.sp)
            }
        },
        onDismissRequest = { onDismissRequest() },
        confirmButton = {
            TextButton(onClick = { onConfirmation() }) { Text("Copy") }
        },
        dismissButton = {
            TextButton(onClick = { onDismissRequest() }) { Text("Dismiss") }
        }
    )
}

@Composable
fun ModelView(
    navController: NavController,
    modelState: AppViewModel.ModelState,
    appViewModel: AppViewModel
) {
    var isDeletingModel by rememberSaveable { mutableStateOf(false) }
    val tested = appViewModel.isModelLibTested(modelState.modelConfig.modelLib)
    Column(
        verticalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .padding(vertical = 4.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()
        ) {
            Column(modifier = Modifier.weight(8f)) {
                Text(
                    text = modelState.modelConfig.modelId,
                    textAlign = TextAlign.Left,
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "lib: " + modelState.modelConfig.modelLib.take(40) + if (modelState.modelConfig.modelLib.length > 40) "..." else "",
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    color = if (tested) Color(0xFF1B5E20) else Color(0xFFB71C1C)
                )
                Text(
                    text = "ctx=${modelState.modelConfig.contextWindowSize}, prefill=${modelState.modelConfig.prefillChunkSize}" +
                        (modelState.modelConfig.estimatedVramBytes?.let { ", vram~${it / 1024 / 1024}MB" } ?: ""),
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                if (!tested) {
                    Text(
                        text = "[untested in current APK]",
                        fontSize = 9.sp,
                        color = Color(0xFFB71C1C),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Divider(
                modifier = Modifier
                    .height(20.dp)
                    .width(1.dp)
            )
            if (modelState.modelInitState.value == ModelInitState.Paused) {
                IconButton(
                    onClick = { modelState.handleStart() }, modifier = Modifier
                        .aspectRatio(1f)
                        .weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Download,
                        contentDescription = "start downloading",
                    )
                }

            } else if (modelState.modelInitState.value == ModelInitState.Downloading) {
                IconButton(
                    onClick = { modelState.handlePause() }, modifier = Modifier
                        .aspectRatio(1f)
                        .weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Pause,
                        contentDescription = "pause downloading",
                    )
                }
            } else if (modelState.modelInitState.value == ModelInitState.Finished) {
                IconButton(
                    onClick = {
                        modelState.startChat()
                        navController.navigate("chat")
                    },
                    enabled = appViewModel.chatState.interruptable(),
                    modifier = Modifier
                        .aspectRatio(1f)
                        .weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Chat,
                        contentDescription = "start chatting",
                    )
                }
            } else {
                IconButton(
                    enabled = false, onClick = {}, modifier = Modifier
                        .aspectRatio(1f)
                        .weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Schedule,
                        contentDescription = "pending",
                    )
                }
            }
            if (modelState.modelInitState.value == ModelInitState.Downloading ||
                modelState.modelInitState.value == ModelInitState.Paused ||
                modelState.modelInitState.value == ModelInitState.Finished
            ) {
                IconButton(
                    onClick = { isDeletingModel = true },
                    modifier = Modifier
                        .aspectRatio(1f)
                        .weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = "delete model",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
        LinearProgressIndicator(
            progress = if (modelState.total.value > 0) modelState.progress.value.toFloat() / modelState.total.value else 0f,
            modifier = Modifier.fillMaxWidth()
        )
        if (isDeletingModel) {
            Row(
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight()
            ) {
                TextButton(onClick = { isDeletingModel = false }) {
                    Text(text = "cancel")
                }
                TextButton(onClick = {
                    isDeletingModel = false
                    modelState.handleClear()
                }) {
                    Text(text = "clear data", color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = {
                    isDeletingModel = false
                    modelState.handleDelete()
                }) {
                    Text(text = "delete model", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
