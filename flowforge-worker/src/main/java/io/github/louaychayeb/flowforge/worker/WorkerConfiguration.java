package io.github.louaychayeb.flowforge.worker;

import io.github.louaychayeb.flowforge.common.messaging.Topics;
import io.github.louaychayeb.flowforge.common.step.StepCommandProcessor;
import io.github.louaychayeb.flowforge.common.step.StepHandler;
import io.github.louaychayeb.flowforge.worker.payments.HttpPaymentGateway;
import io.github.louaychayeb.flowforge.worker.payments.PaymentGateway;
import io.github.louaychayeb.flowforge.worker.payments.SimulatedPaymentGateway;
import org.apache.kafka.clients.admin.NewTopic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

@Configuration(proxyBeanMethods = false)
public class WorkerConfiguration {

    private static final Logger log = LoggerFactory.getLogger(WorkerConfiguration.class);

    @Bean
    StepCommandProcessor stepCommandProcessor(List<StepHandler> handlers,
                                              @Value("${flowforge.worker.simulated-latency:0ms}") Duration latency) {
        List<StepHandler> effective = latency.isZero() ? handlers
                : handlers.stream().<StepHandler>map(handler -> new SimulatedLatency(handler, latency)).toList();
        return new StepCommandProcessor(effective, Clock.systemUTC());
    }

    /**
     * The payment provider over HTTP when {@code flowforge.worker.payments-url} is set (the demo and Kubernetes
     * set it); otherwise an in-process simulation with the same rules.
     */
    @Bean
    PaymentGateway paymentGateway(@Value("${flowforge.worker.payments-url:}") String url,
                                  @Value("${flowforge.worker.payments-timeout:5s}") Duration timeout,
                                  @Value("${flowforge.worker.decline-above:10000}") BigDecimal declineAbove,
                                  RestClient.Builder http) {
        if (url.isBlank()) {
            log.warn("No flowforge.worker.payments-url: payments are simulated in memory and do not survive a restart");
            return new SimulatedPaymentGateway(declineAbove);
        }
        log.info("Payments go to the provider at {}", url);
        // Spring's builder is instrumented, so the call to the provider joins the run's trace.
        return new HttpPaymentGateway(http.clone(), url, timeout);
    }

    // Declared by both services so either can start first; creating an existing topic is a no-op.

    @Bean
    NewTopic stepCommandsTopic(@Value("${flowforge.kafka.partitions:3}") int partitions) {
        return TopicBuilder.name(Topics.STEP_COMMANDS).partitions(partitions).replicas(1).build();
    }

    @Bean
    NewTopic stepResultsTopic(@Value("${flowforge.kafka.partitions:3}") int partitions) {
        return TopicBuilder.name(Topics.STEP_RESULTS).partitions(partitions).replicas(1).build();
    }
}
