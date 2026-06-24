package com.lightagent.overlay

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * 桌宠全局可见性控制
 *
 * 读：PetCommandCenter.isVisible（App 内和 Service 都能读）
 * 写：show() / hide() / toggle()
 */
object PetCommandCenter {

    var isVisible by mutableStateOf(false)
        private set

    fun show() {
        isVisible = true
    }

    fun hide() {
        isVisible = false
    }

    fun toggle() {
        isVisible = !isVisible
    }
}
