package com.javaup.resource.task;

import com.javaup.resource.service.InstantAdmissionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(name = "floworder.instant.enabled", havingValue = "true", matchIfMissing = true)
public class InstantAdmissionRecoveryTask {

    private final InstantAdmissionService admissionService;
    private final long orphanTimeoutMillis;
    private final int batchSize;

    public InstantAdmissionRecoveryTask(
            InstantAdmissionService admissionService,
            @Value("${floworder.instant.orphan-timeout-ms:60000}") long orphanTimeoutMillis,
            @Value("${floworder.instant.orphan-batch-size:100}") int batchSize
    ) {
        this.admissionService = admissionService;
        this.orphanTimeoutMillis = Math.max(1000, orphanTimeoutMillis);
        this.batchSize = Math.min(Math.max(1, batchSize), 500);
    }

    @Scheduled(
            fixedDelayString = "${floworder.instant.orphan-scan-delay-ms:5000}",
            scheduler = "v8RequestTaskScheduler"
    )
    public void recover() {
        try {
            int recovered = admissionService.recoverExpiredUnpersisted(
                    System.currentTimeMillis() - orphanTimeoutMillis,
                    batchSize
            );
            if (recovered > 0) {
                log.warn("Instant未落库Redis准入恢复完成, recovered={}", recovered);
            }
        } catch (RuntimeException exception) {
            log.error("Instant未落库Redis准入扫描失败", exception);
        }
    }
}
