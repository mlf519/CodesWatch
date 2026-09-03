package com.codeswatch.repository;

import com.codeswatch.entity.ScanLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ScanLogRepository extends JpaRepository<ScanLog, Long> {

    List<ScanLog> findByProjectIdOrderByCreatedAtAsc(Long projectId);

    List<ScanLog> findByTaskIdOrderByCreatedAtAsc(Long taskId);

    List<ScanLog> findByProjectIdAndLogType(Long projectId, ScanLog.LogType logType);

    void deleteByProjectId(Long projectId);

    void deleteByTaskId(Long taskId);
}