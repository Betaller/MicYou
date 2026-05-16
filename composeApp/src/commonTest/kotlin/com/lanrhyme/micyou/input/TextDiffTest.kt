package com.lanrhyme.micyou.input

import kotlin.test.Test
import kotlin.test.assertEquals

class TextDiffTest {

    @Test
    fun appendingChar_emitsTypeText() {
        assertEquals(
            TextDiffResult.Edit(backspaces = 0, insert = "c"),
            TextDiff.diff("ab", "abc")
        )
    }

    @Test
    fun backspace_emitsBackKey() {
        assertEquals(
            TextDiffResult.Edit(backspaces = 1, insert = ""),
            TextDiff.diff("abc", "ab")
        )
    }

    @Test
    fun replaceMiddle_emitsBackThenInsert() {
        // "abXYz" → "abQRz": delete 2 (X,Y), insert "QR"
        assertEquals(
            TextDiffResult.Edit(backspaces = 2, insert = "QR"),
            TextDiff.diff("abXYz", "abQRz")
        )
    }

    @Test
    fun unicodeEmoji_remainsAsSingleText() {
        val result = TextDiff.diff("hello ", "hello 😀")
        // Emoji is a surrogate pair → 2 chars in the inserted text
        check(result is TextDiffResult.Edit)
        assertEquals(0, result.backspaces)
        assertEquals("😀", result.insert)
    }

    @Test
    fun identicalText_isNoop() {
        assertEquals(TextDiffResult.Noop, TextDiff.diff("same", "same"))
    }

    @Test
    fun chineseInsertion_emitsTextOnly() {
        assertEquals(
            TextDiffResult.Edit(backspaces = 0, insert = "你好"),
            TextDiff.diff("", "你好")
        )
    }

    @Test
    fun fullClear_emitsBackspacesEqualToLength() {
        assertEquals(
            TextDiffResult.Edit(backspaces = 5, insert = ""),
            TextDiff.diff("hello", "")
        )
    }
}
