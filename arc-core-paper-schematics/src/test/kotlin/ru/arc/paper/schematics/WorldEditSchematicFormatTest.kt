package ru.arc.paper.schematics

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.GZIPOutputStream

class WorldEditSchematicFormatTest : FreeSpec({
    "captured Sponge bytes select the native version reader in WorldEdit and FAWE" {
        for (version in 1..3) {
            val bytes = formatNbt {
                writeByte(10); writeUTF(if (version == 3) "" else "Schematic")
                if (version == 3) { writeByte(10); writeUTF("Schematic") }
                writeByte(3); writeUTF("Version"); writeInt(version)
                writeByte(0)
                if (version == 3) writeByte(0)
            }
            val expected = setOf("SPONGE_V${version}_SCHEMATIC", "FAST_V$version")
            expected shouldContain findSchematicFormat(bytes)?.name
        }
    }

    "legacy MCEdit detection uses captured content and unsupported bytes are rejected" {
        val legacy = formatNbt {
            writeByte(10); writeUTF("Schematic")
            writeByte(8); writeUTF("Materials"); writeUTF("Alpha")
            writeByte(0)
        }
        findSchematicFormat(legacy)?.name shouldBe "MCEDIT_SCHEMATIC"
        findSchematicFormat(byteArrayOf(1, 2, 3)) shouldBe null
        findSchematicFormat(formatNbt { writeByte(10); writeUTF("Unrelated"); writeByte(0) }) shouldBe null
    }
})

private fun formatNbt(write: DataOutputStream.() -> Unit): ByteArray {
    val bytes = ByteArrayOutputStream()
    DataOutputStream(GZIPOutputStream(bytes)).use(write)
    return bytes.toByteArray()
}
