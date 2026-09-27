package com.example.presentation.components

import android.graphics.ImageDecoder
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.view.TextureView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.core.model.StickerType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun AnimatedStickerView(
    uriString: String?,
    stickerType: StickerType,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var drawable by remember(uriString) { mutableStateOf<Drawable?>(null) }
    var staticBitmap by remember(uriString) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var isLoading by remember(uriString) { mutableStateOf(true) }
    var hasError by remember(uriString) { mutableStateOf(false) }

    val isWebm = stickerType == StickerType.WEBM || uriString?.endsWith(".webm", ignoreCase = true) == true

    if (isWebm && !uriString.isNullOrBlank()) {
        // Video WebM player
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            AndroidView(
                factory = { ctx ->
                    TextureView(ctx).apply {
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            var mediaPlayer: android.media.MediaPlayer? = null

                            override fun onSurfaceTextureAvailable(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {
                                try {
                                    mediaPlayer = android.media.MediaPlayer().apply {
                                        setSurface(android.view.Surface(surface))
                                        val uri = if (uriString.startsWith("content://") || uriString.startsWith("file://")) {
                                            Uri.parse(uriString)
                                        } else {
                                            Uri.fromFile(File(uriString))
                                        }
                                        setDataSource(ctx, uri)
                                        isLooping = true
                                        setVolume(0f, 0f) // stickers have no audio
                                        prepareAsync()
                                        setOnPreparedListener { start() }
                                    }
                                } catch (_: Exception) {
                                    hasError = true
                                }
                            }

                            override fun onSurfaceTextureSizeChanged(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {}
                            override fun onSurfaceTextureDestroyed(surface: android.graphics.SurfaceTexture): Boolean {
                                mediaPlayer?.release()
                                mediaPlayer = null
                                return true
                            }
                            override fun onSurfaceTextureUpdated(surface: android.graphics.SurfaceTexture) {}
                        }
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }
        return
    }

    LaunchedEffect(uriString) {
        if (uriString.isNullOrBlank()) {
            isLoading = false
            hasError = true
            return@LaunchedEffect
        }

        withContext(Dispatchers.IO) {
            try {
                val parsedUri = if (uriString.startsWith("content://") || uriString.startsWith("file://")) {
                    Uri.parse(uriString)
                } else {
                    Uri.fromFile(File(uriString))
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val source = if (parsedUri.scheme == "file" || parsedUri.path?.startsWith("/") == true) {
                        val path = parsedUri.path ?: uriString
                        ImageDecoder.createSource(File(path))
                    } else {
                        ImageDecoder.createSource(context.contentResolver, parsedUri)
                    }
                    val decoded = ImageDecoder.decodeDrawable(source)
                    if (decoded is Animatable) {
                        decoded.start()
                    }
                    drawable = decoded
                } else {
                    val stream = context.contentResolver.openInputStream(parsedUri)
                    val bmp = android.graphics.BitmapFactory.decodeStream(stream)
                    staticBitmap = bmp
                }
                isLoading = false
            } catch (e: Exception) {
                e.printStackTrace()
                // Fallback to BitmapFactory
                try {
                    val parsedUri = Uri.parse(uriString)
                    val stream = context.contentResolver.openInputStream(parsedUri)
                    staticBitmap = android.graphics.BitmapFactory.decodeStream(stream)
                    isLoading = false
                } catch (_: Exception) {
                    hasError = true
                    isLoading = false
                }
            }
        }
    }

    Box(
        modifier = modifier.background(Color.Transparent),
        contentAlignment = Alignment.Center
    ) {
        when {
            isLoading -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            hasError -> {
                Icon(
                    imageVector = Icons.Default.BrokenImage,
                    contentDescription = "تعذر تحميل الملصق",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(36.dp)
                )
            }
            drawable != null -> {
                val d = drawable!!
                AndroidView(
                    factory = { ctx ->
                        android.widget.ImageView(ctx).apply {
                            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                            setImageDrawable(d)
                            if (d is Animatable && !d.isRunning) {
                                d.start()
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
            staticBitmap != null -> {
                Image(
                    bitmap = staticBitmap!!.asImageBitmap(),
                    contentDescription = "ملصق",
                    modifier = Modifier.fillMaxSize()
                )
            }
            else -> {
                Icon(
                    imageVector = Icons.Default.Image,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(36.dp)
                )
            }
        }
    }
}
