package com.codeswatch.repository;

import com.codeswatch.entity.ScanProject;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ScanProjectRepository extends JpaRepository<ScanProject, Long> {

    List<ScanProject> findByStatus(ScanProject.ProjectStatus status);

    List<ScanProject> findByProjectNameContaining(String name);

    @Query("SELECT p FROM ScanProject p ORDER BY p.createdAt DESC")
    List<ScanProject> findAllOrderByCreatedAtDesc();

    @Query("SELECT COUNT(p) FROM ScanProject p WHERE p.status = 'COMPLETED'")
    Long countCompletedProjects();

    @Query("SELECT SUM(p.findingCount) FROM ScanProject p WHERE p.status = 'COMPLETED'")
    Long sumTotalFindings();

    @Query("SELECT SUM(CASE WHEN f.severity = 'CRITICAL' THEN 1 ELSE 0 END) FROM ScanFinding f")
    Long countCriticalFindings();

    @Query("SELECT SUM(CASE WHEN f.severity = 'HIGH' THEN 1 ELSE 0 END) FROM ScanFinding f")
    Long countHighFindings();
}