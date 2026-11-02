package com.superagent.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

/**
 * Background execution entry point: long-running jobs, agent runs and
 * scheduled tasks.
 */
@SpringBootApplication
@ComponentScan(basePackages = "com.superagent")
public class SuperAgentWorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(SuperAgentWorkerApplication.class, args);
    }
}
