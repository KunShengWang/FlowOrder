package com.javaup.resource.service;

import com.javaup.dto.InstantReservationResultDto;
import com.javaup.dto.ResourceOrderCreateDto;

public interface InstantReservationService {

    InstantReservationResultDto submit(ResourceOrderCreateDto dto, String traceId);
}
