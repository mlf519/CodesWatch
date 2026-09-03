package com.codeswatch.service;

import com.codeswatch.dto.LlmResponse;
import com.codeswatch.entity.LlmConfig;
import com.codeswatch.entity.PromptConfig;
import com.codeswatch.repository.LlmConfigRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
@Slf4j
@RequiredArgsConstructor
public class LlmService {

    private final LlmConfigRepository llmConfigRepository;
    private final ObjectMapper objectMapper;
    private final PromptConfigService promptConfigService;

    private static final int CONNECT_TIMEOUT = 10000;
    private static final int SOCKET_TIMEOUT = 300000;
    private static final int MAX_CODE_LENGTH = 500000;
    private static final int ANALYSIS_TIMEOUT = 60000;
    private static final int THINKING_ANALYSIS_TIMEOUT = 120000;

    private CloseableHttpClient createHttpClient() {
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(CONNECT_TIMEOUT)
                .setSocketTimeout(SOCKET_TIMEOUT)
                .setConnectionRequestTimeout(CONNECT_TIMEOUT)
                .build();
        return HttpClients.custom()
                .setDefaultRequestConfig(requestConfig)
                .build();
    }

    public String analyzeCodeWithPromptAndTimeout(String code, String taskType, Long llmConfigId, PromptConfig promptConfig) {
        LlmConfig config = getConfig(llmConfigId);
        if (config == null) {
            throw new RuntimeException("未配置可用的LLM服务");
        }
        requirePromptConfig(promptConfig);

        String truncatedCode = truncateCode(code);
        String prompt = buildCustomAnalysisPrompt(truncatedCode, taskType, promptConfig);

        return callLlmWithTimeout(config, prompt, ANALYSIS_TIMEOUT);
    }

    /**
     * 分析代码漏洞，支持接口信息
     */
    public String analyzeCodeForVulnerabilityWithPrompt(String code, String vulnerabilityType, String taskType, Long llmConfigId, PromptConfig promptConfig, String interfaceInfo) {
        LlmConfig config = getConfig(llmConfigId);
        if (config == null) {
            throw new RuntimeException("未配置可用的LLM服务");
        }

        String truncatedCode = truncateCode(code);
        String prompt = buildPromptForVulnerability(truncatedCode, vulnerabilityType, taskType, promptConfig, interfaceInfo);
        
        return callLlmWithTimeout(config, prompt, ANALYSIS_TIMEOUT);
    }

    /**
     * 构建包含接口信息的漏洞分析prompt
     * @param code 代码内容
     * @param vulnerabilityType 漏洞类型
     * @param taskType 任务类型
     * @param promptConfig 提示词配置
     * @param interfaceInfo 接口信息，格式如 "GET /api/users" 或接口描述
     */
    public String buildPromptForVulnerability(String code, String vulnerabilityType, String taskType, PromptConfig promptConfig, String interfaceInfo) {
        // 优先尝试使用漏洞类型专用提示词
        String vulnSpecificPrompt = getVulnerabilitySpecificPrompt(vulnerabilityType, promptConfig);
        if (vulnSpecificPrompt != null && !vulnSpecificPrompt.isEmpty()) {
            return buildPromptWithVulnerabilitySpecific(code, vulnerabilityType, taskType, promptConfig, vulnSpecificPrompt, interfaceInfo);
        }

        // 回退到通用分析提示词
        if (promptConfig != null && promptConfig.getAnalysisPrompt() != null && !promptConfig.getAnalysisPrompt().isEmpty()) {
            return buildCustomVulnerabilityAnalysisPrompt(code, vulnerabilityType, taskType, promptConfig, interfaceInfo);
        }

        throw new RuntimeException("未配置提示词，无法执行分析");
    }

    /**
     * 校验提示词配置，未配置时直接拒绝（未配置提示词的阶段不会创建任务，此为兜底防护）
     */
    private void requirePromptConfig(PromptConfig promptConfig) {
        if (promptConfig == null || promptConfig.getAnalysisPrompt() == null || promptConfig.getAnalysisPrompt().isEmpty()) {
            throw new RuntimeException("未配置提示词，无法执行分析");
        }
    }

    /**
     * 从PromptConfig的vulnerabilityPrompts字段获取指定漏洞类型的专用提示词
     */
    private String getVulnerabilitySpecificPrompt(String vulnerabilityType, PromptConfig config) {
        if (config == null || config.getVulnerabilityPrompts() == null || config.getVulnerabilityPrompts().isEmpty()) {
            return null;
        }
        
        try {
            Map<String, String> prompts = objectMapper.readValue(
                config.getVulnerabilityPrompts(),
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, String>>() {});
            return prompts.get(vulnerabilityType);
        } catch (Exception e) {
            log.warn("解析漏洞类型专用提示词失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 使用漏洞类型专用提示词构建完整的prompt，包含接口信息
     */
    private String buildPromptWithVulnerabilitySpecific(String code, String vulnerabilityType,
                                                        String taskType, PromptConfig config,
                                                        String vulnSpecificPrompt,
                                                        String interfaceInfo) {
        StringBuilder prompt = newPromptBuilder(config, "你是一个专业的代码安全审计专家。请针对指定的漏洞类型分析以下代码。");

        // 前置：接口信息和漏洞类型
        if (interfaceInfo != null && !interfaceInfo.isEmpty()) {
            prompt.append("【目标接口】\n");
            prompt.append(interfaceInfo).append("\n\n");
        }
        prompt.append("【扫描漏洞类型】\n");
        prompt.append(vulnerabilityType).append("\n\n");

        // 中间：提示词配置的漏洞对应的分析提示词
        prompt.append("【针对此漏洞的分析要求】\n");
        prompt.append(vulnSpecificPrompt).append("\n\n");

        // 最后：接口所在的文件（代码内容）
        prompt.append("【接口所在文件代码】\n");
        prompt.append(code).append("\n");

        // 4. 全局配置的后置提示词
        appendGlobalSuffix(prompt);

        return prompt.toString();
    }

    private LlmConfig getConfig(Long llmConfigId) {
        if (llmConfigId != null) {
            // 先按ID查找，不强制要求enabled=true（允许使用已配置的模型）
            return llmConfigRepository.findById(llmConfigId).orElse(null);
        }
        // 回退: 优先使用enabled=1的默认配置，否则使用任何可用配置
        return llmConfigRepository.findByIsDefaultTrue()
                .orElseGet(() -> llmConfigRepository.findAll().stream()
                        .filter(c -> Boolean.TRUE.equals(c.getEnabled()))
                        .findFirst()
                        .orElseGet(() -> llmConfigRepository.findAll().stream().findFirst().orElse(null)));
    }

    public String getLlmConfigName(Long llmConfigId) {
        if (llmConfigId == null) {
            LlmConfig defaultConfig = llmConfigRepository.findByIsDefaultTrue()
                    .orElseGet(() -> llmConfigRepository.findAll().stream().findFirst().orElse(null));
            return defaultConfig != null ? defaultConfig.getProviderName() + " - " + defaultConfig.getModel() : "默认模型";
        }
        return llmConfigRepository.findById(llmConfigId)
                .map(c -> c.getProviderName() + " - " + c.getModel())
                .orElse("未知模型");
    }

    public boolean testConnection(LlmConfig config) {
        String prompt = "请回复'连接成功'四个字。";
        try {
            String response = callLlm(config, prompt);
            return response != null && !response.isEmpty();
        } catch (Exception e) {
            log.warn("LLM连接测试失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 构建prompt公共头部：全局系统提示词 + 全局前缀提示词 + 配置的系统提示词（无配置时使用默认文案）
     */
    private StringBuilder newPromptBuilder(PromptConfig config, String defaultSystemPrompt) {
        StringBuilder prompt = new StringBuilder();

        // 0. 全局配置的系统提示词
        String globalSystemPrompt = promptConfigService.buildGlobalSystemPrompt();
        if (globalSystemPrompt != null && !globalSystemPrompt.isEmpty()) {
            prompt.append(globalSystemPrompt).append("\n\n");
        }

        // 0.1 全局配置的前缀提示词
        String globalPrefixPrompt = promptConfigService.buildGlobalPrefixPrompt();
        if (globalPrefixPrompt != null && !globalPrefixPrompt.isEmpty()) {
            prompt.append(globalPrefixPrompt).append("\n\n");
        }

        // 1. 当前配置的 systemPrompt
        if (config != null && config.getSystemPrompt() != null && !config.getSystemPrompt().isEmpty()) {
            prompt.append(config.getSystemPrompt()).append("\n\n");
        } else {
            prompt.append(defaultSystemPrompt).append("\n\n");
        }
        return prompt;
    }

    /**
     * 追加prompt公共尾部：全局后置提示词
     */
    private void appendGlobalSuffix(StringBuilder prompt) {
        String globalSuffixPrompt = promptConfigService.buildGlobalSuffixPrompt();
        if (globalSuffixPrompt != null && !globalSuffixPrompt.isEmpty()) {
            prompt.append("\n\n").append(globalSuffixPrompt);
        }
    }

    private String buildCustomAnalysisPrompt(String code, String taskType, PromptConfig config) {
        StringBuilder prompt = newPromptBuilder(config, "你是一个专业的代码安全审计专家。请分析以下代码，识别其中的安全漏洞。");

        prompt.append("任务类型: ").append(taskType).append("\n\n");
        prompt.append("代码内容:\n").append(code).append("\n\n");

        // 3. 当前配置的 analysisPrompt（入口处已校验非空）
        prompt.append(config.getAnalysisPrompt()).append("\n\n");

        // 4. 全局配置的后置提示词
        appendGlobalSuffix(prompt);

        return prompt.toString();
    }

    private String buildCustomVulnerabilityAnalysisPrompt(String code, String vulnerabilityType, String taskType, PromptConfig config, String interfaceInfo) {
        StringBuilder prompt = newPromptBuilder(config, "你是一个专业的代码安全审计专家。请针对指定的漏洞类型分析以下代码。");

        // 前置：接口信息和漏洞类型
        if (interfaceInfo != null && !interfaceInfo.isEmpty()) {
            prompt.append("【目标接口】\n");
            prompt.append(interfaceInfo).append("\n\n");
        }
        prompt.append("【扫描漏洞类型】\n");
        prompt.append(vulnerabilityType).append("\n\n");

        // 中间：提示词配置的漏洞对应的分析提示词
        if (config.getAnalysisPrompt() != null && !config.getAnalysisPrompt().isEmpty()) {
            prompt.append("【针对此漏洞的分析要求】\n");
            prompt.append(config.getAnalysisPrompt()).append("\n\n");
        }

        // 最后：接口所在的文件代码
        prompt.append("【接口所在文件代码】\n");
        prompt.append(code).append("\n");

        // 4. 全局配置的后置提示词
        appendGlobalSuffix(prompt);

        return prompt.toString();
    }

    private String callLlm(LlmConfig config, String prompt) {
        String url = getApiUrl(config);
        
        try (CloseableHttpClient httpClient = createHttpClient()) {
            HttpPost httpPost = new HttpPost(url);
            httpPost.setHeader("Content-Type", "application/json");
            httpPost.setHeader("Authorization", "Bearer " + config.getApiKey());

            String body = buildRequestBody(config, prompt);
            httpPost.setEntity(new StringEntity(body, StandardCharsets.UTF_8));

            try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
                int statusCode = response.getStatusLine().getStatusCode();
                
                if (statusCode == 429) {
                    throw new RuntimeException("LLM服务请求过于频繁(限流)，请稍后重试");
                }
                if (statusCode == 401) {
                    throw new RuntimeException("LLM服务认证失败，请检查API密钥配置");
                }
                if (statusCode == 403) {
                    throw new RuntimeException("LLM服务访问被拒绝，请检查账户余额和权限");
                }
                if (statusCode >= 400) {
                    String errorBody = readErrorBody(response);
                    throw new RuntimeException("LLM服务返回错误(" + statusCode + "): " + errorBody);
                }

                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(response.getEntity().getContent(), StandardCharsets.UTF_8));
                StringBuilder result = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    result.append(line);
                }
                return parseResponse(result.toString(), config.getProviderName());
            }
        } catch (Exception e) {
            log.error("LLM调用失败: {}", e.getMessage());
            throw new RuntimeException("LLM服务调用失败: " + e.getMessage(), e);
        }
    }

    private String callLlmWithTimeout(LlmConfig config, String prompt, int timeoutMs) {
        String url = getApiUrl(config);
        
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<String> future = executor.submit(() -> {
            try (CloseableHttpClient httpClient = createHttpClient()) {
                HttpPost httpPost = new HttpPost(url);
                httpPost.setHeader("Content-Type", "application/json");
                httpPost.setHeader("Authorization", "Bearer " + config.getApiKey());

                String body = buildRequestBody(config, prompt);
                httpPost.setEntity(new StringEntity(body, StandardCharsets.UTF_8));

                try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
                    int statusCode = response.getStatusLine().getStatusCode();
                    
                    if (statusCode == 429) {
                        throw new RuntimeException("LLM服务请求过于频繁(限流)，请稍后重试");
                    }
                    if (statusCode == 401) {
                        throw new RuntimeException("LLM服务认证失败，请检查API密钥配置");
                    }
                    if (statusCode == 403) {
                        throw new RuntimeException("LLM服务访问被拒绝，请检查账户余额和权限");
                    }
                    if (statusCode >= 400) {
                        String errorBody = readErrorBody(response);
                        throw new RuntimeException("LLM服务返回错误(" + statusCode + "): " + errorBody);
                    }

                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(response.getEntity().getContent(), StandardCharsets.UTF_8));
                    StringBuilder result = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        result.append(line);
                    }
                    return parseResponse(result.toString(), config.getProviderName());
                }
            }
        });

        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("LLM调用超时({}ms)", timeoutMs);
            throw new RuntimeException("LLM服务响" +
                    "应超时(" + timeoutMs + "ms)，请稍后重试或检查网络连接");
        } catch (Exception e) {
            log.error("LLM调用失败: {}", e.getMessage());
            throw new RuntimeException("LLM服务调用失败: " + e.getMessage(), e);
        } finally {
            executor.shutdownNow();
        }
    }

    private String readErrorBody(CloseableHttpResponse response) {
        try {
            if (response.getEntity() != null) {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(response.getEntity().getContent(), StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                return sb.substring(0, Math.min(sb.length(), 500));
            }
        } catch (Exception e) {
            return "无法读取错误详情";
        }
        return "";
    }

    private String truncateCode(String code) {
        if (code == null || code.length() <= MAX_CODE_LENGTH) {
            return code;
        }
        log.warn("代码过长({}字符)，截断至{}字符", code.length(), MAX_CODE_LENGTH);
        String truncated = code.substring(0, MAX_CODE_LENGTH);
        int lastNewline = truncated.lastIndexOf('\n');
        if (lastNewline > 0) {
            truncated = truncated.substring(0, lastNewline);
        }
        return truncated + "\n\n[代码过长已截断]";
    }

    private String getApiUrl(LlmConfig config) {
        return switch (config.getProviderName()) {
            case "openai", "qwen", "doubao", "moonshot", "zhipu", "deepseek", "mistral", "perplexity", "groq", "ollama", "anthropic" -> config.getBaseUrl() + "/chat/completions";
            case "gemini" -> config.getBaseUrl() + "/models/" + config.getModel() + ":streamGenerateContent";
            case "baidu" -> config.getBaseUrl() + "/" + config.getModel();
            default -> config.getBaseUrl() + "/chat/completions";
        };
    }

    private String buildRequestBody(LlmConfig config, String prompt) {
        try {
            Map<String, Object> requestBody = new LinkedHashMap<>();
            String provider = config.getProviderName();

            if ("gemini".equals(provider)) {
                Map<String, Object> content = new LinkedHashMap<>();
                content.put("parts", List.of(Map.of("text", prompt)));
                requestBody.put("contents", List.of(content));
            } else if ("baidu".equals(provider)) {
                Map<String, Object> message = new LinkedHashMap<>();
                message.put("role", "user");
                message.put("content", prompt);
                requestBody.put("messages", List.of(message));
                requestBody.put("temperature", 0.1);
            } else if (isThinkingModel(config)) {
                // DeepSeek 思考模式请求体
                Map<String, Object> message = new LinkedHashMap<>();
                message.put("role", "user");
                message.put("content", prompt);
                requestBody.put("model", config.getModel());
                requestBody.put("messages", List.of(message));
                // 思考模式不支持 temperature, top_p 等参数
                Map<String, Object> thinking = new LinkedHashMap<>();
                thinking.put("type", "enabled");
                requestBody.put("thinking", thinking);
                requestBody.put("reasoning_effort", "high");
            } else {
                Map<String, Object> message = new LinkedHashMap<>();
                message.put("role", "user");
                message.put("content", prompt);
                requestBody.put("model", config.getModel());
                requestBody.put("messages", List.of(message));
                requestBody.put("temperature", 0.1);
                requestBody.put("max_tokens", 8192);
            }

            return objectMapper.writeValueAsString(requestBody);
        } catch (Exception e) {
            throw new RuntimeException("构建请求体失败", e);
        }
    }
    
    /**
     * 检查是否为支持思考模式的模型（由大模型配置的 thinkingEnabled 决定）
     */
    private boolean isThinkingModel(LlmConfig config) {
        return config != null && Boolean.TRUE.equals(config.getThinkingEnabled());
    }
    
    /**
     * 根据配置ID检查是否为支持思考模式的模型
     */
    public boolean isThinkingModel(Long llmConfigId) {
        LlmConfig config = getConfig(llmConfigId);
        return isThinkingModel(config);
    }

    private String parseResponse(String response, String provider) {
        try {
            String cleanResponse = extractJson(response);
            if (cleanResponse == null || cleanResponse.isEmpty()) {
                log.warn("LLM响应为空，返回原始响应");
                return response;
            }
            JsonNode root = objectMapper.readTree(cleanResponse);
            
            return switch (provider) {
                case "openai", "qwen", "doubao", "moonshot", "zhipu", "deepseek", "mistral", "perplexity", "groq", "ollama", "anthropic" -> {
                    JsonNode contentNode = root.at("/choices/0/message/content");
                    yield contentNode.isMissingNode() ? cleanResponse : contentNode.asText();
                }
                case "gemini" -> {
                    JsonNode textNode = root.at("/candidates/0/content/parts/0/text");
                    yield textNode.isMissingNode() ? cleanResponse : textNode.asText();
                }
                case "baidu" -> {
                    JsonNode resultNode = root.at("/result");
                    yield resultNode.isMissingNode() ? cleanResponse : resultNode.asText();
                }
                default -> cleanResponse;
            };
        } catch (Exception e) {
            log.warn("解析LLM响应失败，返回原始响应");
            return response;
        }
    }
    
    /**
     * 解析响应，同时提取思考过程和最终回答
     * 用于DeepSeek等支持思考模式的模型
     */
    public LlmResponse parseResponseWithThinking(String response, String provider) {
        try {
            String cleanResponse = extractJson(response);
            if (cleanResponse == null || cleanResponse.isEmpty()) {
                return LlmResponse.builder().content(response).build();
            }
            JsonNode root = objectMapper.readTree(cleanResponse);
            
            String content = null;
            String reasoningContent = null;
            
            switch (provider) {
                case "deepseek", "openai", "qwen", "doubao", "moonshot", "zhipu", "mistral", "perplexity", "groq", "ollama", "anthropic" -> {
                    JsonNode contentNode = root.at("/choices/0/message/content");
                    content = contentNode.isMissingNode() ? cleanResponse : contentNode.asText();
                    
                    // 提取reasoning_content（思考过程）
                    JsonNode reasoningNode = root.at("/choices/0/message/reasoning_content");
                    if (!reasoningNode.isMissingNode() && reasoningNode.asText() != null && !reasoningNode.asText().isEmpty()) {
                        reasoningContent = reasoningNode.asText();
                    }
                }
                case "gemini" -> {
                    JsonNode textNode = root.at("/candidates/0/content/parts/0/text");
                    content = textNode.isMissingNode() ? cleanResponse : textNode.asText();
                }
                case "baidu" -> {
                    JsonNode resultNode = root.at("/result");
                    content = resultNode.isMissingNode() ? cleanResponse : resultNode.asText();
                }
                default -> content = cleanResponse;
            }

            // 思考模型偶发将完整回答（含 end 结论标签）写入 reasoning_content 而正文 content 为空，
            // 此时以思考内容作为回复正文，避免最终结论丢失导致任务被判为 INCONCLUSIVE
            if ((content == null || content.isBlank()) && reasoningContent != null && !reasoningContent.isBlank()) {
                log.info("LLM正文为空但思考内容包含完整回答，已将思考内容作为回复正文使用");
                content = reasoningContent;
            }

            return LlmResponse.builder()
                    .content(content)
                    .reasoningContent(reasoningContent)
                    .build();
        } catch (Exception e) {
            log.warn("解析LLM响应失败，返回原始响应");
            return LlmResponse.builder().content(response).build();
        }
    }

    private String extractJson(String text) {
        if (text == null) return null;
        text = text.trim();
        
        if (text.isEmpty()) return null;

        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            if (firstNewline > 0) {
                text = text.substring(firstNewline + 1);
            }
            if (text.endsWith("```")) {
                text = text.substring(0, text.length() - 3).trim();
            } else if (text.contains("```")) {
                int lastFence = text.lastIndexOf("```");
                text = text.substring(0, lastFence).trim();
            }
        }

        int jsonStart = text.indexOf('{');
        int jsonEnd = text.lastIndexOf('}');
        if (jsonStart >= 0 && jsonEnd > jsonStart) {
            text = text.substring(jsonStart, jsonEnd + 1);
        }

        return text;
    }

    /**
     * 构建多轮对话的请求体
     */
    private String buildMultiTurnRequestBody(LlmConfig config, List<Map<String, String>> messages) {
        try {
            Map<String, Object> requestBody = new LinkedHashMap<>();
            String provider = config.getProviderName();

            if ("gemini".equals(provider)) {
                // Gemini 格式转换
                Map<String, Object> content = new LinkedHashMap<>();
                StringBuilder geminiText = new StringBuilder();
                for (Map<String, String> msg : messages) {
                    String role = msg.get("role");
                    String content_text = msg.get("content");
                    if ("system".equals(role)) {
                        geminiText.append("System: ").append(content_text).append("\n\n");
                    } else if ("user".equals(role)) {
                        geminiText.append("User: ").append(content_text).append("\n\n");
                    } else if ("assistant".equals(role)) {
                        geminiText.append("Assistant: ").append(content_text).append("\n\n");
                    }
                }
                content.put("parts", List.of(Map.of("text", geminiText.toString())));
                requestBody.put("contents", List.of(content));
            } else if ("baidu".equals(provider)) {
                Map<String, Object> message = new LinkedHashMap<>();
                StringBuilder baiduText = new StringBuilder();
                for (Map<String, String> msg : messages) {
                    String role = msg.get("role");
                    String content_text = msg.get("content");
                    baiduText.append(role).append(": ").append(content_text).append("\n\n");
                }
                message.put("role", "user");
                message.put("content", baiduText.toString());
                requestBody.put("messages", List.of(message));
                requestBody.put("temperature", 0.1);
            } else if (isThinkingModel(config)) {
                // DeepSeek 思考模式的多轮对话
                List<Map<String, Object>> formattedMessages = new java.util.ArrayList<>();
                for (Map<String, String> msg : messages) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("role", msg.get("role"));
                    m.put("content", msg.get("content"));
                    // 如果有reasoning_content，添加到消息中（在工具调用场景下需要）
                    if (msg.containsKey("reasoning_content") && msg.get("reasoning_content") != null) {
                        m.put("reasoning_content", msg.get("reasoning_content"));
                    }
                    formattedMessages.add(m);
                }
                requestBody.put("model", config.getModel());
                requestBody.put("messages", formattedMessages);
                // 思考模式不支持 temperature, top_p 等参数
                Map<String, Object> thinking = new LinkedHashMap<>();
                thinking.put("type", "enabled");
                requestBody.put("thinking", thinking);
                requestBody.put("reasoning_effort", "high");
            } else {
                // OpenAI 兼容格式
                List<Map<String, String>> formattedMessages = messages.stream()
                        .map(msg -> {
                            Map<String, String> m = new LinkedHashMap<>();
                            m.put("role", msg.get("role"));
                            m.put("content", msg.get("content"));
                            return m;
                        })
                        .toList();
                requestBody.put("model", config.getModel());
                requestBody.put("messages", formattedMessages);
                requestBody.put("temperature", 0.1);
                requestBody.put("max_tokens", 8192);
            }

            return objectMapper.writeValueAsString(requestBody);
        } catch (Exception e) {
            throw new RuntimeException("构建多轮对话请求体失败", e);
        }
    }
    
    /**
     * 带思考模式的多轮对话
     * @param messages 对话历史消息列表（可包含reasoning_content）
     * @param llmConfigId LLM配置ID
     * @param timeoutMs 超时时间
     * @return LlmResponse 包含思考过程和最终回答
     */
    public LlmResponse multiTurnChatWithThinking(List<Map<String, String>> messages, Long llmConfigId, int timeoutMs) {
        LlmConfig config = getConfig(llmConfigId);
        if (config == null) {
            throw new RuntimeException("未配置可用的LLM服务");
        }

        String url = getApiUrl(config);
        
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<LlmResponse> future = executor.submit(() -> {
            try (CloseableHttpClient httpClient = createHttpClient()) {
                HttpPost httpPost = new HttpPost(url);
                httpPost.setHeader("Content-Type", "application/json");
                httpPost.setHeader("Authorization", "Bearer " + config.getApiKey());

                String body = buildMultiTurnRequestBody(config, messages);
                httpPost.setEntity(new StringEntity(body, StandardCharsets.UTF_8));

                try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
                    int statusCode = response.getStatusLine().getStatusCode();
                    
                    if (statusCode == 429) {
                        throw new RuntimeException("LLM服务请求过于频繁(限流)，请稍后重试");
                    }
                    if (statusCode == 401) {
                        throw new RuntimeException("LLM服务认证失败，请检查API密钥配置");
                    }
                    if (statusCode == 403) {
                        throw new RuntimeException("LLM服务访问被拒绝，请检查账户余额和权限");
                    }
                    if (statusCode >= 400) {
                        String errorBody = readErrorBody(response);
                        throw new RuntimeException("LLM服务返回错误(" + statusCode + "): " + errorBody);
                    }

                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(response.getEntity().getContent(), StandardCharsets.UTF_8));
                    StringBuilder result = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        result.append(line);
                    }
                    return parseResponseWithThinking(result.toString(), config.getProviderName());
                }
            }
        });

        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("LLM多轮对话超时({}ms)", timeoutMs);
            throw new RuntimeException("LLM多轮对话超时(" + timeoutMs + "ms)");
        } catch (Exception e) {
            log.error("LLM多轮对话失败: {}", e.getMessage());
            throw new RuntimeException("LLM多轮对话失败: " + e.getMessage(), e);
        } finally {
            executor.shutdownNow();
        }
    }
    
    /**
     * 带思考模式的单次分析
     */
    public LlmResponse analyzeCodeWithThinking(String code, String taskType, Long llmConfigId, PromptConfig promptConfig) {
        LlmConfig config = getConfig(llmConfigId);
        if (config == null) {
            throw new RuntimeException("未配置可用的LLM服务");
        }
        requirePromptConfig(promptConfig);

        String truncatedCode = truncateCode(code);
        String prompt = buildCustomAnalysisPrompt(truncatedCode, taskType, promptConfig);

        return callLlmWithThinking(config, prompt, THINKING_ANALYSIS_TIMEOUT);
    }
    
    /**
     * 带思考模式的漏洞分析
     */
    public LlmResponse analyzeCodeForVulnerabilityWithThinking(String code, String vulnerabilityType, 
            String taskType, Long llmConfigId, PromptConfig promptConfig, String interfaceInfo) {
        LlmConfig config = getConfig(llmConfigId);
        if (config == null) {
            throw new RuntimeException("未配置可用的LLM服务");
        }

        String truncatedCode = truncateCode(code);
        String prompt = buildPromptForVulnerability(truncatedCode, vulnerabilityType, taskType, promptConfig, interfaceInfo);
        
        return callLlmWithThinking(config, prompt, THINKING_ANALYSIS_TIMEOUT);
    }
    
    /**
     * 带思考模式的LLM调用
     */
    private LlmResponse callLlmWithThinking(LlmConfig config, String prompt, int timeoutMs) {
        String url = getApiUrl(config);
        
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<LlmResponse> future = executor.submit(() -> {
            try (CloseableHttpClient httpClient = createHttpClient()) {
                HttpPost httpPost = new HttpPost(url);
                httpPost.setHeader("Content-Type", "application/json");
                httpPost.setHeader("Authorization", "Bearer " + config.getApiKey());

                String body = buildRequestBody(config, prompt);
                httpPost.setEntity(new StringEntity(body, StandardCharsets.UTF_8));

                try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
                    int statusCode = response.getStatusLine().getStatusCode();
                    
                    if (statusCode == 429) {
                        throw new RuntimeException("LLM服务请求过于频繁(限流)，请稍后重试");
                    }
                    if (statusCode == 401) {
                        throw new RuntimeException("LLM服务认证失败，请检查API密钥配置");
                    }
                    if (statusCode == 403) {
                        throw new RuntimeException("LLM服务访问被拒绝，请检查账户余额和权限");
                    }
                    if (statusCode >= 400) {
                        String errorBody = readErrorBody(response);
                        throw new RuntimeException("LLM服务返回错误(" + statusCode + "): " + errorBody);
                    }

                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(response.getEntity().getContent(), StandardCharsets.UTF_8));
                    StringBuilder result = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        result.append(line);
                    }
                    return parseResponseWithThinking(result.toString(), config.getProviderName());
                }
            }
        });

        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("LLM思考模式调用超时({}ms)", timeoutMs);
            throw new RuntimeException("LLM思考模式调用超时(" + timeoutMs + "ms)");
        } catch (Exception e) {
            log.error("LLM思考模式调用失败: {}", e.getMessage());
            throw new RuntimeException("LLM思考模式调用失败: " + e.getMessage(), e);
        } finally {
            executor.shutdownNow();
        }
    }
}