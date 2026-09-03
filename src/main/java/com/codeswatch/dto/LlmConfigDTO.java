package com.codeswatch.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmConfigDTO {

    private Long id;
    private String configName;
    private String providerName;
    private String baseUrl;
    private String apiKey;
    private String model;
    private Boolean thinkingEnabled;
    private Boolean enabled;
    private Boolean isDefault;
}