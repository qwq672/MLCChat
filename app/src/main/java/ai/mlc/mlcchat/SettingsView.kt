package ai.mlc.mlcchat

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController

private val convTemplates = listOf(
    "auto", "llama-2", "llama-3", "qwen", "qwen2", "qwen2.5",
    "gemma", "gemma_instruction", "chatml",
    "phi-2", "phi-3", "mistral_default", "mistral_small",
    "smollm", "tinyllama", "open_hermes"
)

@ExperimentalMaterial3Api
@Composable
fun SettingsView(navController: NavController, appViewModel: AppViewModel) {
    val localFocusManager = LocalFocusManager.current
    val settings = appViewModel.chatSettings

    // local mutable copies so sliders update instantly
    var temperature by remember { mutableStateOf(settings.temperature.value) }
    var topP by remember { mutableStateOf(settings.topP.value) }
    var maxGenLen by remember { mutableStateOf(settings.maxGenLen.value.toFloat()) }
    var meanGenLen by remember { mutableStateOf(settings.meanGenLen.value.toFloat()) }
    var repPen by remember { mutableStateOf(settings.repetitionPenalty.value) }
    var presPen by remember { mutableStateOf(settings.presencePenalty.value) }
    var freqPen by remember { mutableStateOf(settings.frequencyPenalty.value) }
    var shiftK by remember { mutableStateOf(settings.shiftK.value.toFloat()) }
    var prefillChunk by remember { mutableStateOf(settings.prefillChunkSize.value.toFloat()) }
    var streaming by remember { mutableStateOf(settings.streaming.value) }
    var threads by remember { mutableStateOf(settings.threads.value.toFloat()) }
    var systemPrompt by remember { mutableStateOf(settings.systemPrompt.value) }
    var convTemplate by remember { mutableStateOf(settings.convTemplate.value) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Chat Settings", color = MaterialTheme.colorScheme.onPrimary) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primary),
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Filled.ArrowBack, "back", tint = MaterialTheme.colorScheme.onPrimary)
                    }
                },
                actions = {
                    TextButton(onClick = {
                        settings.temperature.set(0.7f)
                        settings.topP.set(0.95f)
                        settings.maxGenLen.set(512)
                        settings.meanGenLen.set(128)
                        settings.repetitionPenalty.set(1.0f)
                        settings.presencePenalty.set(0.0f)
                        settings.frequencyPenalty.set(0.0f)
                        settings.shiftK.set(0)
                        settings.prefillChunkSize.set(2048)
                        settings.streaming.set(true)
                        settings.threads.set(2)
                        settings.systemPrompt.set("You are a helpful, respectful and honest assistant.")
                        settings.convTemplate.set("auto")
                        val app = appViewModel.getApplication<android.app.Application>()
                        settings.save(app, app.getExternalFilesDir(""))
                        // refresh local
                        temperature = settings.temperature.value
                        topP = settings.topP.value
                        maxGenLen = settings.maxGenLen.value.toFloat()
                        meanGenLen = settings.meanGenLen.value.toFloat()
                        repPen = settings.repetitionPenalty.value
                        presPen = settings.presencePenalty.value
                        freqPen = settings.frequencyPenalty.value
                        shiftK = settings.shiftK.value.toFloat()
                        prefillChunk = settings.prefillChunkSize.value.toFloat()
                        streaming = settings.streaming.value
                        threads = settings.threads.value.toFloat()
                        systemPrompt = settings.systemPrompt.value
                        convTemplate = settings.convTemplate.value
                    }) {
                        Text("Reset", color = MaterialTheme.colorScheme.onPrimary)
                    }
                    TextButton(onClick = {
                        settings.temperature.set(temperature)
                        settings.topP.set(topP)
                        settings.maxGenLen.set(maxGenLen.toInt())
                        settings.meanGenLen.set(meanGenLen.toInt())
                        settings.repetitionPenalty.set(repPen)
                        settings.presencePenalty.set(presPen)
                        settings.frequencyPenalty.set(freqPen)
                        settings.shiftK.set(shiftK.toInt())
                        settings.prefillChunkSize.set(prefillChunk.toInt())
                        settings.streaming.set(streaming)
                        settings.threads.set(threads.toInt())
                        settings.systemPrompt.set(systemPrompt)
                        settings.convTemplate.set(convTemplate)
                        val app = appViewModel.getApplication<android.app.Application>()
                        settings.save(app, app.getExternalFilesDir(""))
                    }) {
                        Text("Save", color = MaterialTheme.colorScheme.onPrimary)
                    }
                }
            )
        },
        modifier = Modifier.pointerInput(Unit) {
            detectTapGestures(onTap = { localFocusManager.clearFocus() })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp)
        ) {
            // Sampling params
            SettingsSection("Sampling")
            SliderRow("Temperature", temperature, 0.0f, 2.0f, 0.01f) {
                temperature = it
            }
            SliderRow("Top P", topP, 0.0f, 1.0f, 0.01f) { topP = it }
            SliderRow("Repetition Penalty", repPen, 0.5f, 2.0f, 0.01f) { repPen = it }
            SliderRow("Presence Penalty", presPen, -2.0f, 2.0f, 0.01f) { presPen = it }
            SliderRow("Frequency Penalty", freqPen, -2.0f, 2.0f, 0.01f) { freqPen = it }

            Divider(modifier = Modifier.padding(vertical = 12.dp))

            // Length params
            SettingsSection("Length Limits")
            SliderRow("Max Generation Length", maxGenLen, 1f, 8192f, 1f) { maxGenLen = it }
            SliderRow("Mean Generation Length", meanGenLen, 1f, 4096f, 1f) { meanGenLen = it }

            Divider(modifier = Modifier.padding(vertical = 12.dp))

            // Engine params
            SettingsSection("Engine")
            SliderRow("Threads", threads, 1f, 8f, 1f) { threads = it }
            SliderRow("Shift K", shiftK, -1f, 32f, 1f) { shiftK = it }
            SliderRow("Prefill Chunk Size", prefillChunk, 128f, 8192f, 128f) { prefillChunk = it }

            Row(
                modifier = Modifier.fillMaxWidth().wrapContentHeight().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Streaming", modifier = Modifier.weight(1f))
                Switch(checked = streaming, onCheckedChange = { streaming = it })
            }

            Divider(modifier = Modifier.padding(vertical = 12.dp))

            // Conversation
            SettingsSection("Conversation")
            Text("Conversation Template (modelLib-dependent)", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            Text(
                text = convTemplate,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .padding(vertical = 4.dp)
                    .fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth().wrapContentHeight(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                convTemplates.take(8).forEach { t ->
                    TextButton(
                        onClick = { convTemplate = t },
                        modifier = Modifier
                    ) {
                        Text(
                            t,
                            fontSize = 10.sp,
                            color = if (t == convTemplate) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().wrapContentHeight(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                convTemplates.drop(8).forEach { t ->
                    TextButton(
                        onClick = { convTemplate = t },
                        modifier = Modifier
                    ) {
                        Text(
                            t,
                            fontSize = 10.sp,
                            color = if (t == convTemplate) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }
            }

            OutlinedTextField(
                value = systemPrompt,
                onValueChange = { systemPrompt = it },
                label = { Text("System Prompt") },
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                minLines = 3,
                maxLines = 6
            )

            Divider(modifier = Modifier.padding(vertical = 12.dp))

            // Summary
            SettingsSection("Current Summary")
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
            ) {
                Text(
                    text = settings.summary(),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(8.dp)
                )
            }

            Divider(modifier = Modifier.padding(vertical = 12.dp))

            // Tips
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
            ) {
                Text(
                    text = "Tips:\n" +
                            "- Some settings (temperature, top_p, max_tokens) are sent to MLCEngine via OpenAI-compatible API.\n" +
                            "- Settings like conv_template, threads, shift_k, prefill_chunk_size are modelLib-defined and may NOT take effect at runtime.\n" +
                            "- After saving, tap the back arrow and start a new chat (or reset current chat) for changes to take effect.",
                    fontSize = 10.sp,
                    modifier = Modifier.padding(8.dp)
                )
            }
        }
    }
}

@Composable
private fun SettingsSection(title: String) {
    Text(
        title,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(vertical = 6.dp)
    )
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    step: Float,
    onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text(
                "%.${if (step >= 1f) 0 else 2}f".format(value),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = value.coerceIn(min, max),
            onValueChange = { onValueChange(it) },
            valueRange = min..max,
            steps = if (step >= 1f) {
                val raw = ((max - min) / step).toInt() - 1
                raw.coerceIn(0, 60)
            } else 0,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
