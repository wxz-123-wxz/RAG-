package com.graduation.ragqa.common;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;

class CapabilitiesControllerTest {

    @Test
    void returnsDegradedCapabilitiesWhenRagIsUnavailable() {
        var builder = RestClient.builder().baseUrl("http://localhost:8000");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://localhost:8000/health/ready")).andRespond(withServerError());

        var response = new CapabilitiesController(builder.build()).capabilities();

        assertThat(response.success()).isTrue();
        assertThat(response.data()).containsEntry("ragAvailable", false).containsEntry("chatConfigured", false);
        server.verify();
    }
}
