package io.github.louaychayeb.flowforge.worker;

import io.github.louaychayeb.flowforge.common.messaging.Topics;
import io.github.louaychayeb.flowforge.common.step.StepCommandProcessor;
import io.github.louaychayeb.flowforge.common.step.StepHandler;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

import java.time.Clock;
import java.util.List;

@Configuration(proxyBeanMethods = false)
public class WorkerConfiguration {

    @Bean
    StepCommandProcessor stepCommandProcessor(List<StepHandler> handlers) {
        return new StepCommandProcessor(handlers, Clock.systemUTC());
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
