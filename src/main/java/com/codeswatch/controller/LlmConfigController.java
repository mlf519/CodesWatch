package com.codeswatch.controller;

import com.codeswatch.dto.LlmConfigDTO;
import com.codeswatch.entity.LlmConfig;
import com.codeswatch.repository.LlmConfigRepository;
import com.codeswatch.service.LlmConfigService;
import com.codeswatch.service.LlmService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/llm-config")
@RequiredArgsConstructor
public class LlmConfigController {

    private final LlmConfigService llmConfigService;
    private final LlmService llmService;
    private final LlmConfigRepository llmConfigRepository;

    @GetMapping
    public ResponseEntity<List<LlmConfigDTO>> getAllConfigs() {
        return ResponseEntity.ok(llmConfigService.getAllConfigs());
    }

    @GetMapping("/{id}")
    public ResponseEntity<LlmConfigDTO> getConfig(@PathVariable Long id) {
        return ResponseEntity.ok(llmConfigService.getConfig(id));
    }

    @GetMapping("/enabled")
    public ResponseEntity<List<LlmConfigDTO>> getEnabledConfigs() {
        List<LlmConfigDTO> configs = llmConfigService.getAllConfigs();
        List<LlmConfigDTO> enabled = configs.stream()
                .filter(c -> Boolean.TRUE.equals(c.getEnabled()))
                .toList();
        return ResponseEntity.ok(enabled);
    }

    @PostMapping
    public ResponseEntity<LlmConfigDTO> createConfig(@RequestBody LlmConfigDTO dto) {
        return ResponseEntity.ok(llmConfigService.createConfig(dto));
    }

    @PutMapping("/{id}")
    public ResponseEntity<LlmConfigDTO> updateConfig(
            @PathVariable Long id, 
            @RequestBody LlmConfigDTO dto) {
        return ResponseEntity.ok(llmConfigService.updateConfig(id, dto));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteConfig(@PathVariable Long id) {
        llmConfigService.deleteConfig(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/set-default")
    public ResponseEntity<Void> setDefault(@PathVariable Long id) {
        llmConfigService.setDefault(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/test-connection")
    public ResponseEntity<Map<String, Object>> testConnection(@PathVariable Long id) {
        Map<String, Object> result = new HashMap<>();
        try {
            LlmConfig config = llmConfigRepository.findById(id)
                    .orElseThrow(() -> new RuntimeException("配置不存在"));
            boolean success = llmService.testConnection(config);
            result.put("success", success);
            result.put("message", success ? "连接测试成功" : "连接测试失败，请检查配置");
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "连接测试失败: " + e.getMessage());
        }
        return ResponseEntity.ok(result);
    }
}