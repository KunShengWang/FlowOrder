package com.javaup.controller;

import com.javaup.dto.OrderScopeCandidateRequest;
import com.javaup.dto.OrderScopeCandidateResponse;
import com.javaup.security.InternalIncidentScopeAuthorizer;
import com.javaup.service.IncidentOrderScopeQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class IncidentScopeControllerTest {

    @Test
    void rejectsWrongInternalTokenBeforeQuery() {
        IncidentOrderScopeQueryService service = mock(IncidentOrderScopeQueryService.class);
        IncidentScopeController controller = new IncidentScopeController(
                service, new InternalIncidentScopeAuthorizer("secret"));

        assertThatThrownBy(() -> controller.orderCandidates("wrong", new OrderScopeCandidateRequest()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("401");
        verifyNoInteractions(service);
    }

    @Test
    void authorizedRequestDelegatesToReadOnlyService() {
        IncidentOrderScopeQueryService service = mock(IncidentOrderScopeQueryService.class);
        OrderScopeCandidateRequest request = new OrderScopeCandidateRequest();
        OrderScopeCandidateResponse response = new OrderScopeCandidateResponse();
        when(service.discover(request)).thenReturn(response);
        IncidentScopeController controller = new IncidentScopeController(
                service, new InternalIncidentScopeAuthorizer("secret"));

        assertThat(controller.orderCandidates("secret", request).getData()).isSameAs(response);
        verify(service).discover(request);
    }
}
