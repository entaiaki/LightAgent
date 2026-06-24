package com.lightagent.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.lightagent.agent.PlannerAgent
import com.lightagent.character.CharacterStateHolder
import com.lightagent.ui.theme.*
import kotlinx.coroutines.launch

data class OverlayChatMessage(
    val role: String,
    val content: String
)

@Composable
fun ChatOverlay(
    onClose: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    var input by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }

    val messages = remember {
        mutableStateListOf(
            OverlayChatMessage(
                role = "assistant",
                content = "哼，终于想起点我了？说吧，找我干嘛～"
            )
        )
    }

    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.38f)),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .heightIn(min = 420.dp, max = 620.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = DeepNavy
            )
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // ── 顶栏 ──────────────────────────────────────────────
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "天爱星",
                        color = TextPrimary,
                        style = MaterialTheme.typography.titleMedium
                    )

                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "关闭聊天框",
                            tint = TextPrimary
                        )
                    }
                }

                HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

                // ── 消息列表 ──────────────────────────────────────────
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(messages) { msg ->
                        OverlayMessageBubble(msg)
                    }

                    if (isLoading) {
                        item {
                            OverlayMessageBubble(
                                OverlayChatMessage(
                                    role = "assistant",
                                    content = "让我想想……"
                                )
                            )
                        }
                    }
                }

                // ── 输入栏 ───────────────────────────────────────────
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        placeholder = {
                            Text("和天爱星说点什么……")
                        },
                        singleLine = false,
                        maxLines = 3,
                        textStyle = LocalTextStyle.current.copy(
                            color = TextPrimary
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedBorderColor = AccentPurple,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.22f),
                            cursorColor = AccentPurple
                        )
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    IconButton(
                        onClick = {
                            val text = input.trim()
                            if (text.isEmpty() || isLoading) return@IconButton

                            input = ""
                            messages.add(
                                OverlayChatMessage(role = "user", content = text)
                            )

                            scope.launch {
                                isLoading = true
                                try {
                                    val history = messages
                                        .dropLast(1)
                                        .filter { it.content.isNotBlank() }
                                        .map {
                                            mapOf("role" to it.role, "content" to it.content)
                                        }

                                    val agent = PlannerAgent.create(
                                        context = context.applicationContext,
                                        history = history
                                    )

                                    val result = agent.chatWithEmotion(text)

                                    CharacterStateHolder.emotion = result.emotion

                                    messages.add(
                                        OverlayChatMessage(
                                            role = "assistant",
                                            content = result.text
                                        )
                                    )
                                } catch (e: Exception) {
                                    messages.add(
                                        OverlayChatMessage(
                                            role = "assistant",
                                            content = "出错了：${e.message ?: "未知错误"}"
                                        )
                                    )
                                } finally {
                                    isLoading = false
                                }
                            }
                        },
                        enabled = !isLoading
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "发送",
                            tint = if (isLoading) {
                                TextPrimary.copy(alpha = 0.35f)
                            } else {
                                AccentPurple
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OverlayMessageBubble(
    message: OverlayChatMessage
) {
    val isUser = message.role == "user"

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 280.dp),
            shape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 18.dp,
                bottomStart = if (isUser) 18.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 18.dp
            ),
            color = if (isUser) {
                AccentPurple
            } else {
                Color.White.copy(alpha = 0.10f)
            }
        ) {
            Text(
                text = message.content,
                color = TextPrimary,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}
