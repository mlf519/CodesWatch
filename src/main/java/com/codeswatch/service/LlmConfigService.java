package com.codeswatch.service;

import com.codeswatch.dto.LlmConfigDTO;
import com.codeswatch.entity.LlmConfig;
import com.codeswatch.repository.LlmConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class LlmConfigService {

    private final LlmConfigRepository llmConfigRepository;

    /**
     * 新建配置：不做按厂商合并，允许同一厂商类型存在多条配置
     */
    @Transactional
    public LlmConfigDTO createConfig(LlmConfigDTO dto) {
        LlmConfig config = new LlmConfig();
        applyFields(config, dto);
        handleDefaultOnSave(config, dto);
        config = llmConfigRepository.save(config);
        return convertToDTO(config);
    }

    /**
     * 按 id 更新配置，不影响其他配置
     */
    @Transactional
    public LlmConfigDTO updateConfig(Long id, LlmConfigDTO dto) {
        LlmConfig config = llmConfigRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("配置不存在"));
        applyFields(config, dto);
        handleDefaultOnSave(config, dto);
        config = llmConfigRepository.save(config);
        return convertToDTO(config);
    }

    private void applyFields(LlmConfig config, LlmConfigDTO dto) {
        config.setConfigName(blankToDefault(dto.getConfigName(), dto.getProviderName()));
        config.setProviderName(dto.getProviderName());
        config.setBaseUrl(dto.getBaseUrl());
        config.setApiKey(dto.getApiKey());
        config.setModel(dto.getModel());
        config.setThinkingEnabled(Boolean.TRUE.equals(dto.getThinkingEnabled()));
        config.setEnabled(dto.getEnabled());
    }

    private void handleDefaultOnSave(LlmConfig config, LlmConfigDTO dto) {
        if (Boolean.TRUE.equals(dto.getIsDefault())) {
            llmConfigRepository.findByIsDefaultTrue().ifPresent(c -> {
                c.setIsDefault(false);
                llmConfigRepository.save(c);
            });
            config.setIsDefault(true);
        } else if (config.getIsDefault() == null) {
            config.setIsDefault(false);
        }
    }

    private String blankToDefault(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value.trim();
    }

    @Transactional(readOnly = true)
    public List<LlmConfigDTO> getAllConfigs() {
        return llmConfigRepository.findAll().stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public LlmConfigDTO getConfig(Long id) {
        LlmConfig config = llmConfigRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("配置不存在"));
        return convertToDTO(config);
    }

    @Transactional
    public void deleteConfig(Long id) {
        LlmConfig config = llmConfigRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("配置不存在"));
        llmConfigRepository.delete(config);
    }

    @Transactional
    public void setDefault(Long id) {
        LlmConfig config = llmConfigRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("配置不存在"));

        llmConfigRepository.findByIsDefaultTrue().ifPresent(c -> {
            c.setIsDefault(false);
            llmConfigRepository.save(c);
        });

        config.setIsDefault(true);
        llmConfigRepository.save(config);
    }

    private LlmConfigDTO convertToDTO(LlmConfig config) {
        return LlmConfigDTO.builder()
                .id(config.getId())
                .configName(config.getConfigName())
                .providerName(config.getProviderName())
                .baseUrl(config.getBaseUrl())
                .apiKey(maskApiKey(config.getApiKey()))
                .model(config.getModel())
                .thinkingEnabled(config.getThinkingEnabled())
                .enabled(config.getEnabled())
                .isDefault(config.getIsDefault())
                .build();
    }

    private String maskApiKey(String apiKey) {
        if (apiKey == null || apiKey.length() <= 6) {
            return "******";
        }
        return apiKey.substring(0, 3) + "******" + apiKey.substring(apiKey.length() - 3);
    }
}