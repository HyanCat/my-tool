package com.hyancat.mytool

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.arthenica.ffmpegkit.FFmpegKit
import java.io.File
import java.io.FileOutputStream
import android.widget.Toast
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch




class MainViewModel : ViewModel() {
    var videoUri by mutableStateOf<Uri?>(null)
    var audioFile by mutableStateOf<File?>(null)
    var isProcessing by mutableStateOf(false)

    fun reset() {
        videoUri = null
        audioFile = null
        isProcessing = false
    }
}

fun getRealPathFromUri(context: android.content.Context, uri: Uri): String? {
    val contentResolver = context.contentResolver
    val fileName = uri.path?.substringAfterLast("/") ?: "temp_video.mp4"
    val tempFile = File(context.cacheDir, fileName)

    return try {
        contentResolver.openInputStream(uri)?.use { inputStream ->
            FileOutputStream(tempFile).use { outputStream ->
                inputStream.copyTo(outputStream)
            }
            tempFile.absolutePath
        } ?: tempFile.absolutePath
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

// 清理文件名中的特殊字符
fun sanitizeFileName(fileName: String): String {
    // 定义需要替换的特殊字符
    val specialChars = "[\\\\/:*?\"<>|]".toRegex()
    return fileName.replace(specialChars, "_")
}

@Composable
fun VideoAudioExtractorApp(viewModel: MainViewModel = viewModel()) {
    val context = LocalContext.current
    var hasPermission by remember { mutableStateOf(false) }
    val videoPlayer = remember { ExoPlayer.Builder(context).build() }
    val audioPlayer = remember { MediaPlayer() }
    var isAudioPlaying by remember { mutableStateOf(false) }
    var currentPosition by remember { mutableStateOf(0) } // 当前播放位置 (ms)
    var duration by remember { mutableStateOf(0) } // 总时长 (ms)
    val coroutineScope = rememberCoroutineScope()

    // 请求权限
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasPermission = isGranted
    }

    // 选择视频
    val videoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        viewModel.reset() // 重置 ViewModel 状态
        audioPlayer.reset() // 重置音频播放器
        isAudioPlaying = false
        currentPosition = 0
        duration = 0
        viewModel.videoUri = uri // 设置新视频
        uri?.let {
            videoPlayer.setMediaItem(MediaItem.fromUri(it)) // 更新视频播放器内容
            videoPlayer.prepare()
        }
    }

    // 检查权限
    LaunchedEffect(Unit) {
        hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    // 更新音频播放进度
    LaunchedEffect(isAudioPlaying) {
        while (isAudioPlaying && audioPlayer.isPlaying) {
            currentPosition = audioPlayer.currentPosition
            duration = audioPlayer.duration
            delay(1000) // 每秒更新一次
        }
    }

    // 定义颜色
    val primaryColor = Color(0xFF4285f4) // 蓝色
    val secondaryColor = Color(0xFF03DAC5) // 青色
    val backgroundColor = Color(0xFFF5F5F5) // 浅灰色

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = backgroundColor
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // 选择视频按钮
            Button(
                onClick = { videoPicker.launch("video/*") },
                modifier = Modifier
                    .width(200.dp)
                    .height(60.dp),
                colors = ButtonDefaults.buttonColors(containerColor = primaryColor)
            ) {
                Text("选择视频", color = Color.White, style = MaterialTheme.typography.titleMedium)
            }

            // 视频预览
            viewModel.videoUri?.let { uri ->
                AndroidView(
                    factory = {
                        PlayerView(context).apply {
                            this.player = videoPlayer
                            videoPlayer.setMediaItem(MediaItem.fromUri(uri))
                            videoPlayer.prepare()
                        }
                    },
                    modifier = Modifier
                        .height(200.dp)
                        .fillMaxWidth()
                        .background(Color.Black)
                )
            }

            // 提取音频按钮
            Button(
                onClick = {
                    viewModel.isProcessing = true
                    viewModel.videoUri?.let { uri ->
                        val inputPath = getRealPathFromUri(context, uri) ?: return@let
                        val rawFileName = uri.path?.substringAfterLast("/")?.removeSuffix(".mp4") ?: "audio"
                        val sanitizedFileName = sanitizeFileName(rawFileName) // 清理特殊字符
//                        val outputDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
                        val outputDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                        val outputFile = File(outputDir, "$sanitizedFileName-提取.mp3")
                        val outputPath = outputFile.absolutePath
                        val command = "-y -i \"$inputPath\" -vn -acodec mp3 \"$outputPath\""

                        FFmpegKit.executeAsync(command) { session ->
                            coroutineScope.launch(Dispatchers.Main) {
                                if (session.returnCode.isValueSuccess) {
                                    viewModel.audioFile = outputFile
                                    Toast.makeText(context, "音频提取完成！", Toast.LENGTH_SHORT).show()
                                } else {
                                    println("FFmpeg error: ${session.allLogsAsString}")
                                    Toast.makeText(context, "音频提取失败", Toast.LENGTH_SHORT).show()
                                }
                                viewModel.isProcessing = false
                            }
                        }
                    }
                },
                enabled = viewModel.videoUri != null && !viewModel.isProcessing,
                modifier = Modifier
                    .width(200.dp)
                    .height(60.dp),
                colors = ButtonDefaults.buttonColors(containerColor = primaryColor)
            ) {
                Text("提取音频", color = Color.White, style = MaterialTheme.typography.titleMedium)
            }

            // 音频播放和文件路径
            viewModel.audioFile?.let { file ->
                // 简易音频播放控件和进度条
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Button(
                            onClick = {
                                if (isAudioPlaying) {
                                    audioPlayer.pause()
                                } else {
                                    if (!audioPlayer.isPlaying) {
                                        audioPlayer.reset()
                                        audioPlayer.setDataSource(file.absolutePath)
                                        audioPlayer.prepare()
                                    }
                                    audioPlayer.start()
                                }
                                isAudioPlaying = !isAudioPlaying
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = secondaryColor)
                        ) {
                            Text(if (isAudioPlaying) "暂停" else "播放", color = Color.White)
                        }
                    }
//                    // 时间进度条
//                    LinearProgressIndicator(
//                        progress = if (duration > 0) currentPosition.toFloat() / duration else 0f,
//                        modifier = Modifier
//                            .fillMaxWidth(0.8f)
//                            .padding(top = 8.dp),
//                        color = secondaryColor,
////                        backgroundColor = Color.LightGray
//                    )
                    // 可拖动的进度条
                    Slider(
                        value = currentPosition.toFloat(),
                        onValueChange = { newValue ->
                            currentPosition = newValue.toInt()
                            audioPlayer.seekTo(newValue.toInt())
                        },
                        valueRange = 0f..duration.toFloat(),
                        modifier = Modifier
                            .fillMaxWidth(0.8f)
                            .padding(top = 8.dp),
                        colors = SliderDefaults.colors(
                            thumbColor = secondaryColor,
                            activeTrackColor = secondaryColor,
                            inactiveTrackColor = Color.LightGray
                        )
                    )
                    Text(
                        text = "${formatTime(currentPosition)} / ${formatTime(duration)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                // 文件路径
                Text(
                    text = "已保存至: ${file.absolutePath}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            // 重置按钮
            Button(
                onClick = {
                    viewModel.reset()
                    audioPlayer.reset()
                    isAudioPlaying = false
                    currentPosition = 0
                    duration = 0
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF5350)) // 红色
            ) {
                Text("重置", color = Color.White)
            }

            if (viewModel.isProcessing) {
                CircularProgressIndicator(color = primaryColor)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            videoPlayer.release()
            audioPlayer.release()
        }
    }
}

// 将毫秒格式化为 mm:ss
fun formatTime(ms: Int): String {
    val seconds = ms / 1000
    val minutes = seconds / 60
    val remainingSeconds = seconds % 60
    return String.format("%02d:%02d", minutes, remainingSeconds)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                VideoAudioExtractorApp()
            }
        }
    }
}