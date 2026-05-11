package com.wherebus;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The main entry point for the WhereBus backend application.
 * This class initializes the Spring Boot framework, auto-configures the
 * web server, and scans for components like Services and Controllers.
 */
@SpringBootApplication
@EnableScheduling
public class WhereBusApplication {

    /**
     * The main method that starts the Spring Boot application.
     *
     * @param args Command line arguments passed during application startup.
     */
    public static void main(String[] args) {
        SpringApplication.run(WhereBusApplication.class, args);
    }
}