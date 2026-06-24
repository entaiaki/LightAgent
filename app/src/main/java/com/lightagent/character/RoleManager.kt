package com.lightagent.character

/**
 * 角色切换管理器
 *
 * 委托给 CharacterPackRegistry，保持数据定义在注册表一处。
 * 新增角色只需在 CharacterPackRegistry.packs 里加一行。
 */
object RoleManager {

    /** 所有可用角色 ID 列表 */
    val roles: List<String>
        get() = CharacterPackRegistry.packs.map { it.id }

    /** 切换到指定角色 */
    fun switch(roleId: String) {
        val exists = CharacterPackRegistry.packs.any { it.id == roleId }
        if (exists) {
            CharacterStateHolder.role = roleId
        }
    }
}
