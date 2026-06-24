package com.lightagent.character

/**
 * 角色包定义
 *
 * 图片放在 assets/characters/{folder}/{情绪名小写}.png
 * 例：assets/characters/tianaixing/happy.png
 *
 * @param id        唯一标识（用于持久化）
 * @param name      显示名称
 * @param description 简短介绍
 * @param folder    assets/characters/ 下的子目录名
 * @param previewEmotion 预览时使用的情绪（默认 IDLE）
 * @param supportedEmotions 该套图支持的情绪集合（不支持的回退到 IDLE）
 */
data class CharacterPack(
    val id               : String,
    val name             : String,
    val description      : String,
    val folder           : String,
    val previewEmotion   : CharacterEmotion = CharacterEmotion.IDLE,
    val supportedEmotions: Set<CharacterEmotion> = CharacterEmotion.entries.toSet(),
    val assetExtension   : String = "png"
) {
    /**
     * 获取指定情绪对应的 assets 路径
     * 若该套图不支持此情绪，回退到 IDLE
     */
    fun assetPath(emotion: CharacterEmotion): String {
        val target = if (emotion in supportedEmotions) emotion else CharacterEmotion.IDLE
        return "characters/$folder/${target.name.lowercase()}.$assetExtension"
    }

    /** 预览图 assets 路径 */
    val previewAssetPath: String
        get() = assetPath(previewEmotion)
}

/**
 * 全局角色包注册表
 *
 * 新增角色只需要：
 *   1. 把图片放进 assets/characters/{folder}/
 *   2. 在这里 add 一个 CharacterPack
 *
 * 图片命名规范（16种，全小写）：
 *   idle.png  happy.png  thinking.png  sad.png  angry.png
 *   sleeping.png  sobbing.png  crying.png  depressed.png
 *   distressed.png  drowsy.png  sweating.png  pained.png
 *   disgusted.png  serious.png  wink.png
 */
object CharacterPackRegistry {

    val packs: List<CharacterPack> = listOf(

        // ── 天爱星（默认，原版）──────────────────────────────────────────
        CharacterPack(
            id          = "tianaixing",
            name        = "天爱星",
            description = "来自《败犬女主太多了》\n聪明、偶尔傲娇",
            folder      = "tianaixing"
        ),

        // ── 立绘1（完整16情绪）── 测试版已移除 ──────────────────────────
        // CharacterPack(
        //     id          = "illust1",
        //     name        = "立绘1",
        //     description = "立绘包1\n全套16种情绪",
        //     folder      = "illust1",
        //     previewEmotion = CharacterEmotion.HAPPY,
        //     assetExtension = "jpg"
        // ),

        // ── 立绘2（12情绪，缺 thinking/sleeping/sweating/pained）─────────
        CharacterPack(
            id          = "illust2",
            name        = "立绘2",
            description = "立绘包2\n12种情绪",
            folder      = "illust2",
            previewEmotion = CharacterEmotion.HAPPY,
            assetExtension = "jpg",
            supportedEmotions = setOf(
                CharacterEmotion.IDLE, CharacterEmotion.HAPPY,
                CharacterEmotion.SAD, CharacterEmotion.ANGRY,
                CharacterEmotion.SOBBING, CharacterEmotion.CRYING,
                CharacterEmotion.DROWSY, CharacterEmotion.DEPRESSED,
                CharacterEmotion.DISGUSTED, CharacterEmotion.DISTRESSED,
                CharacterEmotion.SERIOUS, CharacterEmotion.WINK
            )
        ),
    )

    val default: CharacterPack get() = packs.first()

    fun findById(id: String): CharacterPack =
        packs.find { it.id == id } ?: default
}
