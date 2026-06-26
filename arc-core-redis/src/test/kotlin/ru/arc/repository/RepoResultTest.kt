
package ru.arc.repository

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class RepoResultTest {

    @Nested
    @DisplayName("Success")
    inner class SuccessTests {

        @Test
        fun `isSuccess returns true`() {
            val result = RepoResult.success("value")

            assertTrue(result.isSuccess)
        }

        @Test
        fun `isError returns false`() {
            val result = RepoResult.success("value")

            assertFalse(result.isError)
        }

        @Test
        fun `getOrNull returns value`() {
            val result = RepoResult.success("value")

            assertEquals("value", result.getOrNull())
        }

        @Test
        fun `getOrThrow returns value`() {
            val result = RepoResult.success("value")

            assertEquals("value", result.getOrThrow())
        }
    }

    @Nested
    @DisplayName("Error")
    inner class ErrorTests {

        @Test
        fun `isSuccess returns false`() {
            val result = RepoResult.error("error")

            assertFalse(result.isSuccess)
        }

        @Test
        fun `isError returns true`() {
            val result = RepoResult.error("error")

            assertTrue(result.isError)
        }

        @Test
        fun `getOrNull returns null`() {
            val result = RepoResult.error("error")

            assertNull(result.getOrNull())
        }

        @Test
        fun `getOrThrow throws exception`() {
            val cause = RuntimeException("cause")
            val result = RepoResult.error("error", cause)

            val exception = assertThrows<RuntimeException> {
                result.getOrThrow()
            }

            assertEquals("cause", exception.message)
        }

        @Test
        fun `getOrThrow throws IllegalStateException if no cause`() {
            val result = RepoResult.error("error message")

            val exception = assertThrows<IllegalStateException> {
                result.getOrThrow()
            }

            assertEquals("error message", exception.message)
        }
    }

    @Nested
    @DisplayName("Map")
    inner class MapTests {

        @Test
        fun `map transforms success value`() {
            val result = RepoResult.success(5)

            val mapped = result.map { it * 2 }

            assertEquals(10, mapped.getOrNull())
        }

        @Test
        fun `map propagates error`() {
            val result: RepoResult<Int> = RepoResult.error("error")

            val mapped = result.map { it * 2 }

            assertTrue(mapped.isError)
        }
    }

    @Nested
    @DisplayName("Callbacks")
    inner class CallbackTests {

        @Test
        fun `onSuccess is called for success`() {
            val result = RepoResult.success("value")
            var called = false

            result.onSuccess { called = true }

            assertTrue(called)
        }

        @Test
        fun `onSuccess is not called for error`() {
            val result: RepoResult<String> = RepoResult.error("error")
            var called = false

            result.onSuccess { called = true }

            assertFalse(called)
        }

        @Test
        fun `onError is called for error`() {
            val result: RepoResult<String> = RepoResult.error("error message")
            var message: String? = null

            result.onError { msg, _ -> message = msg }

            assertEquals("error message", message)
        }

        @Test
        fun `onError is not called for success`() {
            val result = RepoResult.success("value")
            var called = false

            result.onError { _, _ -> called = true }

            assertFalse(called)
        }

        @Test
        fun `callbacks return the result for chaining`() {
            val result = RepoResult.success("value")

            val chained = result
                .onSuccess { /* do something */ }
                .onError { _, _ -> /* handle error */ }

            assertSame(result, chained)
        }
    }

    @Nested
    @DisplayName("runCatching")
    inner class RunCatchingTests {

        @Test
        fun `runCatching returns success on no exception`() {
            val result = RepoResult.runCatching { "value" }

            assertTrue(result.isSuccess)
            assertEquals("value", result.getOrNull())
        }

        @Test
        fun `runCatching returns error on exception`() {
            val result = RepoResult.runCatching<String> {
                throw RuntimeException("test error")
            }

            assertTrue(result.isError)
            assertTrue((result as RepoResult.Error).message.contains("test error"))
        }

        @Test
        fun `runCatching captures exception as cause`() {
            val original = RuntimeException("original")
            val result = RepoResult.runCatching<String> { throw original }

            assertSame(original, (result as RepoResult.Error).cause)
        }
    }
}
