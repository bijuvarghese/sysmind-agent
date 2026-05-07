package com.bxv.sysmindagent;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

@SpringBootTest
class SysmindAgentApplicationTests {

    @Autowired
    private SysmindProperties sysmindProperties;

    @Autowired
    private Environment environment;

    @Test
    void contextLoads() {
    }

    @Test
    void sysmindPropertiesUseLocalDefaults() {
        assertThat(sysmindProperties.lmStudio().baseUrl()).isEqualTo(URI.create("http://localhost:1234"));
        assertThat(sysmindProperties.lmStudio().apiKey()).isEqualTo("lm-studio");
        assertThat(sysmindProperties.lmStudio().model()).isEqualTo("local-model");
        assertThat(sysmindProperties.mcp().backendUrl()).isEqualTo(URI.create("http://localhost:8080"));
        assertThat(sysmindProperties.mcp().endpointPath()).isEqualTo("/mcp");
        assertThat(sysmindProperties.agent().toolTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(sysmindProperties.agent().timeout()).isEqualTo(Duration.ofSeconds(60));
        assertThat(sysmindProperties.agent().maxToolCallsPerUserRequest()).isEqualTo(3);
        assertThat(environment.getProperty("server.port", Integer.class)).isEqualTo(4000);
    }

}
