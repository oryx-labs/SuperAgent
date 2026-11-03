package com.superagent.core.metrics;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Initialises SuperAgent metrics.
 *
 * <p>This lives in {@code superagent-core} so every process—the HTTP server and
 * the worker alike—shares the same metric conventions. The registry itself and
 * the HTTP scrape endpoint are provided by whichever module is on the classpath
 * (Spring Boot Actuator + a Micrometer registry in {@code superagent-server});
 * this class only establishes the common tags applied to that registry.
 *
 * <p>Discovered through the {@code com.superagent} component scan of the bootable
 * applications. In a process with no
 * {@link io.micrometer.core.instrument.MeterRegistry} on the context (metrics
 * disabled), the post-processor simply never matches and nothing happens.
 */
@Configuration(proxyBeanMethods = false)
public class MetricsConfiguration {

    @Bean
    static CommonTagsMeterRegistryPostProcessor commonTagsMeterRegistryPostProcessor(
            @Value("${spring.application.name:unknown}") String application) {
        return new CommonTagsMeterRegistryPostProcessor(application);
    }
}
