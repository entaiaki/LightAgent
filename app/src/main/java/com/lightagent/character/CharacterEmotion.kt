package com.lightagent.character

/**
 * 天爱星的情绪状态（16 种）
 * 对应 res/drawable/tianaixing_*.png
 */
enum class CharacterEmotion(val assetName: String, val label: String) {
    IDLE("tianaixing_idle", "面无表情"),
    HAPPY("tianaixing_happy", "微笑"),
    THINKING("tianaixing_thinking", "思考"),
    SAD("tianaixing_sad", "伤心"),
    ANGRY("tianaixing_angry", "生气"),
    SLEEPING("tianaixing_sleeping", "睡着"),
    SOBBING("tianaixing_sobbing", "啜泣"),
    CRYING("tianaixing_crying", "大哭"),
    DEPRESSED("tianaixing_depressed", "沮丧"),
    DISTRESSED("tianaixing_distressed", "苦恼"),
    DROWSY("tianaixing_drowsy", "困乏"),
    SWEATING("tianaixing_sweating", "流汗"),
    PAINED("tianaixing_pained", "痛苦"),
    DISGUSTED("tianaixing_disgusted", "嫌弃"),
    SERIOUS("tianaixing_serious", "严肃"),
    WINK("tianaixing_wink", "眨眼笑");

    companion object {
        /**
         * 从LLM返回的情绪标签字符串解析枚举
         * 例如 "[EMOTION:happy]" → HAPPY
         */
        fun fromTag(tag: String): CharacterEmotion {
            val t = tag.trim().lowercase()
            return when {
                t.contains("happy") || t.contains("开心") || t.contains("高兴")
                    || t.contains("微笑") || t.contains("哈哈") || t.contains("棒")
                    || t.contains("眨眼笑") -> HAPPY
                t.contains("think") || t.contains("思考") || t.contains("不确定") -> THINKING
                t.contains("sad") || t.contains("伤心") || t.contains("难过")
                    || t.contains("悲") -> SAD
                t.contains("angry") || t.contains("生气") || t.contains("不满")
                    || t.contains("怒") -> ANGRY
                t.contains("sleep") || t.contains("睡") || t.contains("困乏")
                    || t.contains("drowsy") -> SLEEPING
                t.contains("sob") || t.contains("啜泣") || t.contains("抽泣") -> SOBBING
                t.contains("cry") || t.contains("大哭") || t.contains("痛哭") -> CRYING
                t.contains("depressed") || t.contains("沮丧") || t.contains("低落") -> DEPRESSED
                t.contains("distress") || t.contains("苦恼") || t.contains("烦")
                    || t.contains("困扰") -> DISTRESSED
                t.contains("sweat") || t.contains("流汗") || t.contains("冒汗")
                    || t.contains("汗") -> SWEATING
                t.contains("pain") || t.contains("痛苦") || t.contains("疼") -> PAINED
                t.contains("disgust") || t.contains("嫌弃") || t.contains("恶心")
                    || t.contains("讨厌") -> DISGUSTED
                t.contains("serious") || t.contains("严肃") || t.contains("正经") -> SERIOUS
                t.contains("wink") || t.contains("眨眼") || t.contains("调皮") -> WINK
                t.contains("惊讶") || t.contains("震惊") || t.contains("吓") -> SWEATING
                t.contains("害羞") || t.contains("脸红") -> HAPPY
                else -> IDLE
            }
        }
    }
}
