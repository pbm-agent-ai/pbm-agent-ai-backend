package com.pbm.command.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pbm.command.client.dto.DomPlannerInstructionPayload;
import com.pbm.command.domain.CommandSession;
import com.pbm.command.dto.request.PageSnapshotRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * AiDomPlannerClient 단위 테스트.
 */
class AiDomPlannerClientTest {

    @Test
    @DisplayName("external-api-service DOM planner 응답을 내부 payload로 파싱한다")
    void plan_returnsParsedInstruction() {
        TestFixture fixture = createFixture();

        fixture.server.expect(requestTo("http://localhost:8090/api/v1/planner/analyze-dom"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        """
                        {
                          "action": "CLICK",
                          "target": {
                            "nodeId": "node-1",
                            "role": "button",
                            "labelText": "지금 구매",
                            "selector": null
                          },
                          "value": null,
                          "confidence": 0.92,
                          "reason": "구매 버튼이 가장 명확함"
                        }
                        """,
                        MediaType.APPLICATION_JSON
                ));

        DomPlannerInstructionPayload response = fixture.client.plan(
                CommandSession.createSearching(1L, "무선 이어폰 구매"),
                new PageSnapshotRequest("https://www.aliexpress.com/item/1.html", "상품 상세", "", List.of(), List.of(), List.of(), List.of(), "", LocalDateTime.now()),
                null
        );

        assertThat(response.action()).isEqualTo("CLICK");
        assertThat(response.target().nodeId()).isEqualTo("node-1");
        assertThat(response.reason()).contains("구매 버튼");
        fixture.server.verify();
    }

    private TestFixture createFixture() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        AiDomPlannerClient client = new AiDomPlannerClient(restClient, new ObjectMapper());
        return new TestFixture(client, server);
    }

    private record TestFixture(AiDomPlannerClient client, MockRestServiceServer server) {
    }
}
