package com.bxv.sysmindagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class SysmindAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(SysmindAgentApplication.class, args);
    }

}
