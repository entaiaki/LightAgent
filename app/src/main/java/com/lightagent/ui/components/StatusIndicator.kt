package com.lightagent.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lightagent.agent.ChatState
import com.lightagent.ui.theme.*

@Composable
fun StatusIndicator(state: ChatState) {

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")

    val scale by infiniteTransition.animateFloat(
        initialValue  = 1f,
        targetValue   = 1.4f,
        animationSpec = infiniteRepeatable(
            animation  = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    val (color, label) = when (state) {
        is ChatState.Thinking    -> StatusThinking to "🧠 思考中"
        is ChatState.CallingTool -> StatusTool     to "🧰 执行工具"
        is ChatState.Error       -> StatusTool     to "❌ 出错了"
        else                     -> StatusIdle     to "💫 就绪"
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .scale(if (state !is ChatState.Idle) scale else 1f)
                .background(color, CircleShape)
        )
        Spacer(Modifier.width(6.dp))
        Text(text = label, color = color, fontSize = 12.sp)
    }
}
