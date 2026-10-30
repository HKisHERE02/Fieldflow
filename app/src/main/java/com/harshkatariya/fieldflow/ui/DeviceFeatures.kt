package com.harshkatariya.fieldflow.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.harshkatariya.fieldflow.core.Asset
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Composable private fun CameraPermission(content: @Composable () -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var denied by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it; denied = !it }
    if (granted) content() else {
        Panel("Camera access", if (denied) "Permission denied. You can allow camera access in Android app settings. Manual equipment entry remains available." else "Allow access to scan equipment or capture inspection evidence.")
        Primary("Allow camera") { launcher.launch(Manifest.permission.CAMERA) }
    }
}
@Composable fun ScannerScreen(assets: List<Asset>, navigate: (String) -> Unit, report: (String) -> Unit) {
    var code by remember { mutableStateOf("") }; var handled by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    fun resolve(value: String) {
        if (handled) return
        val clean = value.trim().removePrefix("fieldflow://asset/")
        val asset = assets.find { it.code.equals(clean, true) || it.id == clean }
        if (asset == null) error = "Unknown equipment: ${clean.take(80)}. Search local records or sync assigned work." else { handled = true; navigate(asset.id) }
    }
    Page {
        Heading("Scan equipment", "Point at a FieldFlow QR code.")
        CameraPermission { CameraPreview(scan = { resolve(it) }) }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        OutlinedTextField(code, { code = it }, label = { Text("Equipment code, e.g. FF-001") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Primary("Find equipment", code.isNotBlank()) { resolve(code) }
    }
}
@Composable fun CameraScreen(saved: (String) -> Unit) {
    Page { Heading("Photo evidence", "Capture a clear view of the equipment or defect."); CameraPermission { CameraPreview(saved = saved) } }
}

@androidx.annotation.OptIn(ExperimentalGetImage::class)
@Composable private fun CameraPreview(scan: ((String) -> Unit)? = null, saved: ((String) -> Unit)? = null) {
    val context = LocalContext.current; val lifecycle = LocalLifecycleOwner.current
    val preview = remember { PreviewView(context) }
    var capture by remember { mutableStateOf<ImageCapture?>(null) }
    var error by remember { mutableStateOf("") }; var capturing by remember { mutableStateOf(false) }
    val latestScan by rememberUpdatedState(scan)
    val latestSaved by rememberUpdatedState(saved)
    DisposableEffect(lifecycle, preview) {
        val executor = Executors.newSingleThreadExecutor()
        val main = ContextCompat.getMainExecutor(context)
        val future = ProcessCameraProvider.getInstance(context)
        val scanner = if (scan != null) BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()) else null
        var provider: ProcessCameraProvider? = null
        var disposed = false
        var lastCode = ""
        val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
        val cameraPreview = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
        val imageCapture = ImageCapture.Builder().setTargetResolution(Size(1280, 960)).setJpegQuality(85).build()
        future.addListener({
            if (!disposed) try {
                provider = future.get()
                if (scanner != null) {
                    analysis.setAnalyzer(executor) { image ->
                        val media = image.image
                        if (media == null) image.close() else scanner.process(InputImage.fromMediaImage(media, image.imageInfo.rotationDegrees))
                            .addOnSuccessListener(main) { results ->
                                results.firstOrNull()?.rawValue?.let { value -> if (value != lastCode && !disposed) { lastCode = value; latestScan?.invoke(value) } }
                            }.addOnFailureListener(main) { if (!disposed) error = "Could not read the code. Try manual entry." }.addOnCompleteListener { image.close() }
                    }
                    provider!!.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, cameraPreview, analysis)
                } else { provider!!.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, cameraPreview, imageCapture); capture = imageCapture }
            } catch (_: Exception) { error = "Camera unavailable. Try a device with a rear camera, or use manual equipment entry." }
        }, main)
        onDispose { disposed = true; analysis.clearAnalyzer(); provider?.unbind(cameraPreview, analysis, imageCapture); scanner?.close(); executor.shutdown(); capture = null }
    }
    AndroidView({ preview }, Modifier.fillMaxWidth().height(300.dp))
    if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
    if (saved != null) Primary(if (capturing) "Saving photo…" else "Capture photo", capture != null && !capturing) {
        val directory = File(context.filesDir, "photos").apply { mkdirs() }
        val file = File(directory, "${UUID.randomUUID()}.jpg")
        capturing = true
        capture?.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(), ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(result: ImageCapture.OutputFileResults) { latestSaved?.invoke(file.absolutePath); capturing = false }
            override fun onError(exception: ImageCaptureException) { file.delete(); error = "Capture failed. Please retry."; capturing = false }
        })
    }
}

@Composable fun LocationButton(saved: (Double, Double) -> Unit) {
    val context = LocalContext.current; val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf("") }; var waiting by remember { mutableStateOf(false) }
    fun locate() {
        scope.launch {
            waiting = true; message = "Requesting one location fix…"
            try {
                val location = withTimeout(15_000) { singleLocation(context.getSystemService(LocationManager::class.java)) }
                saved(location.latitude, location.longitude); message = "Location attached"
            } catch (e: TimeoutCancellationException) { message = "Location timed out. You can continue without it." }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { message = "Location unavailable. Enable device location or continue without it." }
            finally { waiting = false }
        }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.any { it }) locate() else message = "Location permission denied. Inspection can continue."
    }
    OutlinedButton({
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) locate()
        else launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }, Modifier.fillMaxWidth(), enabled = !waiting) { Text("Attach optional location") }
    if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)
}
@SuppressLint("MissingPermission")
private suspend fun singleLocation(manager: LocationManager): Location = suspendCancellableCoroutine { continuation ->
    val provider = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER).firstOrNull { manager.isProviderEnabled(it) }
    if (provider == null) { continuation.resumeWithException(IllegalStateException("Location disabled")); return@suspendCancellableCoroutine }
    val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) { manager.removeUpdates(this); if (continuation.isActive) continuation.resume(location) }
        override fun onProviderDisabled(provider: String) { manager.removeUpdates(this); if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Provider disabled")) }
    }
    continuation.invokeOnCancellation { manager.removeUpdates(listener) }
    try { manager.requestLocationUpdates(provider, 0, 0f, listener, Looper.getMainLooper()) } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
}
