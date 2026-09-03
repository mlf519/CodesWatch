package com.codeswatch.controller;

import com.codeswatch.dto.PromptConfigDTO;
import com.codeswatch.service.PromptConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/prompt-config")
@RequiredArgsConstructor
public class PromptConfigController {

    private final PromptConfigService promptConfigService;

    @GetMapping
    public ResponseEntity<List<PromptConfigDTO>> getAllConfigs() {
        return ResponseEntity.ok(promptConfigService.getAllConfigs());
    }

    @GetMapping("/phase/{phase}")
    public ResponseEntity<List<PromptConfigDTO>> getConfigsByPhase(@PathVariable String phase) {
        return ResponseEntity.ok(promptConfigService.getConfigsByPhase(phase));
    }

    @GetMapping("/{id}")
    public ResponseEntity<PromptConfigDTO> getConfig(@PathVariable Long id) {
        return ResponseEntity.ok(promptConfigService.getConfig(id));
    }

    @PostMapping
    public ResponseEntity<PromptConfigDTO> createConfig(@RequestBody PromptConfigDTO dto) {
        return ResponseEntity.ok(promptConfigService.createOrUpdateConfig(dto));
    }

    @PutMapping("/{id}")
    public ResponseEntity<PromptConfigDTO> updateConfig(
            @PathVariable Long id,
            @RequestBody PromptConfigDTO dto) {
        dto.setId(id);
        return ResponseEntity.ok(promptConfigService.createOrUpdateConfig(dto));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, String>> deleteConfig(@PathVariable Long id) {
        promptConfigService.deleteConfig(id);
        return ResponseEntity.ok(Map.of("message", "配置删除成功"));
    }
}
