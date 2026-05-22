package com.pbm.command.repository;

import com.pbm.command.domain.AgentRun;
import com.pbm.command.domain.AgentRunStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * AgentRun JPA 레포지토리.
 *
 * 역할: 브라우저 자동화 실행 세션의 생성/조회/상태 확인을 담당한다.
 * 연관: AgentRunService, AgentRun.
 */
public interface AgentRunRepository extends JpaRepository<AgentRun, Long> {

    Optional<AgentRun> findByRunId(String runId);

    boolean existsByUserIdAndStatusIn(Long userId, Collection<AgentRunStatus> statuses);

    Optional<AgentRun> findFirstByAssignedDeviceIdAndStatusOrderByAssignedAtAsc(String assignedDeviceId, AgentRunStatus status);

    Optional<AgentRun> findFirstByUserIdAndStatusOrderByCreatedAtAsc(Long userId, AgentRunStatus status);

    List<AgentRun> findAllByAssignedDeviceIdInAndStatusIn(Collection<String> assignedDeviceIds, Collection<AgentRunStatus> statuses);

    List<AgentRun> findAllByAssignedDeviceIdAndStatusInOrderByCreatedAtDesc(String assignedDeviceId, Collection<AgentRunStatus> statuses);

    List<AgentRun> findAllByStatusAndApprovalRequestedAtBefore(AgentRunStatus status, java.time.LocalDateTime threshold);

    List<AgentRun> findAllByUserIdAndStatusIn(Long userId, Collection<AgentRunStatus> statuses);
}
