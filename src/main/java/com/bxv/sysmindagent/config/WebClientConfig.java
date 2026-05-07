package com.bxv.sysmindagent.config;

import com.bxv.sysmindagent.SysmindProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class WebClientConfig {

    @Bean
    @ConditionalOnMissingBean
    public WebClient.Builder webClientBuilder() {
        return WebClient.builder();
    }

    @Bean
    @Qualifier("mcpWebClient")
    public WebClient mcpWebClient(WebClient.Builder builder, SysmindProperties properties) {
        return builder.clone()
                .baseUrl(properties.mcp().backendUrl().toString())
                .build();
    }

    @Bean
    @Qualifier("lmStudioWebClient")
    public WebClient lmStudioWebClient(WebClient.Builder builder, SysmindProperties properties) {
        return builder.clone()
                .baseUrl(properties.lmStudio().baseUrl().toString())
                .defaultHeader("Authorization", "Bearer " + properties.lmStudio().apiKey())
                .build();
    }
}
