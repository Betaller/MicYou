package com.lanrhyme.micyou.input

/**
 * 把两次 BasicTextField 的快照差分为一组远程操作，避免逐字符 IME 同步的复杂度。
 * 算法：找最长公共前缀和后缀，中间的 old 段当成 backspace 次数（按字符数计），
 * 中间的 new 段当成 typeUnicode 文本。Emoji 用 Char 计数会高估退格次数，
 * 但符合多数应用对 backspace 的处理预期。
 */
object TextDiff {
    fun diff(oldText: String, newText: String): TextDiffResult {
        if (oldText == newText) return TextDiffResult.Noop
        var prefix = 0
        val maxPrefix = minOf(oldText.length, newText.length)
        while (prefix < maxPrefix && oldText[prefix] == newText[prefix]) prefix++

        var suffix = 0
        val maxSuffix = minOf(oldText.length - prefix, newText.length - prefix)
        while (suffix < maxSuffix &&
            oldText[oldText.length - 1 - suffix] == newText[newText.length - 1 - suffix]) suffix++

        val deleted = oldText.length - prefix - suffix
        val inserted = newText.substring(prefix, newText.length - suffix)
        return TextDiffResult.Edit(backspaces = deleted, insert = inserted)
    }
}

sealed class TextDiffResult {
    object Noop : TextDiffResult()
    data class Edit(val backspaces: Int, val insert: String) : TextDiffResult()
}
