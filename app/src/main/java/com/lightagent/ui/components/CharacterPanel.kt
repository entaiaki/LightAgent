package com.lightagent.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.lightagent.R
import com.lightagent.agent.ChatState

@Composable
fun CharacterPanel(
    state: ChatState,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "float")

    val offsetY by infiniteTransition.animateFloat(
        initialValue  = 0f,
        targetValue   = -12f,
        animationSpec = infiniteRepeatable(
            animation  = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "offsetY"
    )

    val alpha by animateFloatAsState(
        targetValue   = if (state is ChatState.Thinking) 0.6f else 1f,
        animationSpec = tween(400),
        label         = "alpha"
    )

    Box(
        modifier          = modifier.fillMaxWidth(),
        contentAlignment  = Alignment.Center
    ) {
        Image(
            painter           = painterResource(id = R.drawable.character_default),
            contentDescription = "Agent",
            modifier          = Modifier
                .height(200.dp)
                .alpha(alpha)
                .graphicsLayer { translationY = offsetY }
        )
    }
}
