package ai.mlc.mlcchat

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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

private val defaultPrompts = listOf(
    "Hello, how are you?" to 64,
    "Write a haiku about the ocean." to 64,
    "Explain photosynthesis in 3 sentences." to 128,
    "What is the capital of France?" to 64,
    "Tell me a 100-word story about a robot learning to paint." to 256,
    "Write a Python function to check if a string is a palindrome." to 256,
    "Summarize the plot of Romeo and Juliet in 50 words." to 128,
    "What are the main causes of climate change? Answer in 100 words." to 200
)

@ExperimentalMaterial3Api
@Composable
fun BenchmarkView(navController: NavController, appViewModel: AppViewModel) {
    val localFocusManager = LocalFocusManager.current
    val bench = appViewModel.benchmarkState
    val chatState = appViewModel.chatState

    var customPrompt by remember { mutableStateOf("Tell me a short story about a cat.") }
    var customMaxTok by remember { mutableStateOf("256") }

    LaunchedEffect(Unit) {
        val app = appViewModel.getApplication<android.app.Application>()
        bench.load(app.getExternalFilesDir(""))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("Benchmark: ${chatState.modelName.value.ifEmpty { "(no model) " }}",
                        color = MaterialTheme.colorScheme.onPrimary)
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primary),
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Filled.ArrowBack, "back", tint = MaterialTheme.colorScheme.onPrimary)
                    }
                },
                actions = {
                    TextButton(onClick = {
                        bench.clear()
                    }) {
                        Text("Clear", color = MaterialTheme.colorScheme.onPrimary)
                    }
                }
            )
        },
        modifier = Modifier.pointerInput(Unit) {
            detectTapGestures(onTap = { localFocusManager.clearFocus() })
        }
    ) { padding ->
        if (chatState.modelName.value.isEmpty()) {
            // no model loaded
            Box(modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "No model loaded.\n\nGo back and tap the chat icon on a downloaded model first to load it into the engine.",
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp)
        ) {
            // Status
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Text(
                        "Model: ${chatState.modelName.value}",
                        fontWeight = FontWeight.Medium,
                        fontSize = 12.sp
                    )
                    Text(
                        "Status: ${if (bench.running.value) "RUNNING..." else "idle"}",
                        fontSize = 11.sp
                    )
                    bench.lastResult.value?.let {
                        Text(
                            "Last: ${it.format()}",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            if (bench.running.value) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                )
                Text(bench.progressLabel.value, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
            }

            Text("Standard Prompts", fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.padding(vertical = 6.dp))

            defaultPrompts.forEachIndexed { idx, (p, mt) ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(p, fontSize = 11.sp)
                            Text("max_tokens=$mt", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        }
                        IconButton(
                            onClick = {
                                if (chatState.chatable()) {
                                    bench.running.value = true
                                    bench.progressLabel.value = "Running #${idx + 1}..."
                                    chatState.runBenchmark(p, mt) { r ->
                                        bench.push(r)
                                        bench.running.value = false
                                        bench.progressLabel.value = ""
                                        val app = appViewModel.getApplication<android.app.Application>()
                                        bench.save(app.getExternalFilesDir(""))
                                    }
                                }
                            },
                            enabled = chatState.chatable() && !bench.running.value
                        ) {
                            Icon(Icons.Filled.PlayArrow, "run")
                        }
                    }
                }
            }

            Divider(modifier = Modifier.padding(vertical = 8.dp))

            Text("Tip: Run each prompt individually above to collect metrics. Lower tok/s indicates slower generation; high TTFT indicates slow prefill.",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(vertical = 4.dp))

            Divider(modifier = Modifier.padding(vertical = 8.dp))

            Text("Custom Prompt", fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.padding(vertical = 4.dp))
            OutlinedTextField(
                value = customPrompt,
                onValueChange = { customPrompt = it },
                label = { Text("Prompt") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 4
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Max tokens:", fontSize = 11.sp, modifier = Modifier.padding(end = 6.dp))
                OutlinedTextField(
                    value = customMaxTok,
                    onValueChange = { customMaxTok = it.filter { c -> c.isDigit() } },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = {
                        val mt = customMaxTok.toIntOrNull() ?: 256
                        if (chatState.chatable() && !bench.running.value) {
                            bench.running.value = true
                            bench.progressLabel.value = "Running custom..."
                            chatState.runBenchmark(customPrompt, mt) { r ->
                                bench.push(r)
                                bench.running.value = false
                                bench.progressLabel.value = ""
                                val app = appViewModel.getApplication<android.app.Application>()
                                bench.save(app.getExternalFilesDir(""))
                            }
                        }
                    },
                    enabled = chatState.chatable() && !bench.running.value
                ) {
                    Icon(Icons.Filled.PlayArrow, "run")
                }
            }

            Divider(modifier = Modifier.padding(vertical = 8.dp))

            // History
            Text("History (${bench.history.size})", fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.padding(vertical = 4.dp))
            if (bench.history.isEmpty()) {
                Text("No benchmarks yet. Run a prompt above to see metrics.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(vertical = 6.dp))
            } else {
                bench.history.forEach { r: BenchmarkResult ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Text(
                                text = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                                    .format(java.util.Date(r.timestamp)) +
                                    "  tok/s=" + String.format("%.2f", r.tokPerSec) +
                                    "  ttft=" + r.ttftMs + "ms" +
                                    "  out=" + r.outputTokens,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            SelectionContainer {
                                Text(
                                    text = r.prompt,
                                    fontSize = 10.sp,
                                    maxLines = 2
                                )
                            }
                            Text(
                                text = "wall=${r.wallTimeMs}ms, gen=${r.generationTimeMs}ms",
                                fontSize = 9.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            }

            // Tips
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
            ) {
                Text(
                    text = "Benchmark Notes:\n" +
                            "- Uses temperature=0.0, top_p=1.0 for deterministic outputs.\n" +
                            "- TTFT = Time To First Token (ms).\n" +
                            "- tok/s = output tokens / generation time.\n" +
                            "- If chat fails (e.g. unsupported modelLib), benchmark will not produce results.\n" +
                            "- Results are saved to bench-history.json in app data dir.",
                    fontSize = 10.sp,
                    modifier = Modifier.padding(8.dp)
                )
            }
        }
    }
}
