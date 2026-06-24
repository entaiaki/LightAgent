package com.lightagent.character

import android.content.Context
import android.content.SharedPreferences

/**
 * 当前角色包持久化（SharedPreferences）
 * 与 LLMConfigStore 同一思路，保持一致性
 */
object CharacterPackManager {

    private const val PREFS_NAME  = "character_pack"
    private const val KEY_PACK_ID = "selected_pack_id"

    fun save(context: Context, pack: CharacterPack) {
        prefs(context).edit()
            .putString(KEY_PACK_ID, pack.id)
            .apply()
    }

    fun load(context: Context): CharacterPack {
        val id = prefs(context).getString(KEY_PACK_ID, CharacterPackRegistry.default.id)
            ?: CharacterPackRegistry.default.id
        return CharacterPackRegistry.findById(id)
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
