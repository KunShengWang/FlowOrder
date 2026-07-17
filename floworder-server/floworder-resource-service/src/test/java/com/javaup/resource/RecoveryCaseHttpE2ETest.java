package com.javaup.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 需要本地 MySQL、Redis、RabbitMQ、Nacos 和 order-service。
 * 固定夹具由 scripts/ordercare/m0.5-recovery-baseline.ps1 -Action Inject 创建。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "floworder.admin.enabled=true",
                "spring.rabbitmq.listener.simple.auto-startup=false",
                "spring.task.scheduling.enabled=false"
        }
)
@EnabledIfEnvironmentVariable(named = "FLOWORDER_E2E", matches = "true")
class RecoveryCaseHttpE2ETest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void shouldAggregateRealTimeoutDeadLetterCaseAsReplayCandidate() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/internal/recovery/cases/inspect"
                        + "?identifierType=REQUEST_ID"
                        + "&identifierValue=ORDERCARE-M05-REQUEST",
                String.class
        );

        assertTrue(response.getStatusCode().is2xxSuccessful());
        JsonNode data = objectMapper.readTree(response.getBody()).path("data");
        assertEquals("floworder-recovery-case-v1", data.path("schemaVersion").asText());
        assertEquals("REPLAY_CANDIDATE", data.path("diagnosisCode").asText());
        assertTrue(data.path("factsComplete").asBoolean());
        assertTrue(data.path("recoveryEligible").asBoolean());
        assertEquals("ORDERCARE-M05-REQUEST", data.path("canonicalRequestId").asText());
        assertEquals("ORDERCARE-M05-DEDUCT", data.path("deduct").path("deductNo").asText());
        assertEquals("PENDING", data.path("deadLetters").get(0).path("statusName").asText());
        assertEquals("FLOWORDER", data.path("candidates").get(0).path("decisionOwner").asText());
        assertTrue(data.path("evidence").toString()
                .contains("ORDER_STATUS_GAP_EXPLAINED_BY_DEAD_LETTER"));
    }
}
