package com.lightagent.character

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 立绘图片还没准备好时的占位组件
 * 显示情绪名称和 emoji，用于验证切换逻辑
 */
@Composable
fun CharacterPlaceholder(
    emotion: CharacterEmotion,
    modifier: Modifier = Modifier
) {
    val bgColor = when (emotion) {
        CharacterEmotion.IDLE       -> Color(0xFF2A2A4A)
        CharacterEmotion.HAPPY      -> Color(0xFF2A4A2A)
        CharacterEmotion.THINKING   -> Color(0xFF2A3A4A)
        CharacterEmotion.SAD        -> Color(0xFF3A4A6A)
        CharacterEmotion.ANGRY      -> Color(0xFF4A2A2A)
        CharacterEmotion.SLEEPING   -> Color(0xFF1A1A2A)
        CharacterEmotion.SOBBING    -> Color(0xFF3A4A6A)
        CharacterEmotion.CRYING     -> Color(0xFF2A4A6A)
        CharacterEmotion.DEPRESSED  -> Color(0xFF3A3A5A)
        CharacterEmotion.DISTRESSED -> Color(0xFF4A3A3A)
        CharacterEmotion.DROWSY     -> Color(0xFF2A2A3A)
        CharacterEmotion.SWEATING   -> Color(0xFF4A4A3A)
        CharacterEmotion.PAINED     -> Color(0xFF4A2A3A)
        CharacterEmotion.DISGUSTED  -> Color(0xFF3A4A2A)
        CharacterEmotion.SERIOUS    -> Color(0xFF2A2A4A)
        CharacterEmotion.WINK       -> Color(0xFF2A4A2A)
    }

    val emoji = when (emotion) {
        CharacterEmotion.IDLE       -> "😐"
        CharacterEmotion.HAPPY      -> "😊"
        CharacterEmotion.THINKING   -> "🤔"
        CharacterEmotion.SAD        -> "😢"
        CharacterEmotion.ANGRY      -> "😤"
        CharacterEmotion.SLEEPING   -> "😴"
        CharacterEmotion.SOBBING    -> "🥺"
        CharacterEmotion.CRYING     -> "😭"
        CharacterEmotion.DEPRESSED  -> "😞"
        CharacterEmotion.DISTRESSED -> "😣"
        CharacterEmotion.DROWSY     -> "🥱"
        CharacterEmotion.SWEATING   -> "😅"
        CharacterEmotion.PAINED     -> "😖"
        CharacterEmotion.DISGUSTED  -> "😒"
        CharacterEmotion.SERIOUS    -> "🧐"
        CharacterEmotion.WINK       -> "😉"
    }

    Box(
        modifier = modifier
            .padding(bottom = 32.dp)
            .background(bgColor, RoundedCornerShape(24.dp))
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(emoji, fontSize = 64.sp)
            Spacer(Modifier.height(8.dp))
            Text(
                text = "天爱星\n${emotion.label}",
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "立绘就位\n（${emotion.assetName}.png）",
                color = Color.White.copy(alpha = 0.4f),
                fontSize = 11.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}
