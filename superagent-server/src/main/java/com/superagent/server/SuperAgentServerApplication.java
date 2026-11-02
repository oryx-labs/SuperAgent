package com.superagent.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

/**
 * Deployable server entry point.
 *
 * <p>This module owns the process-level concerns (bootstrap, port, logging).
 * HTTP endpoints and their configuration live in {@code superagent-web}.
 */
@SpringBootApplication
@ComponentScan(basePackages = "com.superagent")
public class SuperAgentServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(SuperAgentServerApplication.class, args);
    }
}
