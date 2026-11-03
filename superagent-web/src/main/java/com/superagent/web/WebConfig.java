package com.superagent.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Maps clean UI routes onto the static pages they serve, so the browser URL has
 * no {@code .html} suffix. Each mapping forwards to the static resource, which
 * the default resource handler then serves.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/login").setViewName("forward:/login.html");
    }
}
