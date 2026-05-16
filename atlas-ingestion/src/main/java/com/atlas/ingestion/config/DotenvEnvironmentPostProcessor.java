package com.atlas.ingestion.config;

import io.github.cdimascio.dotenv.Dotenv;
import io.github.cdimascio.dotenv.DotenvException;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.HashMap;
import java.util.Map;

/**
 * Loads .env file into Spring's Environment before any autoconfiguration runs.
 *
 * Registered via META-INF/spring.factories so it fires at the earliest
 * point in the Spring lifecycle — before beans are created.
 *
 * Values from .env are only used if the key is not already set in the
 * environment — real environment variables always take precedence.
 */
public class DotenvEnvironmentPostProcessor implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment,
                                       SpringApplication application) {
        try {
            Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();

            Map<String, Object> dotenvProperties = new HashMap<>();
            dotenv.entries().forEach(entry -> {
                String key = entry.getKey();
                String value = entry.getValue();
                // Only add if not already present in environment (env vars take precedence)
                if (!environment.containsProperty(key) && value != null && !value.isBlank()) {
                    dotenvProperties.put(key, value);
                }
            });

            if (!dotenvProperties.isEmpty()) {
                environment.getPropertySources().addLast(
                        new MapPropertySource("dotenvProperties", dotenvProperties)
                );
            }

        } catch (DotenvException e) {
            // .env not present — rely on actual environment variables
        }
    }
}
