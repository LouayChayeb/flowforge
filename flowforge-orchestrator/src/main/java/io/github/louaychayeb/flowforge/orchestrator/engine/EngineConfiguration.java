package io.github.louaychayeb.flowforge.orchestrator.engine;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class EngineConfiguration {

    /**
     * Ticks in microseconds, the precision PostgreSQL stores, so timestamps compare the same in Java and in SQL.
     */
    @Bean
    Clock clock() {
        return Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000));
    }
}
