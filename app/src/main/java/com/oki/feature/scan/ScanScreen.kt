package com.oki.feature.scan

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.oki.AppContainer
import com.oki.core.ai.*
import com.oki.core.ui.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

@Serializable
data class ScanDraft(
    val id: String = UUID.randomUUID().toString(),
    val content: JsonObject,
    val saved: Boolean = false,
)

class ScanViewModel(
    private val c: AppContainer,
    context: Context,
    private val state: SavedStateHandle,
) : ActionViewModel() {
    private val prep = ImagePreparation(context)
    private val cacheDirectory = context.cacheDir
    val path = state.getStateFlow<String?>("image_path", null)
    val drafts =
        MutableStateFlow(
            state
                .get<String>("scan_drafts")
                ?.let { runCatching { aiJson.decodeFromString<List<ScanDraft>>(it) }.getOrNull() }
                .orEmpty()
        )
    val scanning = MutableStateFlow(false)
    val scanError = MutableStateFlow<String?>(null)
    private var job: Job? = null
    private var generation = 0

    private fun updateDrafts(value: List<ScanDraft>) {
        state["scan_drafts"] = aiJson.encodeToString(value)
        drafts.value = value
    }

    fun markSaved(id: String) {
        updateDrafts(drafts.value.map { if (it.id == id) it.copy(saved = true) else it })
    }

    fun select(uri: Uri) {
        cancel()
        action {
            updateDrafts(emptyList())
            scanError.value = null
            val previous = path.value
            state["image_path"] = prep.prepare(uri).path
            withContext(Dispatchers.IO) {
                previous?.let { File(it).delete() }
                if (uri.scheme == "file") {
                    val original = File(uri.path.orEmpty())
                    if (
                        original.parentFile == cacheDirectory &&
                            original.name.startsWith("oki_capture_")
                    )
                        original.delete()
                }
            }
        }
    }

    fun extract(doctor: Boolean) {
        if (scanning.value) return
        val file = path.value ?: return
        scanning.value = true
        scanError.value = null
        val requestGeneration = ++generation
        job =
            viewModelScope.launch {
                try {
                    val result =
                        c.gemini.extract(
                            withContext(Dispatchers.IO) { File(file).readBytes() },
                            doctor,
                        )
                    if (generation != requestGeneration) return@launch
                    updateDrafts(result.map { ScanDraft(content = it) })
                    if (drafts.value.isEmpty())
                        scanError.value =
                            "No readable ${if (doctor) "doctors" else "tasks"} found. Try another image or enter manually."
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    scanError.value = friendlyError(e)
                } finally {
                    if (generation == requestGeneration) scanning.value = false
                }
            }
    }

    fun cancel() {
        generation++
        job?.cancel()
        scanning.value = false
    }
}

@Composable
fun ScanScreen(
    vm: ScanViewModel,
    doctor: Boolean,
    review: (String, String) -> Unit,
    manual: () -> Unit,
) {
    val path by vm.path.collectAsStateWithLifecycle()
    val drafts by vm.drafts.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val scanError by vm.scanError.collectAsStateWithLifecycle()
    var camera by remember { mutableStateOf(false) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) {
            it?.let(vm::select)
        }
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            if (it) camera = true
            else cameraError = "Camera access was denied. You can choose a gallery image instead."
        }
    DisposableEffect(Unit) { onDispose { vm.cancel() } }
    if (camera) {
        CameraCapture(
            { uri ->
                camera = false
                vm.select(uri)
            },
            { camera = false },
            {
                cameraError = it
                camera = false
            },
        )
        return
    }
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(bottom = 30.dp),
    ) {
        item {
            PageHeading(
                "A picture. A head start.",
                "Scan ${if (doctor) "a card or doctor list" else "a note or task list"}. Review every item before saving.",
            )
        }
        item {
            Text(
                "When you tap Extract, Groq or Google Gemini reads the selected image. You can review and edit all detected ${if (doctor) "doctors" else "tasks"} below before saving them.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        if (
                            ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.CAMERA,
                            ) == PackageManager.PERMISSION_GRANTED
                        )
                            camera = true
                        else permission.launch(Manifest.permission.CAMERA)
                    },
                    enabled = !scanning && !busy,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Outlined.PhotoCamera, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Camera")
                }
                OutlinedButton(
                    onClick = {
                        picker.launch(
                            PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly
                            )
                        )
                    },
                    enabled = !scanning && !busy,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Outlined.PhotoLibrary, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Gallery")
                }
            }
        }
        if (path != null)
            item {
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    AsyncImage(
                        File(path!!),
                        "Selected image for review",
                        Modifier.fillMaxWidth().height(280.dp),
                    )
                }
            }
        if (busy || scanning)
            item {
                CenterLoader(
                    message = if (busy) "Preparing your image…" else "Reading the details…"
                )
            }
        item { ErrorBanner(error ?: scanError ?: cameraError) }
        if (scanning) item { OutlinedButton(onClick = vm::cancel) { Text("Cancel extraction") } }
        else if (path != null && drafts.isEmpty())
            item {
                Button(
                    onClick = { vm.extract(doctor) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    Text(if (scanError == null) "Extract details" else "Retry extraction")
                }
            }
        if (drafts.isNotEmpty())
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Review ${drafts.size} ${if (doctor) "doctors" else "tasks"}",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        "${drafts.count { it.saved }} of ${drafts.size} created · Tap an item to review",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        items(drafts, key = { it.id }) { draft ->
            val title =
                draft.content[if (doctor) "doctorName" else "title"]
                    ?.jsonPrimitive
                    ?.contentOrNull
                    .orEmpty()
                    .ifBlank { "Untitled draft" }
            OutlinedCard(
                onClick = { review(draft.id, draft.content.toString()) },
                enabled = !draft.saved,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(18.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    val details =
                        if (doctor) listOf("department", "hospitalOrClinic")
                        else listOf("date", "time", "endTime")
                    val summary =
                        details
                            .mapNotNull { draft.content[it]?.jsonPrimitive?.contentOrNull }
                            .filter { it.isNotBlank() }
                            .joinToString(" · ")
                    if (summary.isNotEmpty())
                        Text(summary, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (draft.saved) "${if (doctor) "Doctor" else "Task"} created"
                        else "Review & edit →",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            TextButton(onClick = manual, modifier = Modifier.fillMaxWidth()) {
                Text("Enter manually")
            }
        }
    }
}

@Composable
fun CameraCapture(captured: (Uri) -> Unit, back: () -> Unit, error: (String) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }
    val capture = remember {
        ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
    }
    var ready by remember { mutableStateOf(false) }
    var taking by remember { mutableStateOf(false) }
    DisposableEffect(owner) {
        val future = ProcessCameraProvider.getInstance(context)
        val preview =
            Preview.Builder().build().apply { surfaceProvider = previewView.surfaceProvider }
        var disposed = false
        future.addListener(
            {
                if (!disposed)
                    try {
                        future
                            .get()
                            .bindToLifecycle(
                                owner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                capture,
                            )
                        ready = true
                    } catch (_: Exception) {
                        error("Camera could not be opened. Choose an image from your gallery.")
                    }
            },
            ContextCompat.getMainExecutor(context),
        )
        onDispose {
            disposed = true
            if (future.isDone) runCatching { future.get().unbind(preview, capture) }
        }
    }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        AndroidView(factory = { previewView }, modifier = Modifier.weight(1f).fillMaxWidth())
        Button(
            onClick = {
                taking = true
                val file = File.createTempFile("oki_capture_", ".jpg", context.cacheDir)
                capture.takePicture(
                    ImageCapture.OutputFileOptions.Builder(file).build(),
                    ContextCompat.getMainExecutor(context),
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                            taking = false
                            captured(Uri.fromFile(file))
                        }

                        override fun onError(exception: ImageCaptureException) {
                            taking = false
                            file.delete()
                            error("Photo could not be captured. Try again.")
                        }
                    },
                )
            },
            enabled = ready && !taking,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        ) {
            Text(if (taking) "Capturing…" else "Capture photo")
        }
        TextButton(onClick = back, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
    }
}
