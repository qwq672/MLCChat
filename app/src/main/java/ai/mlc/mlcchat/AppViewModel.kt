package ai.mlc.mlcchat

import ai.mlc.mlcllm.MLCEngine
import ai.mlc.mlcllm.OpenAIProtocol
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.toMutableStateList
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.nio.channels.Channels
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import ai.mlc.mlcllm.OpenAIProtocol.ChatCompletionMessage
import kotlinx.coroutines.*
import java.io.FileInputStream
import java.io.OutputStream

class AppViewModel(application: Application) : AndroidViewModel(application) {
    val modelList = emptyList<ModelState>().toMutableStateList()
    val chatState = ChatState()
    val modelSampleList = emptyList<ModelRecord>().toMutableStateList()
    private var showAlert = mutableStateOf(false)
    private var alertMessage = mutableStateOf("")
    private var appConfig = AppConfig(
        emptyList<String>().toMutableList(),
        emptyList<ModelRecord>().toMutableList()
    )
    private val application = getApplication<Application>()
    private val appDirFile = application.getExternalFilesDir("")
    private val gson = Gson()
    private val modelIdSet = emptySet<String>().toMutableSet()

    // ---- New: chat settings (persisted) ----
    val chatSettings = ChatSettings().also { it.load(application, appDirFile) }

    // ---- New: benchmark state ----
    val benchmarkState = BenchmarkState()

    // ---- New: tested model libs (only ones actually compiled into current .so) ----
    // The current libtvm4j_runtime_packed.so was compiled with only gemma2_q4f16_1.
    // Other modelLib entries are downloadable but chat will likely fail at engine.reload().
    private val testedModelLibs = setOf(
        "gemma2_q4f16_1_5cc7dbd3ae3d1040984d9720b2d7b7d4"
    )

    fun isModelLibTested(lib: String): Boolean = testedModelLibs.contains(lib)

    companion object {
        const val AppConfigFilename = "mlc-app-config.json"
        const val ModelConfigFilename = "mlc-chat-config.json"
        const val ParamsConfigFilename = "ndarray-cache.json"
        const val ModelUrlSuffix = "resolve/main/"
        const val SettingsFilename = "chat-settings.json"
        const val BenchHistoryFilename = "bench-history.json"
    }

    init {
        loadAppConfig()
    }

    fun isShowingAlert(): Boolean {
        return showAlert.value
    }

    fun errorMessage(): String {
        return alertMessage.value
    }

    fun dismissAlert() {
        require(showAlert.value)
        showAlert.value = false
    }

    fun copyError() {
        require(showAlert.value)
        val clipboard =
            application.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("MLCChat", errorMessage()))
    }

    private fun issueAlert(error: String) {
        showAlert.value = true
        alertMessage.value = error
    }

    fun requestDeleteModel(modelId: String) {
        deleteModel(modelId)
        issueAlert("Model: $modelId has been deleted")
    }


    private fun loadAppConfig() {
        val appConfigFile = File(appDirFile, AppConfigFilename)
        val jsonString: String = if (!appConfigFile.exists()) {
            application.assets.open(AppConfigFilename).bufferedReader().use { it.readText() }
        } else {
            appConfigFile.readText()
        }
        appConfig = gson.fromJson(jsonString, AppConfig::class.java)
        appConfig.modelLibs = emptyList<String>().toMutableList()
        modelList.clear()
        modelIdSet.clear()
        modelSampleList.clear()
        for (modelRecord in appConfig.modelList) {
            appConfig.modelLibs.add(modelRecord.modelLib)
            val modelDirFile = File(appDirFile, modelRecord.modelId)
            val modelConfigFile = File(modelDirFile, ModelConfigFilename)
            if (modelConfigFile.exists()) {
                val modelConfigString = modelConfigFile.readText()
                val modelConfig = gson.fromJson(modelConfigString, ModelConfig::class.java)
                modelConfig.modelId = modelRecord.modelId
                modelConfig.modelLib = modelRecord.modelLib
                modelConfig.estimatedVramBytes = modelRecord.estimatedVramBytes
                addModelConfig(modelConfig, modelRecord.modelUrl, true)
            } else {
                downloadModelConfig(
                    if (modelRecord.modelUrl.endsWith("/")) modelRecord.modelUrl else "${modelRecord.modelUrl}/",
                    modelRecord,
                    true
                )
            }
        }
    }

    private fun updateAppConfig(action: () -> Unit) {
        action()
        val jsonString = gson.toJson(appConfig)
        val appConfigFile = File(appDirFile, AppConfigFilename)
        appConfigFile.writeText(jsonString)
    }

    private fun addModelConfig(modelConfig: ModelConfig, modelUrl: String, isBuiltin: Boolean) {
        require(!modelIdSet.contains(modelConfig.modelId))
        modelIdSet.add(modelConfig.modelId)
        modelList.add(
            ModelState(
                modelConfig,
                modelUrl + if (modelUrl.endsWith("/")) "" else "/",
                File(appDirFile, modelConfig.modelId)
            )
        )
        if (!isBuiltin) {
            updateAppConfig {
                appConfig.modelList.add(
                    ModelRecord(
                        modelUrl,
                        modelConfig.modelId,
                        modelConfig.estimatedVramBytes,
                        modelConfig.modelLib
                    )
                )
            }
        }
    }

    private fun deleteModel(modelId: String) {
        val modelDirFile = File(appDirFile, modelId)
        modelDirFile.deleteRecursively()
        require(!modelDirFile.exists())
        modelIdSet.remove(modelId)
        modelList.removeIf { modelState -> modelState.modelConfig.modelId == modelId }
        updateAppConfig {
            appConfig.modelList.removeIf { modelRecord -> modelRecord.modelId == modelId }
        }
    }

    private fun isModelConfigAllowed(modelConfig: ModelConfig): Boolean {
        if (appConfig.modelLibs.contains(modelConfig.modelLib)) return true
        viewModelScope.launch {
            issueAlert("Model lib ${modelConfig.modelLib} is not supported.")
        }
        return false
    }

    // ============================================================
    // New: scan local appDirFile for model directories (those that
    // contain a mlc-chat-config.json but are not yet registered).
    // Useful for users who manually copy model files into
    // /sdcard/Android/data/ai.mlc.mlcchat/files/<model-id>/
    // ============================================================
    fun scanLocalModels() {
        val appDir = appDirFile ?: return
        if (!appDir.exists()) {
            issueAlert("App dir does not exist: ${appDir.absolutePath}")
            return
        }
        val added = mutableListOf<String>()
        val subDirs = appDir.listFiles { f -> f.isDirectory } ?: emptyArray()
        for (sub in subDirs) {
            val modelConfigFile = File(sub, ModelConfigFilename)
            if (!modelConfigFile.exists()) continue
            if (modelIdSet.contains(sub.name)) continue
            try {
                val modelConfigString = modelConfigFile.readText()
                val modelConfig = gson.fromJson(modelConfigString, ModelConfig::class.java)
                modelConfig.modelId = sub.name
                if (modelConfig.modelLib.isEmpty()) {
                    // try to infer from id - keep empty for untested libs
                    modelConfig.modelLib = ""
                }
                // modelUrl: leave empty (it's a local import)
                addModelConfig(modelConfig, "local:///${sub.name}/", false)
                added.add(sub.name)
            } catch (e: Exception) {
                // ignore malformed dir
            }
        }
        if (added.isEmpty()) {
            issueAlert("No new local models found in ${appDir.absolutePath}\n\nPlace a model directory containing '$ModelConfigFilename' under that path first.")
        } else {
            issueAlert("Added ${added.size} local model(s):\n${added.joinToString("\n")}")
        }
    }

    // ============================================================
    // New: SAF (Storage Access Framework) directory import.
    // Recursively copy all files under the chosen tree URI into
    // appDirFile/<modelId>/ where modelId is derived from the tree
    // display name (last path segment, sanitized).
    // ============================================================
    fun importFromTreeUri(treeUri: Uri) {
        thread(start = true) {
            try {
                val resolver = application.contentResolver
                var treeName: String? = null
                val cursor = resolver.query(
                    treeUri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                    null, null, null
                )
                cursor?.use {
                    if (it.moveToFirst()) treeName = it.getString(0)
                }
                val rawName = treeName ?: "imported-${UUID.randomUUID()}"
                // sanitize: keep alnum, dash, underscore, dot
                val modelId = rawName.replace(Regex("[^A-Za-z0-9._-]"), "_")
                    .trim('_')
                    .ifEmpty { "imported-${UUID.randomUUID()}" }

                val destDir = File(appDirFile, modelId)
                if (destDir.exists()) {
                    viewModelScope.launch {
                        issueAlert("Destination dir already exists: $modelId\nWill not overwrite. Please delete it first or rename.")
                    }
                    return@thread
                }
                destDir.mkdirs()

                val tree = DocumentsContract.buildDocumentUriUsingTree(
                    treeUri,
                    DocumentsContract.getTreeDocumentId(treeUri)
                )
                copyDocumentTree(resolver, tree, destDir)

                viewModelScope.launch {
                    // try to load model config
                    val modelConfigFile = File(destDir, ModelConfigFilename)
                    if (!modelConfigFile.exists()) {
                        issueAlert("Imported $modelId but no $ModelConfigFilename found in it. The directory needs to be a valid MLC model dir.")
                        return@launch
                    }
                    try {
                        val modelConfigString = modelConfigFile.readText()
                        val modelConfig = gson.fromJson(modelConfigString, ModelConfig::class.java)
                        modelConfig.modelId = modelId
                        // modelLib stays as defined in the json (may be untested)
                        if (modelIdSet.contains(modelId)) {
                            issueAlert("$modelId already exists.")
                            return@launch
                        }
                        addModelConfig(modelConfig, "local:///$modelId/", false)
                        issueAlert("Imported model: $modelId\nModelLib: ${modelConfig.modelLib}\nTested: ${if (isModelLibTested(modelConfig.modelLib)) "YES" else "NO (chat may fail)"}")
                    } catch (e: Exception) {
                        issueAlert("Failed to parse model config: ${e.localizedMessage}")
                    }
                }
            } catch (e: Exception) {
                viewModelScope.launch {
                    issueAlert("SAF import failed: ${e.localizedMessage}")
                }
            }
        }
    }

    private fun copyDocumentTree(resolver: android.content.ContentResolver, docUri: Uri, destDir: File) {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            docUri,
            DocumentsContract.getDocumentId(docUri)
        )
        val cursor = resolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            ),
            null, null, null
        )
        cursor?.use { c ->
            while (c.moveToNext()) {
                val docId = c.getString(0)
                val name = c.getString(1)
                val mime = c.getString(2)
                val childUri = DocumentsContract.buildDocumentUriUsingTree(docUri, docId)
                if (DocumentsContract.Document.MIME_TYPE_DIR == mime) {
                    val subDir = File(destDir, name)
                    subDir.mkdirs()
                    copyDocumentTree(resolver, childUri, subDir)
                } else {
                    val destFile = File(destDir, name)
                    destFile.parentFile?.mkdirs()
                    resolver.openInputStream(childUri).use { input ->
                        FileOutputStream(destFile).use { output ->
                            input?.copyTo(output)
                        }
                    }
                }
            }
        }
    }

    // ============================================================
    // New: add a custom HF-Mirror model URL.
    // User inputs e.g. https://hf-mirror.com/mlc-ai/SomeModel-MLC
    // ============================================================
    fun addCustomModelUrl(url: String) {
        val trimmed = url.trim().trimEnd('/')
        if (trimmed.isEmpty()) {
            issueAlert("URL is empty")
            return
        }
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            issueAlert("URL must start with http:// or https://\nFor HF-Mirror, use https://hf-mirror.com/mlc-ai/<model-name>")
            return
        }
        // derive model id from URL last path segment
        val segs = trimmed.split("/").filter { it.isNotEmpty() }
        val modelId = segs.lastOrNull() ?: "custom-${UUID.randomUUID()}"
        if (modelIdSet.contains(modelId)) {
            issueAlert("Model id '$modelId' already exists.")
            return
        }
        val record = ModelRecord(
            modelUrl = "$trimmed/",
            modelId = modelId,
            estimatedVramBytes = null,
            modelLib = "custom_${UUID.randomUUID()}"
        )
        // we don't pre-add; download config first
        thread(start = true) {
            try {
                val configUrl = URL("$trimmed/${ModelUrlSuffix}${ModelConfigFilename}")
                val tempId = UUID.randomUUID().toString()
                val tempFile = File(
                    application.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                    tempId
                )
                configUrl.openStream().use { inp ->
                    Channels.newChannel(inp).use { src ->
                        FileOutputStream(tempFile).use { fos ->
                            fos.channel.transferFrom(src, 0, Long.MAX_VALUE)
                        }
                    }
                }
                require(tempFile.exists())
                viewModelScope.launch {
                    try {
                        val modelConfigString = tempFile.readText()
                        val modelConfig = gson.fromJson(modelConfigString, ModelConfig::class.java)
                        modelConfig.modelId = modelId
                        modelConfig.modelLib = record.modelLib
                        modelConfig.estimatedVramBytes = record.estimatedVramBytes
                        if (modelIdSet.contains(modelConfig.modelId)) {
                            tempFile.delete()
                            issueAlert("${modelConfig.modelId} already used")
                            return@launch
                        }
                        // Note: do NOT call isModelConfigAllowed - the lib is custom
                        // We bypass the check by also adding to appConfig.modelLibs via updateAppConfig
                        val modelDirFile = File(appDirFile, modelConfig.modelId)
                        val modelConfigFile = File(modelDirFile, ModelConfigFilename)
                        tempFile.copyTo(modelConfigFile, overwrite = true)
                        tempFile.delete()
                        require(modelConfigFile.exists())
                        // mark lib as allowed
                        updateAppConfig {
                            appConfig.modelLibs.add(modelConfig.modelLib)
                            appConfig.modelList.add(record.copy(modelUrl = "$trimmed/"))
                        }
                        addModelConfig(modelConfig, "$trimmed/", false)
                        issueAlert("Added custom model: $modelId\nModelLib: ${modelConfig.modelLib}\nTested: ${if (isModelLibTested(modelConfig.modelLib)) "YES" else "NO (chat may fail)"}\nSource: $trimmed")
                    } catch (e: Exception) {
                        viewModelScope.launch {
                            issueAlert("Add custom model failed: ${e.localizedMessage}\n\nCheck the URL points to a valid MLC repo on HF-Mirror (e.g. https://hf-mirror.com/mlc-ai/<model-name>-MLC)")
                        }
                    }
                }
            } catch (e: Exception) {
                viewModelScope.launch {
                    issueAlert("Download model config failed: ${e.localizedMessage}\n\nURL was: $trimmed/${ModelUrlSuffix}${ModelConfigFilename}")
                }
            }
        }
    }

    private fun downloadModelConfig(
        modelUrl: String,
        modelRecord: ModelRecord,
        isBuiltin: Boolean
    ) {
        thread(start = true) {
            try {
                val url = URL("${modelUrl}${ModelUrlSuffix}${ModelConfigFilename}")
                val tempId = UUID.randomUUID().toString()
                val tempFile = File(
                    application.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                    tempId
                )
                url.openStream().use {
                    Channels.newChannel(it).use { src ->
                        FileOutputStream(tempFile).use { fileOutputStream ->
                            fileOutputStream.channel.transferFrom(src, 0, Long.MAX_VALUE)
                        }
                    }
                }
                require(tempFile.exists())
                viewModelScope.launch {
                    try {
                        val modelConfigString = tempFile.readText()
                        val modelConfig = gson.fromJson(modelConfigString, ModelConfig::class.java)
                        modelConfig.modelId = modelRecord.modelId
                        modelConfig.modelLib = modelRecord.modelLib
                        modelConfig.estimatedVramBytes = modelRecord.estimatedVramBytes
                        if (modelIdSet.contains(modelConfig.modelId)) {
                            tempFile.delete()
                            issueAlert("${modelConfig.modelId} has been used, please consider another local ID")
                            return@launch
                        }
                        if (!isModelConfigAllowed(modelConfig)) {
                            tempFile.delete()
                            return@launch
                        }
                        val modelDirFile = File(appDirFile, modelConfig.modelId)
                        val modelConfigFile = File(modelDirFile, ModelConfigFilename)
                        tempFile.copyTo(modelConfigFile, overwrite = true)
                        tempFile.delete()
                        require(modelConfigFile.exists())
                        addModelConfig(modelConfig, modelUrl, isBuiltin)
                    } catch (e: Exception) {
                        viewModelScope.launch {
                            issueAlert("Add model failed: ${e.localizedMessage}")
                        }
                    }
                }
            } catch (e: Exception) {
                viewModelScope.launch {
                    issueAlert("Download model config failed: ${e.localizedMessage}")
                }
            }

        }
    }

    inner class ModelState(
        val modelConfig: ModelConfig,
        private val modelUrl: String,
        private val modelDirFile: File
    ) {
        var modelInitState = mutableStateOf(ModelInitState.Initializing)
        private var paramsConfig = ParamsConfig(emptyList())
        val progress = mutableStateOf(0)
        val total = mutableStateOf(1)
        val id: UUID = UUID.randomUUID()
        private val remainingTasks = emptySet<DownloadTask>().toMutableSet()
        private val downloadingTasks = emptySet<DownloadTask>().toMutableSet()
        private val maxDownloadTasks = 3
        private val gson = Gson()


        init {
            switchToInitializing()
        }

        private fun switchToInitializing() {
            val paramsConfigFile = File(modelDirFile, ParamsConfigFilename)
            if (paramsConfigFile.exists()) {
                loadParamsConfig()
                switchToIndexing()
            } else {
                // For local imports with no ndarray-cache.json, go straight to Finished.
                if (modelUrl.startsWith("local:///")) {
                    // skip download - just go to Finished
                    if (modelConfigFile().exists()) {
                        switchToFinished()
                    } else {
                        // no params config, but local model - assume Finished
                        switchToFinished()
                    }
                } else {
                    downloadParamsConfig()
                }
            }
        }

        private fun modelConfigFile() = File(modelDirFile, ModelConfigFilename)

        private fun loadParamsConfig() {
            val paramsConfigFile = File(modelDirFile, ParamsConfigFilename)
            require(paramsConfigFile.exists())
            val jsonString = paramsConfigFile.readText()
            paramsConfig = gson.fromJson(jsonString, ParamsConfig::class.java)
        }

        private fun downloadParamsConfig() {
            thread(start = true) {
                val url = URL("${modelUrl}${ModelUrlSuffix}${ParamsConfigFilename}")
                val tempId = UUID.randomUUID().toString()
                val tempFile = File(modelDirFile, tempId)
                url.openStream().use {
                    Channels.newChannel(it).use { src ->
                        FileOutputStream(tempFile).use { fileOutputStream ->
                            fileOutputStream.channel.transferFrom(src, 0, Long.MAX_VALUE)
                        }
                    }
                }
                require(tempFile.exists())
                val paramsConfigFile = File(modelDirFile, ParamsConfigFilename)
                tempFile.renameTo(paramsConfigFile)
                require(paramsConfigFile.exists())
                viewModelScope.launch {
                    loadParamsConfig()
                    switchToIndexing()
                }
            }
        }

        fun handleStart() {
            switchToDownloading()
        }

        fun handlePause() {
            switchToPausing()
        }

        fun handleClear() {
            require(
                modelInitState.value == ModelInitState.Downloading ||
                        modelInitState.value == ModelInitState.Paused ||
                        modelInitState.value == ModelInitState.Finished
            )
            switchToClearing()
        }

        private fun switchToClearing() {
            if (modelInitState.value == ModelInitState.Paused) {
                modelInitState.value = ModelInitState.Clearing
                clear()
            } else if (modelInitState.value == ModelInitState.Finished) {
                modelInitState.value = ModelInitState.Clearing
                if (chatState.modelName.value == modelConfig.modelId) {
                    chatState.requestTerminateChat { clear() }
                } else {
                    clear()
                }
            } else {
                modelInitState.value = ModelInitState.Clearing
            }
        }

        fun handleDelete() {
            require(
                modelInitState.value == ModelInitState.Downloading ||
                        modelInitState.value == ModelInitState.Paused ||
                        modelInitState.value == ModelInitState.Finished
            )
            switchToDeleting()
        }

        private fun switchToDeleting() {
            if (modelInitState.value == ModelInitState.Paused) {
                modelInitState.value = ModelInitState.Deleting
                delete()
            } else if (modelInitState.value == ModelInitState.Finished) {
                modelInitState.value = ModelInitState.Deleting
                if (chatState.modelName.value == modelConfig.modelId) {
                    chatState.requestTerminateChat { delete() }
                } else {
                    delete()
                }
            } else {
                modelInitState.value = ModelInitState.Deleting
            }
        }

        private fun switchToIndexing() {
            modelInitState.value = ModelInitState.Indexing
            progress.value = 0
            total.value = modelConfig.tokenizerFiles.size + paramsConfig.paramsRecords.size
            for (tokenizerFilename in modelConfig.tokenizerFiles) {
                val file = File(modelDirFile, tokenizerFilename)
                if (file.exists()) {
                    ++progress.value
                } else {
                    if (!modelUrl.startsWith("local:///")) {
                        remainingTasks.add(
                            DownloadTask(
                                URL("${modelUrl}${ModelUrlSuffix}${tokenizerFilename}"),
                                file
                            )
                        )
                    }
                }
            }
            for (paramsRecord in paramsConfig.paramsRecords) {
                val file = File(modelDirFile, paramsRecord.dataPath)
                if (file.exists()) {
                    ++progress.value
                } else {
                    if (!modelUrl.startsWith("local:///")) {
                        remainingTasks.add(
                            DownloadTask(
                                URL("${modelUrl}${ModelUrlSuffix}${paramsRecord.dataPath}"),
                                file
                            )
                        )
                    }
                }
            }
            if (progress.value < total.value) {
                switchToPaused()
            } else {
                switchToFinished()
            }
        }

        private fun switchToDownloading() {
            modelInitState.value = ModelInitState.Downloading
            for (downloadTask in remainingTasks) {
                if (downloadingTasks.size < maxDownloadTasks) {
                    handleNewDownload(downloadTask)
                } else {
                    return
                }
            }
        }

        private fun handleNewDownload(downloadTask: DownloadTask) {
            require(modelInitState.value == ModelInitState.Downloading)
            require(!downloadingTasks.contains(downloadTask))
            downloadingTasks.add(downloadTask)
            thread(start = true) {
                val tempId = UUID.randomUUID().toString()
                val tempFile = File(modelDirFile, tempId)
                downloadTask.url.openStream().use {
                    Channels.newChannel(it).use { src ->
                        FileOutputStream(tempFile).use { fileOutputStream ->
                            fileOutputStream.channel.transferFrom(src, 0, Long.MAX_VALUE)
                        }
                    }
                }
                require(tempFile.exists())
                tempFile.renameTo(downloadTask.file)
                require(downloadTask.file.exists())
                viewModelScope.launch {
                    handleFinishDownload(downloadTask)
                }
            }
        }

        private fun handleNextDownload() {
            require(modelInitState.value == ModelInitState.Downloading)
            for (downloadTask in remainingTasks) {
                if (!downloadingTasks.contains(downloadTask)) {
                    handleNewDownload(downloadTask)
                    break
                }
            }
        }

        private fun handleFinishDownload(downloadTask: DownloadTask) {
            remainingTasks.remove(downloadTask)
            downloadingTasks.remove(downloadTask)
            ++progress.value
            require(
                modelInitState.value == ModelInitState.Downloading ||
                        modelInitState.value == ModelInitState.Pausing ||
                        modelInitState.value == ModelInitState.Clearing ||
                        modelInitState.value == ModelInitState.Deleting
            )
            if (modelInitState.value == ModelInitState.Downloading) {
                if (remainingTasks.isEmpty()) {
                    if (downloadingTasks.isEmpty()) {
                        switchToFinished()
                    }
                } else {
                    handleNextDownload()
                }
            } else if (modelInitState.value == ModelInitState.Pausing) {
                if (downloadingTasks.isEmpty()) {
                    switchToPaused()
                }
            } else if (modelInitState.value == ModelInitState.Clearing) {
                if (downloadingTasks.isEmpty()) {
                    clear()
                }
            } else if (modelInitState.value == ModelInitState.Deleting) {
                if (downloadingTasks.isEmpty()) {
                    delete()
                }
            }
        }

        private fun clear() {
            val files = modelDirFile.listFiles { dir, name ->
                !(dir == modelDirFile && name == ModelConfigFilename)
            }
            require(files != null)
            for (file in files) {
                file.deleteRecursively()
                require(!file.exists())
            }
            val modelConfigFile = File(modelDirFile, ModelConfigFilename)
            require(modelConfigFile.exists())
            switchToIndexing()
        }

        private fun delete() {
            modelDirFile.deleteRecursively()
            require(!modelDirFile.exists())
            requestDeleteModel(modelConfig.modelId)
        }

        private fun switchToPausing() {
            modelInitState.value = ModelInitState.Pausing
        }

        private fun switchToPaused() {
            modelInitState.value = ModelInitState.Paused
        }


        private fun switchToFinished() {
            modelInitState.value = ModelInitState.Finished
        }

        fun startChat() {
            if (!isModelLibTested(modelConfig.modelLib)) {
                issueAlert("WARNING: modelLib '${modelConfig.modelLib}' is NOT compiled into this APK's libtvm4j_runtime_packed.so.\n\nThe current build only contains the gemma2_q4f16_1 modelLib. Chat will likely crash when starting.\n\nYou can still attempt, but expect an engine.reload() failure.")
            }
            chatState.requestReloadChat(
                modelConfig,
                modelDirFile.absolutePath,
            )
        }

    }

    inner class ChatState {
        val messages = emptyList<MessageData>().toMutableStateList()
        val report = mutableStateOf("")
        val modelName = mutableStateOf("")
        private var modelChatState = mutableStateOf(ModelChatState.Ready)
            @Synchronized get
            @Synchronized set
        private val engine = MLCEngine()
        private var historyMessages = mutableListOf<ChatCompletionMessage>()
        private var modelLib = ""
        private var modelPath = ""
        private val executorService = Executors.newSingleThreadExecutor()
        private val viewModelScope = CoroutineScope(Dispatchers.Main + Job())
        private fun mainResetChat() {
            executorService.submit {
                callBackend { engine.reset() }
                historyMessages = mutableListOf<ChatCompletionMessage>()
                viewModelScope.launch {
                    clearHistory()
                    switchToReady()
                }
            }
        }

        private fun clearHistory() {
            messages.clear()
            report.value = ""
            historyMessages.clear()
        }


        private fun switchToResetting() {
            modelChatState.value = ModelChatState.Resetting
        }

        private fun switchToGenerating() {
            modelChatState.value = ModelChatState.Generating
        }

        private fun switchToReloading() {
            modelChatState.value = ModelChatState.Reloading
        }

        private fun switchToReady() {
            modelChatState.value = ModelChatState.Ready
        }

        private fun switchToFailed() {
            modelChatState.value = ModelChatState.Falied
        }

        private fun callBackend(callback: () -> Unit): Boolean {
            try {
                callback()
            } catch (e: Exception) {
                viewModelScope.launch {
                    val stackTrace = e.stackTraceToString()
                    val errorMessage = e.localizedMessage
                    appendMessage(
                        MessageRole.Assistant,
                        "MLCChat failed\n\nStack trace:\n$stackTrace\n\nError message:\n$errorMessage"
                    )
                    switchToFailed()
                }
                return false
            }
            return true
        }

        fun requestResetChat() {
            require(interruptable())
            interruptChat(
                prologue = {
                    switchToResetting()
                },
                epilogue = {
                    mainResetChat()
                }
            )
        }

        private fun interruptChat(prologue: () -> Unit, epilogue: () -> Unit) {
            require(interruptable())
            if (modelChatState.value == ModelChatState.Ready) {
                prologue()
                epilogue()
            } else if (modelChatState.value == ModelChatState.Generating) {
                prologue()
                executorService.submit {
                    viewModelScope.launch { epilogue() }
                }
            } else {
                require(false)
            }
        }

        fun requestTerminateChat(callback: () -> Unit) {
            require(interruptable())
            interruptChat(
                prologue = {
                    switchToTerminating()
                },
                epilogue = {
                    mainTerminateChat(callback)
                }
            )
        }

        private fun mainTerminateChat(callback: () -> Unit) {
            executorService.submit {
                callBackend { engine.unload() }
                viewModelScope.launch {
                    clearHistory()
                    switchToReady()
                    callback()
                }
            }
        }

        private fun switchToTerminating() {
            modelChatState.value = ModelChatState.Terminating
        }


        fun requestReloadChat(modelConfig: ModelConfig, modelPath: String) {

            if (this.modelName.value == modelConfig.modelId && this.modelLib == modelConfig.modelLib && this.modelPath == modelPath) {
                return
            }
            require(interruptable())
            interruptChat(
                prologue = {
                    switchToReloading()
                },
                epilogue = {
                    mainReloadChat(modelConfig, modelPath)
                }
            )
        }

        private fun mainReloadChat(modelConfig: ModelConfig, modelPath: String) {
            clearHistory()
            this.modelName.value = modelConfig.modelId
            this.modelLib = modelConfig.modelLib
            this.modelPath = modelPath
            executorService.submit {
                viewModelScope.launch {
                    Toast.makeText(application, "Initialize...", Toast.LENGTH_SHORT).show()
                }
                if (!callBackend {
                        engine.unload()
                        engine.reload(modelPath, modelConfig.modelLib)
                    }) return@submit
                viewModelScope.launch {
                    Toast.makeText(application, "Ready to chat", Toast.LENGTH_SHORT).show()
                    switchToReady()
                }
            }
        }

        fun requestGenerate(prompt: String) {
            require(chatable())
            switchToGenerating()
            appendMessage(MessageRole.User, prompt)
            appendMessage(MessageRole.Assistant, "")

            executorService.submit {
                historyMessages.add(ChatCompletionMessage(
                    role = OpenAIProtocol.ChatCompletionRole.user,
                    content = prompt
                ))

                viewModelScope.launch {
                    val s = chatSettings
                    val responses = engine.chat.completions.create(
                        messages = historyMessages,
                        temperature = s.temperature.value,
                        top_p = s.topP.value,
                        max_tokens = s.maxGenLen.value,
                        stream_options = OpenAIProtocol.StreamOptions(include_usage = true)
                    )

                    var finishReasonLength = false
                    var streamingText = ""

                    for (res in responses) {
                        if (!callBackend {
                            for (choice in res.choices) {
                                choice.delta.content?.let { content ->
                                    streamingText += content.asText()
                                }
                                choice.finish_reason?.let { finishReason ->
                                    if (finishReason == "length") {
                                        finishReasonLength = true
                                    }
                                }
                            }
                            updateMessage(MessageRole.Assistant, streamingText)
                            res.usage?.let { finalUsage ->
                                report.value = finalUsage.extra?.asTextLabel() ?: ""
                            }
                            if (finishReasonLength) {
                                streamingText += " [output truncated due to context length limit...]"
                                updateMessage(MessageRole.Assistant, streamingText)
                            }
                        });
                    }
                    if (streamingText.isNotEmpty()) {
                        historyMessages.add(ChatCompletionMessage(
                            role = OpenAIProtocol.ChatCompletionRole.assistant,
                            content = streamingText
                        ))
                        streamingText = ""
                    } else {
                        if (historyMessages.isNotEmpty()) {
                            historyMessages.removeAt(historyMessages.size - 1)
                        }
                    }

                    if (modelChatState.value == ModelChatState.Generating) switchToReady()
                }
            }
        }

        // ============================================================
        // New: benchmark run - given a prompt, return metrics
        // ============================================================
        fun runBenchmark(
            prompt: String,
            maxTokens: Int,
            onResult: (BenchmarkResult) -> Unit
        ) {
            require(chatable())
            switchToGenerating()
            val startWall = System.currentTimeMillis()
            val startPromptTokens = historyMessages.size
            appendMessage(MessageRole.User, prompt)
            appendMessage(MessageRole.Assistant, "")
            executorService.submit {
                historyMessages.add(ChatCompletionMessage(
                    role = OpenAIProtocol.ChatCompletionRole.user,
                    content = prompt
                ))
                viewModelScope.launch {
                    val s = chatSettings
                    val tStart = System.currentTimeMillis()
                    var firstTokenMs = -1L
                    val responses = engine.chat.completions.create(
                        messages = historyMessages,
                        temperature = 0.0f,  // deterministic for benchmarks
                        top_p = 1.0f,
                        max_tokens = maxTokens,
                        stream_options = OpenAIProtocol.StreamOptions(include_usage = true)
                    )
                    var streamingText = ""
                    var outputTokens = 0
                    for (res in responses) {
                        if (!callBackend {
                            for (choice in res.choices) {
                                choice.delta.content?.let { content ->
                                    if (firstTokenMs < 0 && content.asText().isNotEmpty()) {
                                        firstTokenMs = System.currentTimeMillis() - tStart
                                    }
                                    streamingText += content.asText()
                                    updateMessage(MessageRole.Assistant, streamingText)
                                }
                                choice.finish_reason?.let {}
                            }
                            res.usage?.let { u ->
                                outputTokens = (u.completion_tokens ?: 0)
                                report.value = u.extra?.asTextLabel() ?: ""
                            }
                        });
                    }
                    val tEnd = System.currentTimeMillis()
                    val wallMs = tEnd - startWall
                    val genMs = tEnd - tStart
                    val ttftMs = if (firstTokenMs >= 0) firstTokenMs else -1
                    val tokPerSec = if (genMs > 0 && outputTokens > 0) outputTokens * 1000.0 / genMs else 0.0
                    if (streamingText.isNotEmpty()) {
                        historyMessages.add(ChatCompletionMessage(
                            role = OpenAIProtocol.ChatCompletionRole.assistant,
                            content = streamingText
                        ))
                    } else {
                        if (historyMessages.isNotEmpty()) {
                            historyMessages.removeAt(historyMessages.size - 1)
                        }
                    }
                    val result = BenchmarkResult(
                        prompt = prompt,
                        wallTimeMs = wallMs,
                        ttftMs = ttftMs,
                        generationTimeMs = genMs,
                        outputTokens = outputTokens,
                        tokPerSec = tokPerSec,
                        timestamp = System.currentTimeMillis()
                    )
                    onResult(result)
                    if (modelChatState.value == ModelChatState.Generating) switchToReady()
                }
            }
        }

        private fun appendMessage(role: MessageRole, text: String) {
            messages.add(MessageData(role, text))
        }


        private fun updateMessage(role: MessageRole, text: String) {
            messages[messages.size - 1] = MessageData(role, text)
        }

        fun chatable(): Boolean {
            return modelChatState.value == ModelChatState.Ready
        }

        fun interruptable(): Boolean {
            return modelChatState.value == ModelChatState.Ready
                    || modelChatState.value == ModelChatState.Generating
                    || modelChatState.value == ModelChatState.Falied
        }
    }
}

enum class ModelInitState {
    Initializing,
    Indexing,
    Paused,
    Downloading,
    Pausing,
    Clearing,
    Deleting,
    Finished
}

enum class ModelChatState {
    Generating,
    Resetting,
    Reloading,
    Terminating,
    Ready,
    Falied
}

enum class MessageRole {
    Assistant,
    User
}

data class DownloadTask(val url: URL, val file: File)

data class MessageData(val role: MessageRole, val text: String, val id: UUID = UUID.randomUUID())

data class AppConfig(
    @SerializedName("model_libs") var modelLibs: MutableList<String>,
    @SerializedName("model_list") val modelList: MutableList<ModelRecord>,
)

data class ModelRecord(
    @SerializedName("model_url") val modelUrl: String,
    @SerializedName("model_id") val modelId: String,
    @SerializedName("estimated_vram_bytes") val estimatedVramBytes: Long?,
    @SerializedName("model_lib") val modelLib: String
)

data class ModelConfig(
    @SerializedName("model_lib") var modelLib: String,
    @SerializedName("model_id") var modelId: String,
    @SerializedName("estimated_vram_bytes") var estimatedVramBytes: Long?,
    @SerializedName("tokenizer_files") val tokenizerFiles: List<String>,
    @SerializedName("context_window_size") val contextWindowSize: Int,
    @SerializedName("prefill_chunk_size") val prefillChunkSize: Int,
)

data class ParamsRecord(
    @SerializedName("dataPath") val dataPath: String
)

data class ParamsConfig(
    @SerializedName("records") val paramsRecords: List<ParamsRecord>
)

// ============================================================
// New: chat settings (persisted to JSON)
// ============================================================
data class ChatSettings(
    val temperature: MutableFloatStateHolder = MutableFloatStateHolder(0.7f, 0.0f, 2.0f),
    val topP: MutableFloatStateHolder = MutableFloatStateHolder(0.95f, 0.0f, 1.0f),
    val maxGenLen: MutableIntStateHolder = MutableIntStateHolder(512, 1, 8192),
    val meanGenLen: MutableIntStateHolder = MutableIntStateHolder(128, 1, 4096),
    val repetitionPenalty: MutableFloatStateHolder = MutableFloatStateHolder(1.0f, 0.5f, 2.0f),
    val presencePenalty: MutableFloatStateHolder = MutableFloatStateHolder(0.0f, -2.0f, 2.0f),
    val frequencyPenalty: MutableFloatStateHolder = MutableFloatStateHolder(0.0f, -2.0f, 2.0f),
    val shiftK: MutableIntStateHolder = MutableIntStateHolder(0, -1, 32),
    val prefillChunkSize: MutableIntStateHolder = MutableIntStateHolder(2048, 128, 8192),
    val streaming: MutableBooleanStateHolder = MutableBooleanStateHolder(true),
    val threads: MutableIntStateHolder = MutableIntStateHolder(2, 1, 8),
    val systemPrompt: MutableStringStateHolder = MutableStringStateHolder("You are a helpful, respectful and honest assistant."),
    val convTemplate: MutableStringStateHolder = MutableStringStateHolder("auto")
) {
    fun load(application: Application, baseDir: java.io.File?) {
        try {
            val f = java.io.File(baseDir, "chat-settings.json")
            if (f.exists()) {
                val gson = com.google.gson.Gson()
                val obj = gson.fromJson(f.readText(), com.google.gson.JsonObject::class.java)
                fun gF(name: String, default: Float): Float = if (obj.has(name)) obj.get(name).asFloat else default
                fun gI(name: String, default: Int): Int = if (obj.has(name)) obj.get(name).asInt else default
                fun gB(name: String, default: Boolean): Boolean = if (obj.has(name)) obj.get(name).asBoolean else default
                fun gS(name: String, default: String): String = if (obj.has(name)) obj.get(name).asString else default
                temperature.set(gF("temperature", temperature.value))
                topP.set(gF("top_p", topP.value))
                maxGenLen.set(gI("max_gen_len", maxGenLen.value))
                meanGenLen.set(gI("mean_gen_len", meanGenLen.value))
                repetitionPenalty.set(gF("repetition_penalty", repetitionPenalty.value))
                presencePenalty.set(gF("presence_penalty", presencePenalty.value))
                frequencyPenalty.set(gF("frequency_penalty", frequencyPenalty.value))
                shiftK.set(gI("shift_k", shiftK.value))
                prefillChunkSize.set(gI("prefill_chunk_size", prefillChunkSize.value))
                streaming.set(gB("streaming", streaming.value))
                threads.set(gI("threads", threads.value))
                systemPrompt.set(gS("system_prompt", systemPrompt.value))
                convTemplate.set(gS("conv_template", convTemplate.value))
            }
        } catch (e: Exception) { /* defaults */ }
    }
    fun save(application: Application, baseDir: java.io.File?) {
        try {
            val f = java.io.File(baseDir, "chat-settings.json")
            val gson = com.google.gson.Gson()
            val obj = com.google.gson.JsonObject()
            obj.addProperty("temperature", temperature.value)
            obj.addProperty("top_p", topP.value)
            obj.addProperty("max_gen_len", maxGenLen.value)
            obj.addProperty("mean_gen_len", meanGenLen.value)
            obj.addProperty("repetition_penalty", repetitionPenalty.value)
            obj.addProperty("presence_penalty", presencePenalty.value)
            obj.addProperty("frequency_penalty", frequencyPenalty.value)
            obj.addProperty("shift_k", shiftK.value)
            obj.addProperty("prefill_chunk_size", prefillChunkSize.value)
            obj.addProperty("streaming", streaming.value)
            obj.addProperty("threads", threads.value)
            obj.addProperty("system_prompt", systemPrompt.value)
            obj.addProperty("conv_template", convTemplate.value)
            f.writeText(gson.toJson(obj))
        } catch (e: Exception) { /* ignore */ }
    }
    fun summary(): String =
        "temp=${temperature.value}\n" +
        "top_p=${topP.value}\n" +
        "max_tokens=${maxGenLen.value}\n" +
        "mean_gen_len=${meanGenLen.value}\n" +
        "rep_pen=${repetitionPenalty.value}\n" +
        "pres_pen=${presencePenalty.value}\n" +
        "freq_pen=${frequencyPenalty.value}\n" +
        "shift_k=${shiftK.value}\n" +
        "prefill=${prefillChunkSize.value}\n" +
        "streaming=${streaming.value}\n" +
        "threads=${threads.value}\n" +
        "conv=$convTemplate\n" +
        "system='${systemPrompt.value.take(40)}...'"
}

// Mutable holders for Compose-friendly state
class MutableFloatStateHolder(initial: Float, val min: Float, val max: Float) {
    val value = mutableStateOf(initial.coerceIn(min, max))
    fun set(v: Float) { value.value = v.coerceIn(min, max) }
}
class MutableIntStateHolder(initial: Int, val min: Int, val max: Int) {
    val value = mutableStateOf(initial.coerceIn(min, max))
    fun set(v: Int) { value.value = v.coerceIn(min, max) }
}
class MutableBooleanStateHolder(initial: Boolean) {
    val value = mutableStateOf(initial)
    fun set(v: Boolean) { value.value = v }
}
class MutableStringStateHolder(initial: String) {
    val value = mutableStateOf(initial)
    fun set(v: String) { value.value = v }
}

// ============================================================
// New: benchmark result + history
// ============================================================
data class BenchmarkResult(
    val prompt: String,
    val wallTimeMs: Long,
    val ttftMs: Long,
    val generationTimeMs: Long,
    val outputTokens: Int,
    val tokPerSec: Double,
    val timestamp: Long
) {
    fun format(): String =
        "wall=${wallTimeMs}ms | ttft=${ttftMs}ms | gen=${generationTimeMs}ms | out_tok=$outputTokens | tok/s=${String.format("%.2f", tokPerSec)}"
}

class BenchmarkState {
    val history = emptyList<BenchmarkResult>().toMutableStateList()
    val running = mutableStateOf(false)
    val lastResult = mutableStateOf<BenchmarkResult?>(null)
    val progressLabel = mutableStateOf("")

    fun push(r: BenchmarkResult) {
        history.add(0, r)
        if (history.size > 50) history.removeAt(history.lastIndex)
        lastResult.value = r
    }

    fun load(baseDir: java.io.File?) {
        try {
            val f = java.io.File(baseDir, "bench-history.json")
            if (!f.exists()) return
            val gson = com.google.gson.Gson()
            val arr = gson.fromJson(f.readText(), com.google.gson.JsonArray::class.java)
            for (i in 0 until arr.size()) {
                val o = arr[i].asJsonObject
                history.add(BenchmarkResult(
                    prompt = o.get("prompt").asString,
                    wallTimeMs = o.get("wall_time_ms").asLong,
                    ttftMs = if (o.has("ttft_ms")) o.get("ttft_ms").asLong else -1L,
                    generationTimeMs = o.get("generation_time_ms").asLong,
                    outputTokens = o.get("output_tokens").asInt,
                    tokPerSec = o.get("tok_per_sec").asDouble,
                    timestamp = o.get("timestamp").asLong
                ))
            }
        } catch (e: Exception) {}
    }

    fun save(baseDir: java.io.File?) {
        try {
            val f = java.io.File(baseDir, "bench-history.json")
            val gson = com.google.gson.Gson()
            val arr = com.google.gson.JsonArray()
            for (r in history) {
                val o = com.google.gson.JsonObject()
                o.addProperty("prompt", r.prompt)
                o.addProperty("wall_time_ms", r.wallTimeMs)
                o.addProperty("ttft_ms", r.ttftMs)
                o.addProperty("generation_time_ms", r.generationTimeMs)
                o.addProperty("output_tokens", r.outputTokens)
                o.addProperty("tok_per_sec", r.tokPerSec)
                o.addProperty("timestamp", r.timestamp)
                arr.add(o)
            }
            f.writeText(gson.toJson(arr))
        } catch (e: Exception) {}
    }

    fun clear() {
        history.clear()
        lastResult.value = null
    }
}
