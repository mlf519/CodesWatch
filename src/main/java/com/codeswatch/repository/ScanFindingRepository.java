package com.codeswatch.repository;

import com.codeswatch.entity.ScanFinding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ScanFindingRepository extends JpaRepository<ScanFinding, Long> {

    List<ScanFinding> findByProjectId(Long projectId);

    List<ScanFinding> findByProjectIdOrderBySeverityDesc(Long projectId);

    List<ScanFinding> findByProjectIdAndSeverity(Long projectId, ScanFinding.FindingSeverity severity);

    List<ScanFinding> findByTaskId(Long taskId);

    Long countByProjectId(Long projectId);

    Long countByProjectIdAndSeverity(Long projectId, ScanFinding.FindingSeverity severity);

    void deleteByProjectId(Long projectId);
}