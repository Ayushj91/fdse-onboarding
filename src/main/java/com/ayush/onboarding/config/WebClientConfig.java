package com.ayush.onboarding.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import lombok.extern.slf4j.Slf4j;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Slf4j
@Configuration
public class WebClientConfig {

    @Value("${anthropic.api-key}")
    private String anthropicApiKey;

    @Value("${anthropic.api-url}")
    private String anthropicApiUrl;

    @Value("${crm.base-url}")
    private String crmBaseUrl;

    @Value("${crm.connect-timeout-ms:2000}")
    private int connectTimeoutMs;

    @Value("${crm.read-timeout-ms:10000}")
    private int readTimeoutMs;

    @Bean("anthropicWebClient")
    public WebClient anthropicWebClient() {
        return WebClient.builder()
                .baseUrl(anthropicApiUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + anthropicApiKey)
                .defaultHeader("x-api-key", anthropicApiKey)
                .defaultHeader("anthropic-version", "2023-06-01")
                .defaultHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                .filter(logRequest("Anthropic"))
                .build();
    }

    @Bean("crmWebClient")
    public WebClient crmWebClient() {
        HttpClient httpClient = HttpClient.create()
                .option(io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMs)
                .responseTimeout(Duration.ofMillis(readTimeoutMs))
                .doOnConnected(conn ->
                        conn.addHandlerLast(new ReadTimeoutHandler(readTimeoutMs, TimeUnit.MILLISECONDS))
                            .addHandlerLast(new WriteTimeoutHandler(connectTimeoutMs, TimeUnit.MILLISECONDS)));

        return WebClient.builder()
                .baseUrl(crmBaseUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .defaultHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                .defaultHeader(HttpHeaders.ACCEPT, "application/json")
                .filter(logRequest("CRM"))
                .build();
    }

    private ExchangeFilterFunction logRequest(String clientName) {
        return ExchangeFilterFunction.ofRequestProcessor(request -> {
            log.debug("[{}] {} {}", clientName, request.method(), request.url());
            return reactor.core.publisher.Mono.just(request);
        });
    }
}
