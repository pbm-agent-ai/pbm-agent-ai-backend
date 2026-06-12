package com.pbm.price.repository;

import com.pbm.price.domain.BrowserSearchTask;
import com.pbm.price.domain.BrowserSearchTaskStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface BrowserSearchTaskRepository extends JpaRepository<BrowserSearchTask, Long> {

    Optional<BrowserSearchTask> findByCommandId(String commandId);

    List<BrowserSearchTask> findAllByUserIdAndStatus(Long userId, BrowserSearchTaskStatus status);

    List<BrowserSearchTask> findAllByUserIdAndStatusAndLastDispatchedAtBefore(
            Long userId,
            BrowserSearchTaskStatus status,
            Instant threshold
    );
}
