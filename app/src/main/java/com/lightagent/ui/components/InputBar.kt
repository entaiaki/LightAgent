package com.lightagent.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lightagent.ui.theme.*

@Composable
fun InputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    enabled: Boolean = true
) {
    val borderColor by animateColorAsState(
        targetValue = if (value.isNotEmpty()) AccentPurple else GlassBorder,
        label       = "border"
    )

    Row(
        modifier          = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(24.dp))
                .background(
                    Brush.linearGradient(
                        listOf(Color(0x33FFFFFF), Color(0x11FFFFFF))
                    )
                )
                .border(1.dp, borderColor, RoundedCornerShape(24.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            if (value.isEmpty()) {
                Text("和 AI 说点什么...", color = TextHint, fontSize = 14.sp)
            }
            BasicTextField(
                value         = value,
                onValueChange = onValueChange,
                enabled       = enabled,
                textStyle     = LocalTextStyle.current.copy(
                    color    = TextPrimary,
                    fontSize = 14.sp
                ),
                cursorBrush = SolidColor(AccentPurple),
                modifier    = Modifier.fillMaxWidth()
            )
        }

        Spacer(Modifier.width(8.dp))

        IconButton(
            onClick  = onSend,
            enabled  = value.isNotEmpty() && enabled,
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(
                    if (value.isNotEmpty())
                        Brush.linearGradient(listOf(AccentPurple, AccentBlue))
                    else
                        Brush.linearGradient(listOf(GlassBg, GlassBg))
                )
        ) {
            Icon(
                imageVector        = Icons.AutoMirrored.Rounded.Send,
                contentDescription = "发送",
                tint               = if (value.isNotEmpty()) Color.White else TextHint
            )
        }
    }
}
