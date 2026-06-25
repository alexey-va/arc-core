package ru.arc.ai.llm

import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import org.slf4j.LoggerFactory
import ru.arc.ai.config.LlmModuleConfig
import java.net.InetSocketAddress
import java.net.Proxy
import java.time.Duration

class OpenRouterLlmClient private constructor(
    val config: LlmModuleConfig,
    val client: OpenAIClient?,
) {

    val enabled: Boolean get() = client != null

    companion object {
        private val log = LoggerFactory.getLogger(OpenRouterLlmClient::class.java)

        fun create(config: LlmModuleConfig): OpenRouterLlmClient {
            if (!config.llmEnabled) {
                log.warn("LLM disabled — openrouter.api-key is not set")
                return OpenRouterLlmClient(config, null)
            }
            config.validateProxy()
            return OpenRouterLlmClient(config, buildClient(config))
        }

        internal fun buildClient(config: LlmModuleConfig): OpenAIClient {
            val builder =
                OpenAIOkHttpClient.builder()
                    .apiKey(config.apiKey)
                    .timeout(Duration.ofSeconds(config.timeoutSeconds))
                    .baseUrl(config.apiBaseUrl)

            if (config.proxyEnabled) {
                builder.proxy(
                    Proxy(
                        Proxy.Type.HTTP,
                        InetSocketAddress(config.proxyHost, config.proxyPort),
                    ),
                )
            }

            return builder.build()
        }
    }
}
