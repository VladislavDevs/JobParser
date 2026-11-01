package org.example.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class WebClientConfig {

    @Value("${hh.api.base-url}")
    private String baseUrl;

    @Value("${hh.api.user-agent}")
    private String userAgent;

    @Bean
    public WebClient hhWebClient() {
        return WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.USER_AGENT, userAgent)
                .build();
    }
}