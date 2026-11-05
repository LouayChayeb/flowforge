package io.github.louaychayeb.flowforge.orchestrator;

import io.micrometer.observation.ObservationPredicate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.ServerRequestObservationContext;

/**
 * Keeps traces about real work. Health probes, metric scrapes and the dashboard's polling would otherwise produce
 * a trace every second and bury the traces of actual runs; only requests that change something (POST) are traced.
 */
@Configuration(proxyBeanMethods = false)
class ObservationConfiguration {

    @Bean
    ObservationPredicate onlyStateChangingRequests() {
        return (name, context) -> !(context instanceof ServerRequestObservationContext request)
                || "POST".equals(request.getCarrier().getMethod());
    }
}