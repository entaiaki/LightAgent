package com.lightagent.character

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 天爱星立绘组件 — v4.0 多角色包版本
 *
 * 图片来源从 R.drawable 改为 assets/characters/{pack.folder}/{emotion}.png
 * 好处：添加新角色无需重新编译，只要放图片 + 注册 CharacterPack 即可
 *
 * 动画（呼吸浮动、说话脉冲）由外层 GalGameChatLayout 统一驱动。
 */
@Composable
fun CharacterView(
    emotion      : CharacterEmotion,
    pack         : CharacterPack    = CharacterPackRegistry.default,
    modifier     : Modifier         = Modifier,
    isTalking    : Boolean          = false,
    contentScale : ContentScale     = ContentScale.Fit
) {
    val context = LocalContext.current

    // 用 pack + emotion 作为 key，切换时重新解码
    val bitmap = remember(pack.id, emotion) {
        runCatching {
            context.assets.open(pack.assetPath(emotion)).use { stream ->
                BitmapFactory.decodeStream(stream)
            }
        }.getOrNull()
    }

    if (bitmap != null) {
        Image(
            bitmap             = bitmap.asImageBitmap(),
            contentDescription = "${pack.name} - ${emotion.label}",
            modifier           = modifier,
            contentScale       = contentScale,
            alignment          = Alignment.BottomCenter
        )
    } else {
        // assets 中找不到图片时显示占位，避免白屏
        CharacterFallbackPlaceholder(
            pack     = pack,
            emotion  = emotion,
            modifier = modifier
        )
    }
}

/**
 * 找不到立绘时的兜底占位
 * 显示角色名 + 情绪 emoji，确保 UI 不崩溃
 */
@Composable
private fun CharacterFallbackPlaceholder(
    pack    : CharacterPack,
    emotion : CharacterEmotion,
    modifier: Modifier = Modifier
) {
    val emoji = when (emotion) {
        CharacterEmotion.IDLE       -> "\uD83D\uDE10"
        CharacterEmotion.HAPPY      -> "\uD83D\uDE0A"
        CharacterEmotion.THINKING   -> "\uD83E\uDD14"
        CharacterEmotion.SAD        -> "\uD83D\uDE22"
        CharacterEmotion.ANGRY      -> "\uD83D\uDE24"
        CharacterEmotion.SLEEPING   -> "\uD83D\uDE34"
        CharacterEmotion.SOBBING    -> "\uD83E\uDD7A"
        CharacterEmotion.CRYING     -> "\uD83D\uDE2D"
        CharacterEmotion.DEPRESSED  -> "\uD83D\uDE1E"
        CharacterEmotion.DISTRESSED -> "\uD83D\uDE30"
        CharacterEmotion.DROWSY     -> "\uD83D\uDE2A"
        CharacterEmotion.SWEATING   -> "\uD83D\uDE05"
        CharacterEmotion.PAINED     -> "\uD83D\uDE16"
        CharacterEmotion.DISGUSTED  -> "\uD83D\uDE12"
        CharacterEmotion.SERIOUS    -> "\uD83E\uDDD0"
        CharacterEmotion.WINK       -> "\uD83D\uDE09"
    }

    Box(
        modifier         = modifier
            .background(Color(0xFF1A1A2E), RoundedCornerShape(16.dp))
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(emoji, fontSize = 56.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                text     = pack.name,
                color    = Color.White.copy(alpha = 0.7f),
                fontSize = 13.sp
            )
            Text(
                text     = emotion.label,
                color    = Color.White.copy(alpha = 0.4f),
                fontSize = 11.sp
            )
        }
    }
}
