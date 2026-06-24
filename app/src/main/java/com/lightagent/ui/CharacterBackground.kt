package com.lightagent.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import com.lightagent.character.CharacterEmotion
import com.lightagent.character.CharacterPackRegistry
import com.lightagent.character.CharacterView

/**
 * 角色立绘背景层
 *
 * 从 assets/characters/{pack.folder}/{emotion}.png 动态加载，
 * 不依赖 R.drawable，新增角色只需放图片 + 注册 CharacterPack。
 *
 * Usage:
 *   CharacterBackground(role = "tianaixing", emotion = CharacterStateHolder.emotion)
 *
 * @param role         角色 ID（如 "tianaixing"），映射到 CharacterPackRegistry
 * @param emotion      当前情绪
 * @param modifier     透传给 CharacterView
 * @param isTalking    说话脉冲（默认关闭，Chat 上下文可传入）
 * @param contentScale 缩放模式（默认 Fill，铺满)
 */
@Composable
fun CharacterBackground(
    role         : String,
    emotion      : CharacterEmotion,
    modifier     : Modifier    = Modifier,
    isTalking    : Boolean     = false,
    contentScale : ContentScale = ContentScale.Crop
) {
    val pack = CharacterPackRegistry.findById(role)

    CharacterView(
        emotion      = emotion,
        pack         = pack,
        modifier     = modifier.fillMaxSize(),
        isTalking    = isTalking,
        contentScale = contentScale
    )
}
