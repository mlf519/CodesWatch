package com.codeswatch.service;

import com.codeswatch.dto.PromptConfigDTO;
import com.codeswatch.entity.PromptConfig;
import com.codeswatch.repository.PromptConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class PromptConfigService {

    private final PromptConfigRepository promptConfigRepository;

    @Transactional(readOnly = true)
    public List<PromptConfigDTO> getAllConfigs() {
        return promptConfigRepository.findAll().stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<PromptConfigDTO> getConfigsByPhase(String phase) {
        PromptConfig.ScanPhase scanPhase = PromptConfig.ScanPhase.valueOf(phase);
        return promptConfigRepository.findByPhase(scanPhase).stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public PromptConfigDTO getConfig(Long id) {
        PromptConfig config = promptConfigRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("提示词配置不存在"));
        return convertToDTO(config);
    }

    /**
     * 获取全局配置的系统提示词拼接
     */
    @Transactional(readOnly = true)
    public String buildGlobalSystemPrompt() {
        List<PromptConfig> globalConfigs = promptConfigRepository.findByPhaseAndEnabledOrderByDisplayOrderAsc(
                PromptConfig.ScanPhase.GLOBAL_CONFIG, true);
        
        if (globalConfigs.isEmpty()) {
            return null;
        }
        
        StringBuilder sb = new StringBuilder();
        for (PromptConfig config : globalConfigs) {
            if (config.getSystemPrompt() != null && !config.getSystemPrompt().isEmpty()) {
                if (sb.length() > 0) {
                    sb.append("\n\n");
                }
                sb.append(config.getSystemPrompt());
            }
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    /**
     * 获取全局配置的前缀提示词拼接
     */
    @Transactional(readOnly = true)
    public String buildGlobalPrefixPrompt() {
        List<PromptConfig> globalConfigs = promptConfigRepository.findByPhaseAndEnabledOrderByDisplayOrderAsc(
                PromptConfig.ScanPhase.GLOBAL_CONFIG, true);
        
        if (globalConfigs.isEmpty()) {
            return null;
        }
        
        StringBuilder sb = new StringBuilder();
        for (PromptConfig config : globalConfigs) {
            if (config.getPrefixPrompt() != null && !config.getPrefixPrompt().isEmpty()) {
                if (sb.length() > 0) {
                    sb.append("\n\n");
                }
                sb.append(config.getPrefixPrompt());
            }
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    /**
     * 获取全局配置的后缀提示词拼接
     */
    @Transactional(readOnly = true)
    public String buildGlobalSuffixPrompt() {
        List<PromptConfig> globalConfigs = promptConfigRepository.findByPhaseAndEnabledOrderByDisplayOrderAsc(
                PromptConfig.ScanPhase.GLOBAL_CONFIG, true);
        
        if (globalConfigs.isEmpty()) {
            return null;
        }
        
        StringBuilder sb = new StringBuilder();
        for (PromptConfig config : globalConfigs) {
            if (config.getSuffixPrompt() != null && !config.getSuffixPrompt().isEmpty()) {
                if (sb.length() > 0) {
                    sb.append("\n\n");
                }
                sb.append(config.getSuffixPrompt());
            }
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    @Transactional(readOnly = true)
    public List<PromptConfig> getEnabledPromptConfigEntitiesByPhase(String phase) {
        PromptConfig.ScanPhase scanPhase = PromptConfig.ScanPhase.valueOf(phase);
        return promptConfigRepository.findByPhaseAndEnabledOrderByDisplayOrderAsc(scanPhase, true);
    }

    /**
     * 获取指定阶段的第一个启用配置
     */
    @Transactional(readOnly = true)
    public PromptConfig getPromptConfigEntityByPhase(String phase) {
        PromptConfig.ScanPhase scanPhase = PromptConfig.ScanPhase.valueOf(phase);
        List<PromptConfig> configs = promptConfigRepository.findByPhaseAndEnabledOrderByDisplayOrderAsc(scanPhase, true);
        return configs.isEmpty() ? null : configs.get(0);
    }

    @Transactional
    public PromptConfigDTO createOrUpdateConfig(PromptConfigDTO dto) {
        PromptConfig config;
        
        if (dto.getId() != null) {
            config = promptConfigRepository.findById(dto.getId())
                    .orElseThrow(() -> new RuntimeException("提示词配置不存在"));
        } else {
            config = new PromptConfig();
        }

        config.setPhase(PromptConfig.ScanPhase.valueOf(dto.getPhase()));
        config.setName(dto.getName());
        config.setSystemPrompt(dto.getSystemPrompt());
        config.setAnalysisPrompt(dto.getAnalysisPrompt());
        config.setPrefixPrompt(dto.getPrefixPrompt());
        config.setSuffixPrompt(dto.getSuffixPrompt());
        config.setVulnerabilityTypes(dto.getVulnerabilityTypes());
        config.setVulnerabilityPrompts(dto.getVulnerabilityPrompts());
        config.setDangerousFunctionPatterns(dto.getDangerousFunctionPatterns());
        config.setEnabled(dto.getEnabled() != null ? dto.getEnabled() : true);
        config.setDisplayOrder(dto.getDisplayOrder() != null ? dto.getDisplayOrder() : 0);

        config = promptConfigRepository.saveAndFlush(config);
        return convertToDTO(config);
    }

    @Transactional
    public void deleteConfig(Long id) {
        if (!promptConfigRepository.existsById(id)) {
            throw new RuntimeException("提示词配置不存在");
        }
        promptConfigRepository.deleteById(id);
    }

    private PromptConfigDTO convertToDTO(PromptConfig config) {
        return PromptConfigDTO.builder()
                .id(config.getId())
                .phase(config.getPhase().name())
                .name(config.getName())
                .systemPrompt(config.getSystemPrompt())
                .analysisPrompt(config.getAnalysisPrompt())
                .prefixPrompt(config.getPrefixPrompt())
                .suffixPrompt(config.getSuffixPrompt())
                .vulnerabilityTypes(config.getVulnerabilityTypes())
                .vulnerabilityPrompts(config.getVulnerabilityPrompts())
                .dangerousFunctionPatterns(config.getDangerousFunctionPatterns())
                .enabled(config.getEnabled())
                .displayOrder(config.getDisplayOrder())
                .createdAt(config.getCreatedAt())
                .updatedAt(config.getUpdatedAt())
                .build();
    }
}
