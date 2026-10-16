package ru.arc.paper.schematics

import com.sk89q.worldedit.extent.clipboard.Clipboard
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader
import com.sk89q.worldedit.math.BlockVector3
import com.sk89q.worldedit.world.block.BaseBlock
import com.sk89q.worldedit.world.block.BlockState
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import ru.arc.paper.api.ArcSchematicCacheSettings
import ru.arc.paper.api.ArcSchematicException
import ru.arc.paper.api.ArcSchematicFailure
import java.io.InputStream

class WorldEditSchematicClipboardTest : FreeSpec({
    "decode releases an owned closeable clipboard after copying or rejecting its dimensions" {
        val clipboard = mockk<Clipboard>(moreInterfaces = arrayOf(AutoCloseable::class))
        val resource = clipboard as AutoCloseable
        val state = mockk<BlockState>()
        val block = mockk<BaseBlock>()
        every { block.toImmutableState() } returns state
        every { block.nbtReference } returns null
        every { clipboard.minimumPoint } returns BlockVector3.ZERO
        every { clipboard.maximumPoint } returns BlockVector3.ZERO
        every { clipboard.origin } returns BlockVector3.ZERO
        every { clipboard.getFullBlock(any()) } returns block
        every { clipboard.hasBiomes() } returns false
        every { clipboard.entities } returns emptyList()
        every { resource.close() } returns Unit
        val reader = mockk<ClipboardReader>()
        every { reader.read() } returns clipboard
        every { reader.close() } returns Unit
        val format = mockk<ClipboardFormat>()
        every { format.isFormat(any<InputStream>()) } returns true
        every { format.getReader(any()) } returns reader
        val captured = SchematicBytes(mockk(), byteArrayOf(1), "captured-source")
        // Initialize native registration before mocking static calls used by its initializer.
        ClipboardFormats.getAll().isNotEmpty() shouldBe true
        mockkStatic(ClipboardFormats::class)
        try {
            every { ClipboardFormats.getAll() } returns listOf(format)
            val codec = WorldEditSchematicCodec(ArcSchematicCacheSettings.defaults().copy(maximumVolume = 1))
            val decoded = codec.decode(captured)
            decoded.palette shouldBe listOf(state)
            decoded.indices.toList() shouldBe listOf(0)
            verify(exactly = 1) { resource.close() }
            every { clipboard.maximumPoint } returns BlockVector3.at(1, 0, 0)
            shouldThrow<ArcSchematicException> { codec.decode(captured) }.reason shouldBe ArcSchematicFailure.LIMIT_EXCEEDED
            verify(exactly = 2) { resource.close(); reader.close() }
        } finally {
            unmockkStatic(ClipboardFormats::class)
        }
    }
})
