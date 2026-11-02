package com.superagent.core.logging;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

/**
 * Applies {@link LogFormat#PATTERN} to every Spring Boot application.
 *
 * <p>Registered in {@code META-INF/spring.factories}, so any module that puts
 * {@code superagent-core} on the classpath inherits the same log layout. The
 * property source is added last but only applies when the application has not
 * set {@code logging.pattern.console} itself, so explicit configuration wins.
 */
public class LoggingEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String PROPERTY_SOURCE_NAME = "superagentLoggingDefaults";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (environment.containsProperty("logging.pattern.console")) {
            return;
        }
        environment.getPropertySources().addLast(new MapPropertySource(
                PROPERTY_SOURCE_NAME, Map.of("logging.pattern.console", LogFormat.PATTERN)));
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
