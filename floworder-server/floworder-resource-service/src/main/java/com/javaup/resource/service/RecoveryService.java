package com.javaup.resource.service;

import com.javaup.resource.dto.RecoveryDeadLetterRequest;
import com.javaup.resource.dto.RecoveryExecuteResult;
import com.javaup.resource.dto.RecoveryPreviewResult;
import com.javaup.resource.dto.ReservationRecoveryCheckResult;

public interface RecoveryService {

    RecoveryPreviewResult previewDeadLetter(RecoveryDeadLetterRequest request);

    RecoveryExecuteResult executeDeadLetter(RecoveryDeadLetterRequest request);

    ReservationRecoveryCheckResult checkReservation(String requestId);
}
