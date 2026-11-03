package com.superagent.core.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.config.BeanPostProcessor;

/**
 * Applies SuperAgent's common tags to every {@link MeterRegistry} as it is
 * created, so that all metrics—whoever registers them—carry a consistent
 * identity (which application emitted them).
 *
 * <p>Doing this in a {@link BeanPostProcessor} rather than after startup means
 * the tags are in place before application code resolves the registry and
 * starts registering meters, so no early meter is left untagged.
 */
class CommonTagsMeterRegistryPostProcessor implements BeanPostProcessor {

    private final String application;

    CommonTagsMeterRegistryPostProcessor(String application) {
        this.application = application;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof MeterRegistry registry) {
            registry.config().commonTags(MetricNames.TAG_APPLICATION, application);
        }
        return bean;
    }
}
