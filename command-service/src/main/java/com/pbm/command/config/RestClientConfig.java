package com.pbm.command.config;

import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.util.Timeout;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * external-api-service 호출용 RestClient 설정 클래스.
 *
 * 역할: Apache HttpClient 5 기반 RestClient를 생성하고 요청/응답 로깅 인터셉터를 추가한다.
 * 동작: HttpComponentsClientHttpRequestFactory로 연결 타임아웃과 읽기 타임아웃을 설정하고,
 *       RestClient에 HTTP 메서드와 URL을 DEBUG 레벨로 로깅하는 인터셉터를 추가한다.
 * 연관: OpenAiCommandClient, OpenAiProperties.
 */
public class RestClientConfig {

    private static final Logger log = LoggerFactory.getLogger(RestClientConfig.class);

    /**
     * external-api-service 호출용 RestClient를 생성한다.
     *
     * @param openAiProperties OpenAI 프록시 호출 설정 (baseUrl, 타임아웃)
     * @return 로깅 인터셉터가 적용된 RestClient
     */
    public static RestClient createExternalApiRestClient(OpenAiProperties openAiProperties) {
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(Timeout.ofMilliseconds(openAiProperties.getConnectTimeoutMs()))
                .setResponseTimeout(Timeout.ofMilliseconds(openAiProperties.getReadTimeoutMs()))
                .build();

        var httpClient = HttpClients.custom()
                .setDefaultRequestConfig(requestConfig)
                .build();

        HttpComponentsClientHttpRequestFactory requestFactory = new HttpComponentsClientHttpRequestFactory(httpClient);

        return RestClient.builder()
                .requestFactory(requestFactory)
                .requestInterceptor(loggingInterceptor())
                .build();
    }

    /**
     * HTTP 요청/응답 로깅 인터셉터.
     *
     * 역할: RestClient가 보내는 요청의 HTTP 메서드와 URL을 DEBUG 레벨로 기록하여
     *       404 등의 문제 발생 시 실제 요청 경로를 추적할 수 있게 한다.
     */
    private static ClientHttpRequestInterceptor loggingInterceptor() {
        return (request, body, execution) -> {
            log.debug("[ExternalApi] {} {}", request.getMethod(), request.getURI());
            var response = execution.execute(request, body);
            log.debug("[ExternalApi] Response status: {}", response.getStatusCode());
            return response;
        };
    }
}
