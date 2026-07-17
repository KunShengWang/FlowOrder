package com.javaup.resource.service;

import com.javaup.resource.dto.RecoveryCaseResult;

public interface RecoveryCaseService {

    RecoveryCaseResult inspect(String identifierType, String identifierValue);
}
