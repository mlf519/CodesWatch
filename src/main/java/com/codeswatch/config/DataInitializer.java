package com.codeswatch.config;

import com.codeswatch.entity.LlmConfig;
import com.codeswatch.repository.LlmConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Slf4j
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final LlmConfigRepository llmConfigRepository;

    @Override
    public void run(String... args) {
        if (llmConfigRepository.count() == 0) {
            log.info("LlmConfig表为空，正在插入默认配置...");

            insertConfig("openai", "https://api.openai.com/v1", "gpt-4o", true);
            insertConfig("qwen", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-turbo", false);
            insertConfig("doubao", "https://ark.cn-beijing.volces.com/api/v3", "doubao-pro-32k", false);
            insertConfig("zhipu", "https://open.bigmodel.cn/api/paas/v4", "glm-4", false);
            insertConfig("moonshot", "https://api.moonshot.cn/v1", "moonshot-v1-8k", false);
            insertConfig("baidu", "https://aip.baidubce.com/rpc/2.0/ai_custom/v1/wenxinworkshop/chat", "ernie-4.0", false);
            insertConfig("gemini", "https://generativelanguage.googleapis.com/v1", "gemini-1.5-pro", false);

            log.info("默认LLM配置插入完成");
        } else {
            log.info("LlmConfig表已有数据，跳过默认配置插入");
        }
    }

    private void insertConfig(String providerName, String baseUrl, String model, boolean isDefault) {
        LlmConfig config = LlmConfig.builder()
                .configName(providerName)
                .providerName(providerName)
                .baseUrl(baseUrl)
                .model(model)
                .enabled(false)
                .isDefault(isDefault)
                .build();
        llmConfigRepository.save(config);
        log.info("插入配置: provider={}, model={}", providerName, model);
    }
}