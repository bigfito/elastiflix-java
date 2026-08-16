package com.elastiflix;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Entry point for the Elastiflix movie search application.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class ElastiflixApplication {
    public static void main(String[] args) {
        SpringApplication.run(ElastiflixApplication.class, args);
    }
}
