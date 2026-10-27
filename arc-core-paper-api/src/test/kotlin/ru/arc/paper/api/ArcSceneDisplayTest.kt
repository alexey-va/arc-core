package ru.arc.paper.api

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import java.net.URLClassLoader
import java.nio.file.Files
import javax.tools.ToolProvider

class ArcSceneDisplayTest : FreeSpec({
    fun pose() = ArcSceneDisplayPose(1.0, 64.0, 1.0, 0f, 0f,
        ArcSceneVector3(0f, 0f, 0f), ArcSceneVector3(1f, 1f, 1f),
        ArcSceneQuaternion(0f, 0f, 0f, 1f), ArcSceneQuaternion(0f, 0f, 0f, 1f))
    val item = ArcSceneItem("minecraft:paper", 1, 501, null, "ruscrafting:crate_reward")

    "timelines are detached finite forward windows with bounded native item switches" {
        val samples = MutableList(21) { ArcSceneDisplayKeyframe(pose(), 0) }
        val items = mutableListOf(item)
        val switches = mutableListOf(ArcSceneDisplayItemSwitch(0, 0), ArcSceneDisplayItemSwitch(150, 0))
        val timeline = ArcSceneDisplayTimeline(1, .5, 100, samples, items, switches)
        samples.clear(); items.clear(); switches.clear()
        timeline.samples.size shouldBe 21
        timeline.items.single() shouldBe item
        timeline.itemSwitches.last().offsetMillis shouldBe 150
        shouldThrow<UnsupportedOperationException> { (timeline.samples as MutableList).clear() }
        shouldThrow<IllegalArgumentException> { ArcSceneDisplayTimeline(1, 0.0, 100, List(22) { ArcSceneDisplayKeyframe(pose(), 0) }, listOf(item), listOf(ArcSceneDisplayItemSwitch(0, 0))) }
        shouldThrow<IllegalArgumentException> { ArcSceneDisplayTimeline(1, 0.0, 1000, List(4) { ArcSceneDisplayKeyframe(pose(), 0) }, listOf(item), listOf(ArcSceneDisplayItemSwitch(0, 0))) }
        for (invalid in listOf(listOf(ArcSceneDisplayItemSwitch(50, 0)),
            listOf(ArcSceneDisplayItemSwitch(0, 0), ArcSceneDisplayItemSwitch(0, 0)),
            listOf(ArcSceneDisplayItemSwitch(0, 1)))) {
            shouldThrow<IllegalArgumentException> { ArcSceneDisplayTimeline(1, 0.0, 100, timeline.samples, listOf(item), invalid) }
        }
        shouldThrow<IllegalArgumentException> { ArcSceneItem("../private", 1, null, null, null) }
        shouldThrow<IllegalArgumentException> { ArcSceneVector3(Float.NaN, 0f, 0f) }
        shouldThrow<IllegalArgumentException> { ArcSceneTextContent("public", listOf(ArcSceneTextRun("private", 0, "minecraft:default", false, false, false, false, false))) }
        for (type in listOf(ArcSceneDisplay::class.java, ArcSceneDisplayPose::class.java, ArcSceneDisplayTimeline::class.java,
            ArcSceneDisplayContent.Item::class.java, ArcSceneDisplayContent.Text::class.java, ArcSceneItem::class.java)) {
            type.constructors.none { it.parameterTypes.any { parameter -> parameter.name == "kotlin.jvm.internal.DefaultConstructorMarker" } } shouldBe true
        }
    }

    "an old compiled source SAM inherits a real JVM default without recompilation" {
        ArcSceneEntitySource::class.java.getMethod("displays", ArcSceneBounds::class.java, Int::class.javaPrimitiveType).isDefault shouldBe true
        ArcSceneObservationService::class.java.getMethod("displays", ArcSceneBounds::class.java, Int::class.javaPrimitiveType).isDefault shouldBe true
        val directory = Files.createTempDirectory("arc-scene-old-sam")
        try {
            val source = directory.resolve("ArcSceneEntitySource.java")
            val factory = directory.resolve("LegacySceneFactory.java")
            Files.writeString(source, """
                package ru.arc.paper.api;
                public interface ArcSceneEntitySource {
                    java.util.List<ArcSceneEntity> snapshot(ArcSceneBounds bounds, int limit);
                }
            """.trimIndent())
            Files.writeString(factory, """
                package fixture;
                public final class LegacySceneFactory {
                    public static ru.arc.paper.api.ArcSceneEntitySource make() {
                        return (bounds, limit) -> java.util.List.of();
                    }
                }
            """.trimIndent())
            val classpath = java.nio.file.Path.of(ArcSceneEntitySource::class.java.protectionDomain.codeSource.location.toURI()).toString()
            ToolProvider.getSystemJavaCompiler().run(null, null, null, "-classpath", classpath,
                "-d", directory.toString(), source.toString(), factory.toString()) shouldBe 0
            URLClassLoader(arrayOf(directory.toUri().toURL()), ArcSceneEntitySource::class.java.classLoader).use { loader ->
                val legacy = loader.loadClass("fixture.LegacySceneFactory").getMethod("make").invoke(null) as ArcSceneEntitySource
                val bounds = ArcSceneBounds(java.util.UUID(0, 1), 0.0, 0.0, 0.0, 1.0, 1.0, 1.0)
                legacy.snapshot(bounds, 1) shouldBe emptyList()
                legacy.displays(bounds, 1) shouldBe emptyList()
            }
        } finally {
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }
})
