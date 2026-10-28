package com.xcweb;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class XcWebApplication {

    public static void main(String[] args) {
        SpringApplication.run(XcWebApplication.class, args);
    }
}
