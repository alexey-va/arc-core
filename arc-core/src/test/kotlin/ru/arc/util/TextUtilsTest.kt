package ru.arc.util

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe

class TextUtilsTest : FreeSpec({
    "TextUtils" - {
        "escapeMM should escape angle brackets" {
            TextUtils.escapeMM("<bad>") shouldBe "\\<bad>"
        }

        "formatAmount should abbreviate thousands" {
            TextUtils.formatAmount(12_345.0) shouldBe "12.35K"
        }

        "mmToLegacy should map common tags" {
            TextUtils.mmToLegacy("<red>hi") shouldBe "&chi"
        }

        "centerInLore should keep long text unchanged" {
            TextUtils.centerInLore("long text", 4) shouldBe "long text"
        }

        "centerInLore should always produce the requested width" {
            TextUtils.centerInLore("Test", 11).length shouldBe 11
        }

        "splitLoreString should not add an empty line before a long word" {
            TextUtils.splitLoreString("Supercalifragilisticexpialidocious", 10, 0) shouldBe
                listOf("Supercalifragilisticexpialidocious")
        }

        "splitLoreString should account for the separating space" {
            TextUtils.splitLoreString("12345 67890", 10, 0) shouldBe listOf("12345", "67890")
        }
    }
})
