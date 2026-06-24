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
    }
})
