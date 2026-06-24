package com.lightagent.ui.screen

import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lightagent.character.CharacterEmotion
import com.lightagent.character.CharacterView
import com.lightagent.live2d.Live2DController
import com.lightagent.live2d.NoOpLive2DController
import com.lightagent.memory.ConversationEntity
import com.lightagent.ui.BackgroundSource
import com.lightagent.ui.BackgroundViewModel
import com.lightagent.ui.ChatMessage
import com.lightagent.ui.CharacterPackViewModel
import com.lightagent.ui.ChatViewModel
import com.lightagent.ui.ReminderViewModel
import com.lightagent.ui.music.MusicScreen
import com.lightagent.ui.music.MusicViewModel
import com.lightagent.ui.theme.*
import kotlinx.coroutines.launch

// ── 页面路由枚举 ─────────────────────────────────────────────────────────────
private enum class Screen { Chat, Reminder, Settings, Music }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun ChatScreen(
    chatViewModel        : ChatViewModel           = viewModel(),
    reminderViewModel    : ReminderViewModel       = viewModel(),
    backgroundViewModel  : BackgroundViewModel     = viewModel(),
    characterPackViewModel: CharacterPackViewModel = viewModel(),
    musicViewModel       : MusicViewModel          = viewModel(),
    live2DController     : Live2DController        = remember { NoOpLive2DController() }
) {
    val drawerState    = rememberDrawerState(DrawerValue.Closed)
    val scope          = rememberCoroutineScope()
    var showBgSheet    by remember { mutableStateOf(false) }
    var showCharSheet  by remember { mutableStateOf(false) }
    var currentScreen  by remember { mutableStateOf(Screen.Chat) }

    val conversations  by chatViewModel.conversations.collectAsState()
    val currentConvId  by chatViewModel.currentConversationId.collectAsState()
    val messages       by chatViewModel.messages.collectAsState()
    val isLoading      by chatViewModel.isLoading.collectAsState()
    val inputText      by chatViewModel.input.collectAsState()
    val currentEmotion by chatViewModel.currentEmotion.collectAsState()
    val isTalking      by chatViewModel.isTalking.collectAsState()
    val ttsEnabled      by chatViewModel.ttsEnabled.collectAsState()
    val currentPack    by characterPackViewModel.currentPack.collectAsState()
    val listState      = rememberLazyListState()

    // 新消息自动滚底
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    if (showBgSheet) {
        BackgroundSettingsSheet(
            onDismiss   = { showBgSheet = false },
            bgViewModel = backgroundViewModel
        )
    }

    if (showCharSheet) {
        CharacterPackSheet(
            onDismiss = { showCharSheet = false },
            viewModel = characterPackViewModel
        )
    }

    // ── 页面路由 + 跨页面动画 ──────────────────────────────────────────────
    SharedTransitionLayout(modifier = Modifier.fillMaxSize()) {

        AnimatedContent(
            targetState    = currentScreen,
            transitionSpec = {
                when {
                    initialState == Screen.Chat ->
                        (slideInHorizontally(
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness    = Spring.StiffnessMediumLow
                            )
                        ) { it } + fadeIn(tween(220)))
                            .togetherWith(
                                slideOutHorizontally { -it / 3 } + fadeOut(tween(180))
                            )
                    else ->
                        (slideInHorizontally(
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness    = Spring.StiffnessMediumLow
                            )
                        ) { -it } + fadeIn(tween(220)))
                            .togetherWith(
                                slideOutHorizontally { it / 3 } + fadeOut(tween(180))
                            )
                }.using(SizeTransform(clip = false))
            },
            label = "screenTransition"
        ) { screen ->

            when (screen) {

                Screen.Reminder -> ReminderScreen(
                    onBack            = { currentScreen = Screen.Chat },
                    reminderViewModel = reminderViewModel
                )

                Screen.Settings -> LLMSettingsScreen(
                    onBack = { currentScreen = Screen.Chat }
                )

                Screen.Music -> MusicScreen(
                    viewModel       = musicViewModel,
                    onNavigateBack = { currentScreen = Screen.Chat }
                )

                Screen.Chat -> {
                    ModalNavigationDrawer(
                        drawerState   = drawerState,
                        drawerContent = {
                            ConversationDrawer(
                                conversations        = conversations,
                                currentConversationId = currentConvId,
                                onSelectConversation  = { conv ->
                                    chatViewModel.switchConversation(conv)
                                    scope.launch { drawerState.close() }
                                },
                                onNewConversation = {
                                    chatViewModel.createNewConversation()
                                    scope.launch { drawerState.close() }
                                },
                                onDeleteConversation = { conv ->
                                    chatViewModel.deleteConversation(conv)
                                },
                                onOpenReminders = {
                                    currentScreen = Screen.Reminder
                                    scope.launch { drawerState.close() }
                                },
                                onOpenSettings = {
                                    currentScreen = Screen.Settings
                                    scope.launch { drawerState.close() }
                                },
                                onOpenMusic = {
                                    currentScreen = Screen.Music
                                    scope.launch { drawerState.close() }
                                }
                            )
                        }
                    ) {
                        GalGameChatLayout(
                            messages        = messages,
                            isLoading       = isLoading,
                            inputText       = inputText,
                            currentEmotion  = currentEmotion,
                            isTalking       = isTalking,
                            characterPack   = currentPack,
                            conversations   = conversations,
                            currentConvId   = currentConvId,
                            listState       = listState,
                            onOpenDrawer    = { scope.launch { drawerState.open() } },
                            onOpenBgSheet   = { showBgSheet = true },
                            onOpenCharSheet = { showCharSheet = true },
                            onTextChange    = { chatViewModel.updateInput(it) },
                            onSend          = { chatViewModel.send() },
                            ttsEnabled      = ttsEnabled,
                            onToggleTts     = { chatViewModel.toggleTts() }
                        )
                    }
                }
            }
        }
    }
}
