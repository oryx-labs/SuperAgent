package com.superagent.core.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CommonTagsMeterRegistryPostProcessorTest {

    @Test
    void appliesApplicationTagToMetersRegisteredAfterwards() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CommonTagsMeterRegistryPostProcessor processor =
                new CommonTagsMeterRegistryPostProcessor("superagent-test");

        Object result = processor.postProcessAfterInitialization(registry, "meterRegistry");

        assertThat(result).isSameAs(registry);
        registry.counter("sample.counter").increment();
        assertThat(registry.get("sample.counter").counter().getId().getTag(MetricNames.TAG_APPLICATION))
                .isEqualTo("superagent-test");
    }

    @Test
    void ignoresNonRegistryBeans() {
        CommonTagsMeterRegistryPostProcessor processor =
                new CommonTagsMeterRegistryPostProcessor("superagent-test");
        Object bean = new Object();

        assertThat(processor.postProcessAfterInitialization(bean, "someBean")).isSameAs(bean);
    }
}
