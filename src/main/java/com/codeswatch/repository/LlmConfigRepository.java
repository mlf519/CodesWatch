package com.codeswatch.repository;

import com.codeswatch.entity.LlmConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface LlmConfigRepository extends JpaRepository<LlmConfig, Long> {

    Optional<LlmConfig> findByIsDefaultTrue();
}