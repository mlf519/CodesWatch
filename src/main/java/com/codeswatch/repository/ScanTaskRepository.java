package com.codeswatch.repository;

import com.codeswatch.entity.ScanTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ScanTaskRepository extends JpaRepository<ScanTask, Long> {

    List<ScanTask> findByProjectId(Long projectId);

    List<ScanTask> findByParentTaskId(Long parentTaskId);

    List<ScanTask> findByProjectIdAndTaskType(Long projectId, ScanTask.TaskType taskType);

    List<ScanTask> findByProjectIdAndTaskTypeIn(Long projectId, List<ScanTask.TaskType> taskTypes);

    List<ScanTask> findByProjectIdAndStatus(Long projectId, ScanTask.TaskStatus status);

    List<ScanTask> findByProjectIdAndStatusIn(Long projectId, List<ScanTask.TaskStatus> statuses);

    List<ScanTask> findByStatusIn(List<ScanTask.TaskStatus> statuses);

    List<ScanTask> findByProjectIdAndTaskName(Long projectId, String taskName);

    Long countByProjectIdAndStatus(Long projectId, ScanTask.TaskStatus status);

    Long countByParentTaskId(Long parentTaskId);

    Long countByParentTaskIdAndStatusIn(Long parentTaskId, List<ScanTask.TaskStatus> statuses);

    @Modifying
    @Query("UPDATE ScanTask t SET t.status = :status, t.completedAt = :completedAt WHERE t.project.id = :projectId AND t.status IN :statuses")
    int updateStatusByProjectIdAndStatusIn(Long projectId, ScanTask.TaskStatus status, 
                                            LocalDateTime completedAt, List<ScanTask.TaskStatus> statuses);

    @Query("SELECT t.id FROM ScanTask t WHERE t.parentTask.id = :parentId")
    List<Long> findChildIdsByParentId(Long parentId);

    @Query("SELECT t FROM ScanTask t LEFT JOIN FETCH t.project WHERE t.parentTask.id = :parentId")
    List<ScanTask> findChildrenWithProject(Long parentId);

    @Modifying
    void deleteByProjectId(Long projectId);
}