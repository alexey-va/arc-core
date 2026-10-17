package ru.arc.paper.schematics

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import ru.arc.paper.api.ArcSchematicLibrary
import ru.arc.paper.api.ArcSchematicSnapshot
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

class PreparingSchematicServiceTest : FunSpec({
    test("cold libraries wait for prepared limits without blocking or reading files") {
        val facade = PreparingSchematicService()
        val library = facade.open("consumer", Path.of("missing-root"))
        val load = library.load("house")
        load.isDone shouldBe false
        facade.diagnostics().pendingRequests shouldBe 1
        val snapshot = mockk<ArcSchematicSnapshot>()
        val actual = mockk<ArcSchematicLibrary>(relaxed = true)
        every { actual.load("house") } returns CompletableFuture.completedFuture(snapshot)
        val service = mockk<SharedSchematicService>(relaxed = true)
        every { service.open("consumer", any()) } returns actual
        facade.attach(service)
        load.getNow(null) shouldBe snapshot
        library.close()
        verify(exactly = 1) { actual.close() }
        facade.close()
    }

    test("crop and detached NBT inspection wait for the prepared provider and forward their exact inputs") {
        val facade = PreparingSchematicService()
        val library = facade.open("crop", Path.of("root"))
        val snapshot = mockk<ArcSchematicSnapshot>()
        val cropped = mockk<ArcSchematicSnapshot>()
        val min = ru.arc.paper.api.ArcSchematicPoint(-2, 3, 4)
        val max = ru.arc.paper.api.ArcSchematicPoint(5, 6, 7)
        val visitor = java.util.function.BiConsumer<ru.arc.paper.api.ArcSchematicPoint, String> { _, _ -> }
        val crop = library.crop(snapshot, min, max)
        val inspection = library.inspectBlockEntityNbt(snapshot, visitor)
        crop.isDone shouldBe false
        inspection.isDone shouldBe false
        val actual = mockk<ArcSchematicLibrary>(relaxed = true)
        every { actual.crop(snapshot, min, max) } returns CompletableFuture.completedFuture(cropped)
        every { actual.inspectBlockEntityNbt(snapshot, visitor) } returns CompletableFuture.completedFuture(null)
        val service = mockk<SharedSchematicService>(relaxed = true)
        every { service.open("crop", any()) } returns actual
        facade.attach(service)
        crop.getNow(null) shouldBe cropped
        inspection.isDone shouldBe true
        inspection.isCompletedExceptionally shouldBe false
        facade.close()
    }

    test("closing one pending library completes only its requests and rejects late binding") {
        val facade = PreparingSchematicService()
        val first = facade.open("first", Path.of("first"))
        val second = facade.open("second", Path.of("second"))
        val firstLoad = first.load("house")
        val secondLoad = second.load("house")
        first.close()
        firstLoad.isCompletedExceptionally shouldBe true
        secondLoad.isDone shouldBe false
        val actual = mockk<ArcSchematicLibrary>(relaxed = true)
        every { actual.load("house") } returns CompletableFuture.completedFuture(mockk())
        val service = mockk<SharedSchematicService>(relaxed = true)
        every { service.open("second", any()) } returns actual
        facade.attach(service)
        verify(exactly = 0) { service.open("first", any()) }
        secondLoad.isDone shouldBe true
        secondLoad.isCompletedExceptionally shouldBe false
        facade.close()
    }

    test("closed provider rejects a late prepared generation and completes waiting requests") {
        val facade = PreparingSchematicService()
        val pending = facade.open("consumer", Path.of("root")).load("house")
        facade.close()
        pending.isCompletedExceptionally shouldBe true
        val late = mockk<SharedSchematicService>(relaxed = true)
        facade.attach(late)
        verify(exactly = 1) { late.close() }
        verify(exactly = 0) { late.open(any(), any()) }
        facade.diagnostics().closed shouldBe true
    }
})
