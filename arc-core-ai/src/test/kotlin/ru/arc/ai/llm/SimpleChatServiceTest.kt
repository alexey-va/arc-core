package ru.arc.ai.llm

import com.openai.client.OpenAIClient
import com.openai.models.chat.completions.ChatCompletion
import com.openai.models.chat.completions.ChatCompletionMessage
import com.openai.services.blocking.ChatService
import com.openai.services.blocking.chat.ChatCompletionService
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import ru.arc.ai.config.TestLlmModuleConfig
import java.util.Optional
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

class SimpleChatServiceTest : FreeSpec({
    "SimpleChatService" - {
        "should return null without scheduling when LLM is disabled" {
            val config = TestLlmModuleConfig(apiKey = "none")
            val executorCalled = AtomicBoolean()
            val service =
                SimpleChatService(
                    OpenRouterLlmClient.create(config),
                    Executor {
                        executorCalled.set(true)
                        it.run()
                    },
                )

            service.complete("model", "system", emptyList(), 10, 0.0).join().shouldBeNull()
            executorCalled.get() shouldBe false
        }

        "should use supplied executor and trim a non-empty response" {
            val executorCalled = AtomicBoolean()
            val service =
                SimpleChatService(
                    llmReturning("  hello world  "),
                    Executor {
                        executorCalled.set(true)
                        it.run()
                    },
                )

            service.complete(
                model = "model",
                systemPrompt = "system",
                history = listOf(ChatTurn("user", "hello")),
                maxTokens = 10,
                temperature = 0.0,
            ).join() shouldBe "hello world"
            executorCalled.get() shouldBe true
        }

        "should turn blank and missing choices into no response" {
            SimpleChatService(llmReturning("   "), DIRECT_CHAT_EXECUTOR)
                .complete("model", "", emptyList(), 10, 0.0)
                .join()
                .shouldBeNull()

            SimpleChatService(llmReturningNoChoices(), DIRECT_CHAT_EXECUTOR)
                .complete("model", "", emptyList(), 10, 0.0)
                .join()
                .shouldBeNull()
        }

        "should turn client failures into no response" {
            val client = mockk<OpenAIClient>()
            every { client.chat() } throws IllegalStateException("boom")
            val llm = OpenRouterLlmClient(TestLlmModuleConfig(), client)

            SimpleChatService(llm, DIRECT_CHAT_EXECUTOR)
                .complete("model", "", emptyList(), 10, 0.0)
                .join()
                .shouldBeNull()
        }

        "should turn rejected scheduling into no response" {
            SimpleChatService(
                llmReturning("response"),
                Executor { throw RejectedExecutionException("closed") },
            ).complete("model", "", emptyList(), 10, 0.0)
                .join()
                .shouldBeNull()
        }
    }
})

private val DIRECT_CHAT_EXECUTOR = Executor { it.run() }

private fun llmReturning(content: String): OpenRouterLlmClient {
    val message = mockk<ChatCompletionMessage>()
    every { message.content() } returns Optional.of(content)
    val choice = mockk<ChatCompletion.Choice>()
    every { choice.message() } returns message
    return llmReturning(listOf(choice))
}

private fun llmReturningNoChoices(): OpenRouterLlmClient = llmReturning(emptyList())

private fun llmReturning(choices: List<ChatCompletion.Choice>): OpenRouterLlmClient {
    val completion = mockk<ChatCompletion>()
    every { completion.choices() } returns choices
    val completions = mockk<ChatCompletionService>()
    every { completions.create(any()) } returns completion
    val chat = mockk<ChatService>()
    every { chat.completions() } returns completions
    val client = mockk<OpenAIClient>()
    every { client.chat() } returns chat
    return OpenRouterLlmClient(TestLlmModuleConfig(), client)
}
