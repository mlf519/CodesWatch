package com.codeswatch.repository;

import com.codeswatch.entity.PromptConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PromptConfigRepository extends JpaRepository<PromptConfig, Long> {

    List<PromptConfig> findByPhase(PromptConfig.ScanPhase phase);

    List<PromptConfig> findByPhaseAndEnabledOrderByDisplayOrderAsc(PromptConfig.ScanPhase phase, Boolean enabled);
}
