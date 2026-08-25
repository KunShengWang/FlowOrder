package com.javaup.resource.incident.scope;

import com.javaup.resource.incident.scope.controller.IncidentScopeEnrichmentController;
import com.javaup.resource.incident.scope.dto.ResourceScopeEnrichmentRequest;
import com.javaup.resource.incident.scope.service.ResourceScopeEnrichmentService;
import com.javaup.security.InternalIncidentScopeAuthorizer;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class IncidentScopeEnrichmentControllerTest {

    @Test
    void rejectsUnauthorizedRequestBeforeResourceQueries() {
        ResourceScopeEnrichmentService service = mock(ResourceScopeEnrichmentService.class);
        IncidentScopeEnrichmentController controller = new IncidentScopeEnrichmentController(
                service, new InternalIncidentScopeAuthorizer("secret"));

        assertThatThrownBy(() -> controller.enrich("wrong", new ResourceScopeEnrichmentRequest()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("401");
        verifyNoInteractions(service);
    }
}
