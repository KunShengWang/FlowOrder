package com.javaup.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 MySQL、RabbitMQ、Nacos 和 order-service 下的 Proposal -> execute -> convergence 纵向切片。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "floworder.admin.enabled=true",
                "floworder.recovery.proposal-ttl-seconds=300",
                "spring.rabbitmq.listener.simple.auto-startup=true",
                "spring.task.scheduling.enabled=false"
        }
)
@EnabledIfEnvironmentVariable(named = "FLOWORDER_E2E", matches = "true")
class RecoveryProposalHttpE2ETest {

    private static final String PROPOSAL_ID = "prop-ordercare-m2-http-e2e";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void shouldBindApprovalExecuteOnceAndReachBusinessConvergence() throws Exception {
        JsonNode initialCase = getData(
                "/internal/recovery/cases/inspect"
                        + "?identifierType=REQUEST_ID"
                        + "&identifierValue=ORDERCARE-M05-REQUEST"
        );
        assertEquals("REPLAY_CANDIDATE", initialCase.path("diagnosisCode").asText(), initialCase.toString());

        Map<String, Object> previewRequest = new LinkedHashMap<>();
        previewRequest.put("proposalId", PROPOSAL_ID);
        previewRequest.put("identifierType", "REQUEST_ID");
        previewRequest.put("identifierValue", "ORDERCARE-M05-REQUEST");
        previewRequest.put("actionType", "REPLAY");
        previewRequest.put("suggestedReason", "Agent diagnosed an ORDER_TIMEOUT dead letter");

        JsonNode preview = post("/internal/recovery/proposals", previewRequest);

        assertEquals("floworder-recovery-proposal-v1", preview.path("schemaVersion").asText());
        assertEquals(PROPOSAL_ID, preview.path("proposalId").asText());
        assertEquals("ACTIVE", preview.path("proposalStatus").asText());
        assertEquals("NOT_STARTED", preview.path("actionStatus").asText());
        assertEquals("NOT_CONVERGED", preview.path("caseOutcome").asText());
        assertTrue(preview.path("canExecute").asBoolean());
        assertEquals(64, preview.path("stateFingerprint").asText().length());
        assertEquals(64, preview.path("previewDigest").asText().length());
        assertNotEquals(PROPOSAL_ID, preview.path("actionRequestId").asText());

        Map<String, Object> executeRequest = new LinkedHashMap<>();
        executeRequest.put("proposalId", PROPOSAL_ID);
        executeRequest.put("proposalVersion", preview.path("proposalVersion").asInt());
        executeRequest.put("stateFingerprint", preview.path("stateFingerprint").asText());
        executeRequest.put("effectsDigest", preview.path("effectsDigest").asText());
        executeRequest.put("warningsDigest", preview.path("warningsDigest").asText());
        executeRequest.put("previewDigest", preview.path("previewDigest").asText());
        executeRequest.put("approvalId", "approval-ordercare-m2-http-e2e");
        executeRequest.put("approvedBy", "operator-e2e");
        executeRequest.put("approvalComment", "reviewed immutable effects and warnings");

        JsonNode submitted = post(
                "/internal/recovery/proposals/" + PROPOSAL_ID + "/execute",
                executeRequest
        );
        assertEquals("APPROVED", submitted.path("proposalStatus").asText());
        assertEquals("SUBMITTED", submitted.path("actionStatus").asText());
        assertFalse(submitted.path("canExecute").asBoolean());

        JsonNode resolved = awaitResolved(Duration.ofSeconds(15));
        assertEquals("RESOLVED", resolved.path("caseOutcome").asText());
        assertEquals("SUBMITTED", resolved.path("actionStatus").asText());
        assertEquals("operator-e2e", resolved.path("approvedBy").asText());

        JsonNode repeated = post(
                "/internal/recovery/proposals/" + PROPOSAL_ID + "/execute",
                executeRequest
        );
        assertEquals("APPROVED", repeated.path("proposalStatus").asText());
        assertEquals("SUBMITTED", repeated.path("actionStatus").asText());
        assertEquals("RESOLVED", repeated.path("caseOutcome").asText());
    }

    private JsonNode awaitResolved(Duration timeout) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        JsonNode last = null;
        while (Instant.now().isBefore(deadline)) {
            ResponseEntity<String> response = restTemplate.getForEntity(
                    "/internal/recovery/proposals/" + PROPOSAL_ID,
                    String.class
            );
            assertTrue(response.getStatusCode().is2xxSuccessful());
            last = objectMapper.readTree(response.getBody()).path("data");
            if ("RESOLVED".equals(last.path("caseOutcome").asText())) {
                return last;
            }
            Thread.sleep(250);
        }
        throw new AssertionError("recovery did not converge, last proposal=" + last);
    }

    private JsonNode post(String path, Map<String, Object> request) throws Exception {
        ResponseEntity<String> response = restTemplate.postForEntity(path, request, String.class);
        assertTrue(response.getStatusCode().is2xxSuccessful());
        JsonNode envelope = objectMapper.readTree(response.getBody());
        assertEquals(200, envelope.path("code").asInt(), envelope.toString());
        return envelope.path("data");
    }

    private JsonNode getData(String path) throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity(path, String.class);
        assertTrue(response.getStatusCode().is2xxSuccessful());
        JsonNode envelope = objectMapper.readTree(response.getBody());
        assertEquals(200, envelope.path("code").asInt(), envelope.toString());
        return envelope.path("data");
    }
}
