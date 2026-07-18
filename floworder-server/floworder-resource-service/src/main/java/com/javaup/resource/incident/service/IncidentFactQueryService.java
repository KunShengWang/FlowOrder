package com.javaup.resource.incident.service;

import com.javaup.resource.incident.dto.IncidentDeadLetterFacts;
import com.javaup.resource.incident.dto.IncidentFactQueryRequest;
import com.javaup.resource.incident.dto.IncidentFactResponse;
import com.javaup.resource.incident.dto.IncidentInventoryFacts;
import com.javaup.resource.incident.dto.IncidentOrderFacts;

public interface IncidentFactQueryService {

    IncidentFactResponse<IncidentOrderFacts> queryOrders(IncidentFactQueryRequest request);

    IncidentFactResponse<IncidentInventoryFacts> queryInventory(IncidentFactQueryRequest request);

    IncidentFactResponse<IncidentDeadLetterFacts> queryDeadLetters(IncidentFactQueryRequest request);
}
