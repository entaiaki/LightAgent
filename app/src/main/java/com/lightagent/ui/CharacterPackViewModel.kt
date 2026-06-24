package com.lightagent.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.lightagent.character.CharacterPack
import com.lightagent.character.CharacterPackManager
import com.lightagent.character.CharacterPackRegistry
import com.lightagent.character.CharacterStateHolder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class CharacterPackViewModel(application: Application) : AndroidViewModel(application) {

    /** 所有可用角色包 */
    val packs: List<CharacterPack> = CharacterPackRegistry.packs

    /** 当前选中的角色包 */
    private val _currentPack = MutableStateFlow(
        CharacterPackManager.load(application)
    )
    val currentPack: StateFlow<CharacterPack> = _currentPack.asStateFlow()

    /** 切换角色包并持久化 */
    fun selectPack(pack: CharacterPack) {
        _currentPack.value = pack
        CharacterStateHolder.role = pack.id
        CharacterPackManager.save(getApplication(), pack)
    }
}
