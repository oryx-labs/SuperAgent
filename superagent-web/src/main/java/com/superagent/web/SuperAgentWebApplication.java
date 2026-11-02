package com.superagent.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

/**
 * HTTP entry point for SuperAgent.
 *
 * <p>Component scanning is rooted at {@code com.superagent} so that beans
 * contributed by shared modules (core, api) are picked up alongside this
 * module's controllers.
 */
@SpringBootApplication
@ComponentScan(basePackages = "com.superagent")
public class SuperAgentWebApplication {

    public static void main(String[] args) {
        SpringApplication.run(SuperAgentWebApplication.class, args);
    }
}
