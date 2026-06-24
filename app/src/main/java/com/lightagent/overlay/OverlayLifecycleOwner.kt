package com.lightagent.overlay

import androidx.lifecycle.*
import androidx.savedstate.*

/**
 * Service 里使用 ComposeView 必须提供 LifecycleOwner
 * 这个类模拟 Activity 的生命周期给 ComposeView 用
 */
class OverlayLifecycleOwner : SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    fun onCreate() {
        savedStateController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    fun onStart()  = lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
    fun onResume() = lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    fun onPause()  = lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
    fun onStop()   = lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
    fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    }
}
