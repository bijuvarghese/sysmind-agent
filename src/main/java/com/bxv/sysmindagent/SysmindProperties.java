package com.bxv.sysmindagent;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "sysmind")
public record SysmindProperties(
        @Valid LmStudio lmStudio,
        @Valid Mcp mcp,
        @Valid Agent agent
) {

    public SysmindProperties {
        lmStudio = lmStudio == null ? LmStudio.defaults() : lmStudio;
        mcp = mcp == null ? Mcp.defaults() : mcp;
        agent = agent == null ? Agent.defaults() : agent;
    }

    public record LmStudio(
            @NotNull URI baseUrl,
            @NotBlank String apiKey,
            @NotBlank String model
    ) {

        public LmStudio {
            baseUrl = baseUrl == null ? URI.create("http://localhost:1234") : baseUrl;
            apiKey = apiKey == null || apiKey.isBlank() ? "lm-studio" : apiKey;
            model = model == null || model.isBlank() ? "google/gemma-4-e4b" : model;
        }

        static LmStudio defaults() {
            return new LmStudio(null, null, null);
        }
    }

    public record Mcp(
            @NotNull URI backendUrl,
            @NotBlank @Pattern(regexp = "/.*", message = "must start with /") String endpointPath
    ) {

        public Mcp {
            backendUrl = backendUrl == null ? URI.create("http://localhost:8080") : backendUrl;
            endpointPath = endpointPath == null || endpointPath.isBlank() ? "/mcp" : endpointPath;
        }

        static Mcp defaults() {
            return new Mcp(null, null);
        }
    }

    public record Agent(
            @NotNull Duration toolTimeout,
            @NotNull Duration timeout,
            @Min(1) int maxToolCallsPerUserRequest
    ) {

        public Agent {
            toolTimeout = toolTimeout == null ? Duration.ofSeconds(10) : toolTimeout;
            timeout = timeout == null ? Duration.ofSeconds(60) : timeout;
            maxToolCallsPerUserRequest = maxToolCallsPerUserRequest < 1 ? 3 : maxToolCallsPerUserRequest;
        }

        static Agent defaults() {
            return new Agent(null, null, 0);
        }
    }
}
