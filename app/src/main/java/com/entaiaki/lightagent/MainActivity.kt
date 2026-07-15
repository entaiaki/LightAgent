package com.entaiaki.lightagent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.entaiaki.lightagent.music.MusicPlayerController
import com.entaiaki.lightagent.ui.ChatScreen
import com.entaiaki.lightagent.ui.MusicPlayerScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: ChatViewModel by viewModels()
    private lateinit var musicController: MusicPlayerController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        musicController = MusicPlayerController(this)

        lifecycleScope.launch(Dispatchers.IO) {
            viewModel.initTTS(this@MainActivity)
        }

        setContent {
            MaterialTheme {
                MainScreen(
                    viewModel = viewModel,
                    musicController = musicController
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        musicController.release()
    }
}

@Composable
fun MainScreen(
    viewModel: ChatViewModel,
    musicController: MusicPlayerController
) {
    var selectedTab by remember { mutableIntStateOf(0) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Chat, contentDescription = "聊天") },
                    label = { Text("聊天") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.MusicNote, contentDescription = "音乐") },
                    label = { Text("音乐") }
                )
            }
        }
    ) { innerPadding ->
        when (selectedTab) {
            0 -> ChatScreen(
                viewModel = viewModel,
                modifier = Modifier.padding(innerPadding)
            )
            1 -> MusicPlayerScreen(
                controller = musicController,
                modifier = Modifier.padding(innerPadding)
            )
        }
    }
}
