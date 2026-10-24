package com.zpocket.app.ui

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.PlanarYUVLuminanceSource
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Composable
fun ScannerScreen(onBack: () -> Unit, onScanned: (String) -> Boolean) {
    val context = LocalContext.current
    BackHandler(onBack = onBack)

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var permissionDeniedBefore by remember { mutableStateOf(false) }
    var torchOn by remember { mutableStateOf(false) }
    val connecting = remember { AtomicBoolean(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        if (!granted) permissionDeniedBefore = true
    }

    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    fun deliver(raw: String) {
        val text = raw.trim()
        if (text.startsWith("http://") || text.startsWith("https://")) {
            if (connecting.compareAndSet(false, true)) {
                vibrate(context)
                if (!onScanned(text)) {
                    // 链接格式有效但无法建立连接记录：允许继续扫下一个
                    connecting.set(false)
                    Toast.makeText(context, "该链接无法建立连接", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            // 识别到了但不是链接：明确告诉用户内容是什么，而不是无声失败
            Toast.makeText(
                context,
                "识别到非链接内容：${text.take(80)}",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .height(56.dp)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Color.White)
            }
            Spacer(Modifier.width(8.dp))
            Text(
                "扫码连接",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            if (hasPermission) {
                IconButton(onClick = { torchOn = !torchOn }) {
                    Icon(
                        AppIcons.Flashlight,
                        contentDescription = "手电筒",
                        tint = if (torchOn) MaterialTheme.colorScheme.primary else Color.White,
                    )
                }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (hasPermission) {
                CameraScanner(
                    torchOn = torchOn,
                    onResult = { raw -> deliver(raw) },
                    modifier = Modifier.fillMaxSize(),
                )
                ScanOverlay(Modifier.fillMaxSize())
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "对准电脑端 ZCode 显示的二维码",
                        color = Color.White.copy(alpha = 0.85f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = { pasteAndConnect(context, { deliver(it) }) }) {
                        Text(
                            "扫不出来？粘贴链接连接",
                            color = Color.White.copy(alpha = 0.6f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            } else {
                PermissionMissing(
                    showSettings = permissionDeniedBefore,
                    onRetry = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                )
            }
        }
    }
}

private fun pasteAndConnect(context: Context, deliver: (String) -> Unit) {
    val text = runCatching {
        context.getSystemService(ClipboardManager::class.java)
            ?.primaryClip?.getItemAt(0)?.text?.toString()
    }.getOrNull()?.trim().orEmpty()
    if (text.isEmpty()) {
        Toast.makeText(context, "剪贴板是空的，先在别处复制链接", Toast.LENGTH_SHORT).show()
    } else {
        deliver(text)
    }
}

@Composable
private fun PermissionMissing(showSettings: Boolean, onRetry: () -> Unit) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "需要相机权限才能扫码",
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "ZPocket 仅使用相机识别电脑端显示的二维码，\n不会拍摄或保存任何照片。",
            color = Color.White.copy(alpha = 0.7f),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        Button(onClick = onRetry) {
            Text(if (showSettings) "重新授权" else "授权相机")
        }
        if (showSettings) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = {
                runCatching {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null),
                        )
                    )
                }
            }) {
                Text("打开系统设置", color = Color.White.copy(alpha = 0.7f))
            }
        }
    }
}

@Composable
private fun CameraScanner(torchOn: Boolean, onResult: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }
    val executor = remember { Executors.newSingleThreadExecutor() }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    LaunchedEffect(Unit) {
        val p = cameraProvider(context)
        // 离开扫码页后才拿到 provider 的情况：不再绑定相机，避免摄像头常开
        coroutineContext.ensureActive()
        provider = p
        val preview = Preview.Builder()
            .build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }
        // 分析分辨率尽量高：拍电脑屏幕上的二维码更容易解出
        val resolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    android.util.Size(1920, 1440),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                )
            )
            .build()
        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(resolutionSelector)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        analysis.setAnalyzer(executor, QrAnalyzer(onResult))
        camera = p.bindToLifecycle(
            lifecycleOwner,
            CameraSelector.DEFAULT_BACK_CAMERA,
            preview,
            analysis,
        )
    }

    LaunchedEffect(torchOn) {
        camera?.cameraControl?.enableTorch(torchOn)
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { provider?.unbindAll() }
            executor.shutdown()
        }
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}

private suspend fun cameraProvider(context: Context): ProcessCameraProvider =
    suspendCancellableCoroutine { cont ->
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                try {
                    cont.resume(future.get())
                } catch (t: Throwable) {
                    cont.resumeWithException(t)
                }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

/** 逐帧取亮度平面交给 [QrDecoder] 识别。 */
private class QrAnalyzer(private val onResult: (String) -> Unit) : ImageAnalysis.Analyzer {

    override fun analyze(image: ImageProxy) {
        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)

            val width = image.width
            val height = image.height
            val luminance: ByteArray =
                if (plane.pixelStride == 1) {
                    bytes
                } else {
                    ByteArray(width * height).also { out ->
                        var rowStart = 0
                        var outIndex = 0
                        for (row in 0 until height) {
                            var colStart = rowStart
                            for (col in 0 until width) {
                                out[outIndex++] = bytes[colStart]
                                colStart += plane.pixelStride
                            }
                            rowStart += plane.rowStride
                        }
                    }
                }

            val source = PlanarYUVLuminanceSource(luminance, width, height, 0, 0, width, height, false)
            // 先按相机报告的方向转正（clockwise(rotation) == counterclockwise(360-rotation)）
            val preRotations = when (image.imageInfo.rotationDegrees) {
                90 -> 3
                180 -> 2
                270 -> 1
                else -> 0
            }
            QrDecoder.decode(source, preRotations)?.let(onResult)
        } catch (_: Exception) {
            // 个别帧数据异常，直接丢弃
        } finally {
            image.close()
        }
    }
}

@Composable
private fun ScanOverlay(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val side = size.minDimension * 0.62f
        val left = (size.width - side) / 2f
        val top = (size.height - side) / 2f * 0.85f
        val rect = Rect(Offset(left, top), Size(side, side))
        val radius = 28f

        val dim = Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(Rect(Offset.Zero, size))
            addRoundRect(RoundRect(rect, CornerRadius(radius)))
        }
        drawPath(dim, Color.Black.copy(alpha = 0.45f))
        drawRoundRect(
            color = Color.White,
            topLeft = rect.topLeft,
            size = rect.size,
            cornerRadius = CornerRadius(radius),
            style = Stroke(width = 2.dp.toPx()),
        )
    }
}

private fun vibrate(context: Context) {
    val vibrator = context.getSystemService(Vibrator::class.java) ?: return
    vibrator.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE))
}
