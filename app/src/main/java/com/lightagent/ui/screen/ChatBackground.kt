package com.lightagent.ui.screen

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.lightagent.ui.BackgroundSource
import com.lightagent.ui.theme.AccentBlue
import com.lightagent.ui.theme.AccentPurple

/**
 * 聊天背景层 — v3.1
 * Asset/Custom 来源渲染图片 + 暗色蒙层，
 * SolidColor / 默认 → 纵向渐变 + 装饰光晕。
 */
@Composable
fun ChatBackground(
    source: BackgroundSource
) {
    when (source) {
        is BackgroundSource.Asset -> AssetBackground(fileName = source.fileName)
        is BackgroundSource.Custom -> CustomBackground(uri = source.uri)
        is BackgroundSource.SolidColor -> DefaultGradientBackground()
    }
}

// ── Asset 来源：从 assets/backgrounds/ 解码 ──────────────────────────────────
@Composable
private fun AssetBackground(fileName: String) {
    val context = LocalContext.current
    val bitmap = remember(fileName) {
        runCatching {
            context.assets.open("backgrounds/$fileName").use { stream ->
                BitmapFactory.decodeStream(stream)
            }
        }.getOrNull()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            // 暗色蒙层，确保文字可读
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x99000000))
            )
        } else {
            DefaultGradientBackground()
        }
    }
}

// ── Custom 来源：Coil 异步加载本地 URI ──────────────────────────────────────
@Composable
private fun CustomBackground(uri: android.net.Uri) {
    Box(modifier = Modifier.fillMaxSize()) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(uri)
                .crossfade(true)
                .build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        // 暗色蒙层
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x99000000))
        )
    }
}

// ── 默认渐变背景 + 装饰光晕 ────────────────────────────────────────────────
@Composable
private fun DefaultGradientBackground() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f   to Color(0xFF0A0E1A),
                    0.4f to Color(0xFF1A0A2E),
                    0.75f to Color(0xFF0D1B2A),
                    1f   to Color(0xFF0A0E1A)
                )
            )
    ) {
        // 右上角装饰光晕
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(220.dp)
                .offset(x = 60.dp, y = (-40).dp)
                .blur(80.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            AccentPurple.copy(alpha = 0.25f),
                            Color.Transparent
                        )
                    ),
                    shape = RoundedCornerShape(50)
                )
        )
        // 左下角装饰光晕
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .size(180.dp)
                .offset(x = (-40).dp, y = 40.dp)
                .blur(70.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            AccentBlue.copy(alpha = 0.20f),
                            Color.Transparent
                        )
                    ),
                    shape = RoundedCornerShape(50)
                )
        )
    }
}
