package com.entaiaki.lightagent.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.entaiaki.lightagent.ChatViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val listState = rememberLazyListState()

    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.size - 1)
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("LightAgent Live") },
                actions = {
                    TextButton(onClick = { viewModel.toggleFloatingMode(context) }) {
                        Text(if (uiState.isFloatingMode) "返回" else "桌宠")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(uiState.messages.size) { i ->
                    val msg = uiState.messages[i]
                    MessageBubble(role = msg.role, content = msg.content)
                }
                if (uiState.isLoading) {
                    item { CircularProgressIndicator(modifier = Modifier.padding(8.dp)) }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = uiState.inputText,
                    onValueChange = viewModel::updateInputText,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("输入消息...") },
                    singleLine = true
                )
                Spacer(modifier = Modifier.width(8.dp))
                if (uiState.isSpeaking) {
                    IconButton(onClick = { viewModel.stopSpeaking() }) {
                        Icon(Icons.Default.Stop, contentDescription = "停止播放")
                    }
                } else {
                    IconButton(
                        onClick = { viewModel.sendMessage() },
                        enabled = uiState.inputText.isNotBlank()
                    ) {
                        Icon(Icons.Default.Send, contentDescription = "发送")
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(role: String, content: String) {
    val isUser = role == "user"
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (isUser) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp
    ) {
        Text(
            text = content.ifEmpty { "..." },
            modifier = Modifier.padding(12.dp),
            color = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
