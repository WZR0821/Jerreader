import CryptoKit
import Foundation
import XCTest
@testable import Jerreader

final class BackendTranslationServiceTests: XCTestCase {
    func testSuccessfulRequestUsesProxyContractAndBearerToken() async throws {
        let endpoint = try XCTUnwrap(URL(string: "https://translate.example.test/v1/selection"))
        let client = BackendHTTPClientStub(
            statusCode: 200,
            body: #"{"translatedText":"夜色很安静。"}"#.data(using: .utf8)!
        )
        let service = BackendTranslationService(
            configuration: BackendTranslationConfiguration(
                endpoint: endpoint,
                model: "translation-model",
                accessToken: "local-proxy-token"
            ),
            client: client
        )

        let result = try await service.translate(
            text: "  The night was quiet.  ",
            context: "The lamps were already lit. The night was quiet. Nobody spoke.",
            sourceLanguage: .english,
            targetLanguage: .simplifiedChinese
        )
        let capturedRequest = await client.lastRequest()
        let request = try XCTUnwrap(capturedRequest)
        let bodyData = try XCTUnwrap(request.httpBody)
        let body = try XCTUnwrap(
            JSONSerialization.jsonObject(with: bodyData) as? [String: Any]
        )

        XCTAssertEqual(request.url, endpoint)
        XCTAssertEqual(request.httpMethod, "POST")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer local-proxy-token")
        XCTAssertEqual(request.value(forHTTPHeaderField: "X-Jerreader-Translation-Protocol"), "1")
        XCTAssertEqual(
            request.value(forHTTPHeaderField: "X-Jerreader-Idempotency-Key"),
            TranslationCacheStore.cacheKey(
                text: "The night was quiet.",
                contextText: "The lamps were already lit. The night was quiet. Nobody spoke.",
                sourceLanguage: .english,
                targetLanguage: .simplifiedChinese,
                providerIdentifier: BackendTranslationService.providerIdentifier,
                providerVersion: service.providerVersion
            )
        )
        XCTAssertEqual(body["text"] as? String, "The night was quiet.")
        XCTAssertEqual(
            body["context"] as? String,
            "The lamps were already lit. The night was quiet. Nobody spoke."
        )
        XCTAssertEqual(body["sourceLanguage"] as? String, LanguageCode.english.rawValue)
        XCTAssertEqual(body["targetLanguage"] as? String, LanguageCode.simplifiedChinese.rawValue)
        XCTAssertEqual(body["model"] as? String, "translation-model")
        XCTAssertTrue((body["prompt"] as? String)?.contains("英语") == true)
        XCTAssertTrue((body["prompt"] as? String)?.contains("简体中文") == true)
        XCTAssertEqual(result.translatedText, "夜色很安静。")
        XCTAssertEqual(result.providerIdentifier, BackendTranslationService.providerIdentifier)
        XCTAssertFalse(result.isFromCache)
    }

    func testAlternativeTranslationResponseFieldIsAccepted() async throws {
        let endpoint = try XCTUnwrap(URL(string: "https://translate.example.test/translate"))
        let client = BackendHTTPClientStub(
            statusCode: 200,
            body: #"{"translation":"A quiet evening."}"#.data(using: .utf8)!
        )
        let service = BackendTranslationService(
            configuration: BackendTranslationConfiguration(
                endpoint: endpoint,
                model: nil,
                accessToken: nil
            ),
            client: client
        )

        let result = try await service.translate(
            text: "静かな夜でした。",
            sourceLanguage: .japanese,
            targetLanguage: .english
        )

        XCTAssertEqual(result.translatedText, "A quiet evening.")
    }

    func testAuthenticationAndConfigurationStatusCodesAreTyped() async throws {
        let endpoint = try XCTUnwrap(URL(string: "https://translate.example.test/translate"))

        for statusCode in [401, 403] {
            let service = BackendTranslationService(
                configuration: .init(endpoint: endpoint, model: nil, accessToken: nil),
                client: BackendHTTPClientStub(statusCode: statusCode, body: Data())
            )
            await assertServiceError(.authenticationFailed) {
                try await service.translate(
                    text: "Translate me.",
                    sourceLanguage: .english,
                    targetLanguage: .japanese
                )
            }
        }

        for statusCode in [400, 404, 422] {
            let service = BackendTranslationService(
                configuration: .init(endpoint: endpoint, model: nil, accessToken: nil),
                client: BackendHTTPClientStub(statusCode: statusCode, body: Data())
            )
            await assertServiceError(.invalidConfiguration) {
                try await service.translate(
                    text: "Translate me.",
                    sourceLanguage: .english,
                    targetLanguage: .japanese
                )
            }
        }
    }

    func testInsecureEndpointAndSameLanguageAreRejectedBeforeNetwork() async throws {
        let insecureEndpoint = try XCTUnwrap(URL(string: "http://translate.example.test/translate"))
        let client = BackendHTTPClientStub(statusCode: 200, body: Data())
        let service = BackendTranslationService(
            configuration: .init(endpoint: insecureEndpoint, model: nil, accessToken: nil),
            client: client
        )

        await assertServiceError(.invalidConfiguration) {
            try await service.translate(
                text: "Translate me.",
                sourceLanguage: .english,
                targetLanguage: .japanese
            )
        }
        let requestAfterInsecureEndpoint = await client.lastRequest()
        XCTAssertNil(requestAfterInsecureEndpoint)

        let secureService = BackendTranslationService(
            configuration: .init(
                endpoint: URL(string: "https://translate.example.test/translate")!,
                model: nil,
                accessToken: nil
            ),
            client: client
        )
        await assertServiceError(.unsupportedLanguage) {
            try await secureService.translate(
                text: "同じ言語",
                sourceLanguage: .japanese,
                targetLanguage: .japanese
            )
        }
        let requestAfterSameLanguage = await client.lastRequest()
        XCTAssertNil(requestAfterSameLanguage)
    }

    func testOpenAIDirectPresetUsesResponsesContract() async throws {
        let endpoint = try XCTUnwrap(URL(string: "https://api.openai.com/v1/responses"))
        let client = BackendHTTPClientStub(
            statusCode: 200,
            body: #"{"output_text":"夜晚很安静。","output":[]}"#.data(using: .utf8)!
        )
        let service = DirectAITranslationService(
            configuration: .init(
                provider: .openAI,
                endpoint: endpoint,
                model: "gpt-test",
                apiKey: "openai-test-key"
            ),
            client: client
        )

        let result = try await service.translate(
            text: "The night was quiet.",
            context: "The lamps were lit. The night was quiet.",
            sourceLanguage: .english,
            targetLanguage: .simplifiedChinese
        )
        let capturedRequest = await client.lastRequest()
        let request = try XCTUnwrap(capturedRequest)
        let body = try XCTUnwrap(
            JSONSerialization.jsonObject(with: try XCTUnwrap(request.httpBody))
                as? [String: Any]
        )

        XCTAssertEqual(request.url, endpoint)
        XCTAssertEqual(
            request.value(forHTTPHeaderField: "Authorization"),
            "Bearer openai-test-key"
        )
        XCTAssertEqual(body["model"] as? String, "gpt-test")
        XCTAssertTrue((body["instructions"] as? String)?.contains("只输出译文") == true)
        let input = try XCTUnwrap(body["input"] as? String)
        // The sentence and its paragraph are told apart in the request itself,
        // so a custom prompt cannot get the paragraph translated as well.
        XCTAssertTrue(input.contains("<source>\nThe night was quiet.\n</source>"))
        XCTAssertTrue(input.contains("<context>\nThe lamps were lit. The night was quiet.\n</context>"))
        XCTAssertTrue(input.contains("不要翻译它"))
        XCTAssertEqual(result.translatedText, "夜晚很安静。")
        XCTAssertEqual(result.providerIdentifier, "direct-ai-openAI")
    }

    func testDeepSeekDirectPresetUsesChatCompletionsContract() async throws {
        let endpoint = try XCTUnwrap(
            URL(string: "https://api.deepseek.com/chat/completions")
        )
        let client = BackendHTTPClientStub(
            statusCode: 200,
            body: #"{"choices":[{"message":{"content":"你好。"}}]}"#.data(using: .utf8)!
        )
        let service = DirectAITranslationService(
            configuration: .init(
                provider: .deepSeek,
                endpoint: endpoint,
                model: "deepseek-test",
                apiKey: "deepseek-test-key"
            ),
            client: client
        )

        let result = try await service.translate(
            text: "Hello.",
            sourceLanguage: .english,
            targetLanguage: .simplifiedChinese
        )
        let capturedRequest = await client.lastRequest()
        let request = try XCTUnwrap(capturedRequest)
        let body = try XCTUnwrap(
            JSONSerialization.jsonObject(with: try XCTUnwrap(request.httpBody))
                as? [String: Any]
        )
        let messages = try XCTUnwrap(body["messages"] as? [[String: Any]])

        XCTAssertEqual(request.url, endpoint)
        XCTAssertEqual(body["model"] as? String, "deepseek-test")
        XCTAssertEqual(messages.map { $0["role"] as? String }, ["system", "user"])
        XCTAssertEqual(result.translatedText, "你好。")
    }

    func testKimiDirectPresetUsesOfficialChatCompletionsContract() async throws {
        let endpoint = try XCTUnwrap(
            URL(string: "https://api.moonshot.cn/v1/chat/completions")
        )
        let client = BackendHTTPClientStub(
            statusCode: 200,
            body: #"{"choices":[{"message":{"content":"月光很安静。"}}]}"#.data(using: .utf8)!
        )
        let service = DirectAITranslationService(
            configuration: .init(
                provider: .kimi,
                endpoint: endpoint,
                model: "kimi-k2.6",
                apiKey: "kimi-test-key"
            ),
            client: client
        )

        let result = try await service.translate(
            text: "The moonlight was quiet.",
            sourceLanguage: .english,
            targetLanguage: .simplifiedChinese
        )
        let capturedRequest = await client.lastRequest()
        let request = try XCTUnwrap(capturedRequest)
        let body = try XCTUnwrap(
            JSONSerialization.jsonObject(with: try XCTUnwrap(request.httpBody))
                as? [String: Any]
        )
        let messages = try XCTUnwrap(body["messages"] as? [[String: Any]])

        XCTAssertEqual(request.url, endpoint)
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer kimi-test-key")
        XCTAssertEqual(body["model"] as? String, "kimi-k2.6")
        XCTAssertEqual(messages.map { $0["role"] as? String }, ["system", "user"])
        XCTAssertEqual(result.translatedText, "月光很安静。")
        XCTAssertEqual(result.providerIdentifier, "direct-ai-kimi")
    }

    func testGeminiDirectPresetBuildsModelURLAndKeyHeader() async throws {
        let baseURL = try XCTUnwrap(
            URL(string: "https://generativelanguage.googleapis.com/v1beta")
        )
        let client = BackendHTTPClientStub(
            statusCode: 200,
            body: #"{"candidates":[{"content":{"parts":[{"text":"你好。"}]}}]}"#.data(using: .utf8)!
        )
        let service = DirectAITranslationService(
            configuration: .init(
                provider: .gemini,
                endpoint: baseURL,
                model: "gemini-test",
                apiKey: "gemini-test-key"
            ),
            client: client
        )

        let result = try await service.translate(
            text: "Hello.",
            sourceLanguage: .english,
            targetLanguage: .simplifiedChinese
        )
        let capturedRequest = await client.lastRequest()
        let request = try XCTUnwrap(capturedRequest)
        let body = try XCTUnwrap(
            JSONSerialization.jsonObject(with: try XCTUnwrap(request.httpBody))
                as? [String: Any]
        )

        XCTAssertEqual(
            request.url?.absoluteString,
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-test:generateContent"
        )
        XCTAssertEqual(
            request.value(forHTTPHeaderField: "x-goog-api-key"),
            "gemini-test-key"
        )
        XCTAssertNotNil(body["systemInstruction"])
        XCTAssertNotNil(body["contents"])
        XCTAssertEqual(result.translatedText, "你好。")
    }

    func testAnthropicDirectPresetUsesMessagesHeadersAndResponse() async throws {
        let endpoint = try XCTUnwrap(URL(string: "https://api.anthropic.com/v1/messages"))
        let client = BackendHTTPClientStub(
            statusCode: 200,
            body: #"{"content":[{"type":"text","text":"你好。"}]}"#.data(using: .utf8)!
        )
        let service = DirectAITranslationService(
            configuration: .init(
                provider: .anthropic,
                endpoint: endpoint,
                model: "claude-test",
                apiKey: "claude-test-key"
            ),
            client: client
        )

        let result = try await service.translate(
            text: "Hello.",
            sourceLanguage: .english,
            targetLanguage: .simplifiedChinese
        )
        let capturedRequest = await client.lastRequest()
        let request = try XCTUnwrap(capturedRequest)
        let body = try XCTUnwrap(
            JSONSerialization.jsonObject(with: try XCTUnwrap(request.httpBody))
                as? [String: Any]
        )

        XCTAssertEqual(request.value(forHTTPHeaderField: "x-api-key"), "claude-test-key")
        XCTAssertEqual(request.value(forHTTPHeaderField: "anthropic-version"), "2023-06-01")
        XCTAssertEqual(body["model"] as? String, "claude-test")
        XCTAssertEqual(body["max_tokens"] as? Int, 1_200)
        XCTAssertEqual(result.translatedText, "你好。")
    }

    func testBackendContextExplanationUsesExplicitTaskAndLimitedContext() async throws {
        let endpoint = try XCTUnwrap(URL(string: "https://translate.example.test/v1/selection"))
        let client = BackendHTTPClientStub(
            statusCode: 200,
            body: #"{"explanation":"这里的 light 表示重量轻，而不是光线。"}"#.data(using: .utf8)!
        )
        let service = BackendTranslationService(
            configuration: .init(
                endpoint: endpoint,
                model: "explanation-model",
                accessToken: "proxy-token"
            ),
            client: client
        )

        let result = try await service.explain(
            ContextExplanationRequest(
                focusedText: "It was light.",
                contextText: "She lifted the box. It was light.",
                sourceLanguage: .english,
                responseLanguage: .simplifiedChinese
            )
        )
        let capturedRequest = await client.lastRequest()
        let request = try XCTUnwrap(capturedRequest)
        let body = try XCTUnwrap(
            JSONSerialization.jsonObject(with: try XCTUnwrap(request.httpBody))
                as? [String: Any]
        )

        XCTAssertEqual(body["task"] as? String, "explain")
        XCTAssertEqual(body["analysisMode"] as? String, "grammar")
        XCTAssertEqual(body["text"] as? String, "It was light.")
        XCTAssertEqual(body["context"] as? String, "She lifted the box. It was light.")
        XCTAssertEqual(body["responseLanguage"] as? String, "zh-Hans")
        XCTAssertTrue((body["prompt"] as? String)?.contains("句子主干") == true)
        XCTAssertEqual(
            request.timeoutInterval,
            BackendTranslationService.explanationRequestTimeout
        )
        XCTAssertEqual(result.explanation, "这里的 light 表示重量轻，而不是光线。")
        XCTAssertEqual(result.providerIdentifier, BackendTranslationService.providerIdentifier)
    }

    func testOpenAIContextExplanationReusesConfiguredProviderContract() async throws {
        let endpoint = try XCTUnwrap(URL(string: "https://api.openai.com/v1/responses"))
        let client = BackendHTTPClientStub(
            statusCode: 200,
            body: #"{"output_text":"light 在这里是形容重量轻。","output":[]}"#.data(using: .utf8)!
        )
        let service = DirectAITranslationService(
            configuration: .init(
                provider: .openAI,
                endpoint: endpoint,
                model: "gpt-test",
                apiKey: "openai-test-key"
            ),
            client: client
        )

        let result = try await service.explain(
            ContextExplanationRequest(
                focusedText: "It was light.",
                contextText: "She lifted the box. It was light.",
                sourceLanguage: .english,
                responseLanguage: .simplifiedChinese
            )
        )
        let capturedRequest = await client.lastRequest()
        let request = try XCTUnwrap(capturedRequest)
        let body = try XCTUnwrap(
            JSONSerialization.jsonObject(with: try XCTUnwrap(request.httpBody))
                as? [String: Any]
        )

        XCTAssertEqual(body["model"] as? String, "gpt-test")
        XCTAssertTrue((body["instructions"] as? String)?.contains("精确的语言教师") == true)
        XCTAssertTrue((body["instructions"] as? String)?.contains("句子主干") == true)
        XCTAssertTrue((body["input"] as? String)?.contains("SELECTED TEXT:\nIt was light.") == true)
        XCTAssertTrue((body["input"] as? String)?.contains("READING CONTEXT:") == true)
        XCTAssertEqual(
            request.timeoutInterval,
            DirectAITranslationService.explanationRequestTimeout
        )
        XCTAssertEqual(result.explanation, "light 在这里是形容重量轻。")
        XCTAssertEqual(result.providerIdentifier, "direct-ai-openAI")
    }

    func testOpenAIContextExplanationAcceptsReasoningItemBeforeMessage() async throws {
        let endpoint = try XCTUnwrap(URL(string: "https://api.openai.com/v1/responses"))
        let client = BackendHTTPClientStub(
            statusCode: 200,
            body: #"""
            {
              "output": [
                {"id":"reasoning-1","type":"reasoning","summary":[]},
                {
                  "id":"message-1",
                  "type":"message",
                  "content":[
                    {"type":"output_text","text":"这是主系表结构。","annotations":[]}
                  ]
                }
              ]
            }
            """#.data(using: .utf8)!
        )
        let service = DirectAITranslationService(
            configuration: .init(
                provider: .openAI,
                endpoint: endpoint,
                model: "reasoning-model",
                apiKey: "openai-test-key"
            ),
            client: client
        )

        let result = try await service.explain(
            ContextExplanationRequest(
                focusedText: "It was light.",
                contextText: nil,
                sourceLanguage: .english,
                responseLanguage: .simplifiedChinese
            )
        )

        XCTAssertEqual(result.explanation, "这是主系表结构。")
    }

    func testDirectProviderVersionDoesNotDependOnSecret() throws {
        let endpoint = try XCTUnwrap(URL(string: "https://api.openai.com/v1/responses"))
        let first = DirectAITranslationConfiguration(
            provider: .openAI,
            endpoint: endpoint,
            model: "gpt-test",
            apiKey: "first-secret"
        )
        let second = DirectAITranslationConfiguration(
            provider: .openAI,
            endpoint: endpoint,
            model: "gpt-test",
            apiKey: "second-secret"
        )

        XCTAssertEqual(
            DirectAITranslationService.providerVersion(for: first),
            DirectAITranslationService.providerVersion(for: second)
        )
    }

    func testCustomPromptChangesProviderVersionAndRenderedInstruction() async throws {
        let endpoint = try XCTUnwrap(URL(string: "https://api.openai.com/v1/responses"))
        let baseline = DirectAITranslationConfiguration(
            provider: .openAI,
            endpoint: endpoint,
            model: "gpt-test",
            apiKey: "local-secret"
        )
        let customized = DirectAITranslationConfiguration(
            provider: .openAI,
            endpoint: endpoint,
            model: "gpt-test",
            apiKey: "local-secret",
            translationPromptTemplate:
                "CUSTOM {source_language} TO {target_language}",
            grammarAnalysisPromptTemplate:
                AIPromptTemplateDefaults.grammarAnalysis
        )
        XCTAssertNotEqual(
            DirectAITranslationService.providerVersion(for: baseline),
            DirectAITranslationService.providerVersion(for: customized)
        )

        let client = BackendHTTPClientStub(
            statusCode: 200,
            body: #"{"output_text":"こんにちは","output":[]}"#.data(using: .utf8)!
        )
        let service = DirectAITranslationService(
            configuration: customized,
            client: client
        )
        _ = try await service.translate(
            text: "Hello",
            sourceLanguage: .english,
            targetLanguage: .japanese
        )
        let capturedRequest = await client.lastRequest()
        let request = try XCTUnwrap(capturedRequest)
        let body = try XCTUnwrap(
            JSONSerialization.jsonObject(
                with: try XCTUnwrap(request.httpBody)
            ) as? [String: Any]
        )
        XCTAssertEqual(body["instructions"] as? String, "CUSTOM 英语 TO 日语")
    }

    func testProviderVersionKeepsLegacyIdentityUntilFormatOrReasoningIsChosen() throws {
        let endpoint = try XCTUnwrap(URL(string: "https://api.openai.com/v1/responses"))
        let legacy = DirectAITranslationConfiguration(
            provider: .openAI,
            endpoint: endpoint,
            model: "gpt-test",
            apiKey: "local-secret"
        )
        let sameFormat = DirectAITranslationConfiguration(
            provider: .openAI,
            endpoint: endpoint,
            model: "gpt-test",
            apiKey: "local-secret",
            style: .responses,
            reasoning: nil
        )
        // The formula used before formats could be chosen, so cached
        // translations of an existing setup are still found.
        let before = [
            "openAI",
            endpoint.absoluteString,
            "gpt-test",
            AIPromptTemplateDefaults.translation,
            AIPromptTemplateDefaults.grammarAnalysis,
            "jerreader-direct-api-v2",
        ].joined(separator: "\u{001F}")
        let beforeVersion = SHA256.hash(data: Data(before.utf8))
            .prefix(12).map { String(format: "%02x", $0) }.joined()

        XCTAssertEqual(legacy.style, .responses)
        XCTAssertEqual(DirectAITranslationService.providerVersion(for: legacy), beforeVersion)
        XCTAssertEqual(DirectAITranslationService.providerVersion(for: sameFormat), beforeVersion)
        XCTAssertNotEqual(
            DirectAITranslationService.providerVersion(
                for: DirectAITranslationConfiguration(
                    provider: .openAI, endpoint: endpoint, model: "gpt-test",
                    apiKey: "local-secret", style: .chatCompletions
                )
            ),
            beforeVersion
        )
        XCTAssertNotEqual(
            DirectAITranslationService.providerVersion(
                for: DirectAITranslationConfiguration(
                    provider: .openAI, endpoint: endpoint, model: "gpt-test",
                    apiKey: "local-secret", reasoning: .high
                )
            ),
            beforeVersion
        )
        XCTAssertEqual(
            DirectAITranslationConfiguration(
                provider: .gemini, endpoint: endpoint, model: "gemini-test", apiKey: "k"
            ).style,
            .geminiNative
        )
    }

    func testDeepSeekFlashTranslatesWithoutThinkingAndExplainsWithLowEffort() async throws {
        let endpoint = try XCTUnwrap(URL(string: "https://api.deepseek.com/chat/completions"))
        let configuration = DirectAITranslationConfiguration(
            provider: .deepSeek,
            endpoint: endpoint,
            model: "deepseek-flash",
            apiKey: "deepseek-test-key"
        )
        let translationClient = BackendHTTPClientStub(
            statusCode: 200,
            body: #"{"choices":[{"message":{"content":"你好。"}}]}"#.data(using: .utf8)!
        )
        _ = try await DirectAITranslationService(
            configuration: configuration,
            client: translationClient
        ).translate(text: "Hello.", sourceLanguage: .english, targetLanguage: .simplifiedChinese)
        let translationRequest = await translationClient.lastRequest()
        let translation = try XCTUnwrap(
            JSONSerialization.jsonObject(
                with: try XCTUnwrap(translationRequest?.httpBody)
            ) as? [String: Any]
        )
        XCTAssertEqual((translation["thinking"] as? [String: Any])?["type"] as? String, "disabled")
        XCTAssertNil(translation["reasoning_effort"])
        XCTAssertEqual(translation["stream"] as? Bool, false)
        XCTAssertEqual(translation["model"] as? String, "deepseek-flash")

        let explanationClient = BackendHTTPClientStub(
            statusCode: 200,
            body: #"{"choices":[{"message":{"content":"这是主系表结构。"}}]}"#.data(using: .utf8)!
        )
        _ = try await DirectAITranslationService(
            configuration: configuration,
            client: explanationClient
        ).explain(
            ContextExplanationRequest(
                focusedText: "It was light.",
                contextText: nil,
                sourceLanguage: .english,
                responseLanguage: .simplifiedChinese
            )
        )
        let explanationRequest = await explanationClient.lastRequest()
        let explanation = try XCTUnwrap(
            JSONSerialization.jsonObject(
                with: try XCTUnwrap(explanationRequest?.httpBody)
            ) as? [String: Any]
        )
        XCTAssertEqual((explanation["thinking"] as? [String: Any])?["type"] as? String, "enabled")
        XCTAssertEqual(explanation["reasoning_effort"] as? String, "low")
    }

    func testChosenFormatAndReasoningShapeTheRequest() async throws {
        // A DeepSeek model called through its Claude-compatible address.
        let endpoint = try XCTUnwrap(URL(string: "https://api.deepseek.com/anthropic/messages"))
        let client = BackendHTTPClientStub(
            statusCode: 200,
            body: #"{"content":[{"type":"text","text":"你好。"}]}"#.data(using: .utf8)!
        )
        let result = try await DirectAITranslationService(
            configuration: DirectAITranslationConfiguration(
                provider: .deepSeek,
                endpoint: endpoint,
                model: "deepseek-v4-pro",
                apiKey: "deepseek-test-key",
                style: .anthropicMessages,
                reasoning: .high
            ),
            client: client
        ).translate(text: "Hello.", sourceLanguage: .english, targetLanguage: .simplifiedChinese)
        let capturedRequest = await client.lastRequest()
        let request = try XCTUnwrap(capturedRequest)
        let body = try XCTUnwrap(
            JSONSerialization.jsonObject(with: try XCTUnwrap(request.httpBody)) as? [String: Any]
        )

        XCTAssertEqual(request.url, endpoint)
        XCTAssertEqual(request.value(forHTTPHeaderField: "x-api-key"), "deepseek-test-key")
        XCTAssertNil(request.value(forHTTPHeaderField: "Authorization"))
        XCTAssertEqual((body["thinking"] as? [String: Any])?["type"] as? String, "enabled")
        XCTAssertEqual((body["output_config"] as? [String: Any])?["effort"] as? String, "high")
        XCTAssertNotNil(body["max_tokens"])
        XCTAssertEqual(result.translatedText, "你好。")
    }

    private func assertServiceError(
        _ expected: ServiceError,
        operation: () async throws -> TranslationResult
    ) async {
        do {
            _ = try await operation()
            XCTFail("Expected \(expected)")
        } catch {
            XCTAssertEqual(error as? ServiceError, expected)
        }
    }
}

private actor BackendHTTPClientStub: BackendTranslationHTTPClient {
    private let statusCode: Int
    private let body: Data
    private var capturedRequest: URLRequest?

    init(statusCode: Int, body: Data) {
        self.statusCode = statusCode
        self.body = body
    }

    func data(for request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        capturedRequest = request
        let response = HTTPURLResponse(
            url: request.url!,
            statusCode: statusCode,
            httpVersion: "HTTP/1.1",
            headerFields: ["Content-Type": "application/json"]
        )!
        return (body, response)
    }

    func lastRequest() -> URLRequest? {
        capturedRequest
    }
}
