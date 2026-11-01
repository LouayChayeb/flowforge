package io.github.louaychayeb.flowforge.orchestrator.engine;

import io.github.louaychayeb.flowforge.common.messaging.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Declared by both services so either can start first; creating an existing topic is a no-op.
 */
@Configuration(proxyBeanMethods = false)
public class KafkaTopicsConfiguration {

    @Bean
    NewTopic stepCommandsTopic(@Value("${flowforge.kafka.partitions:3}") int partitions) {
        return TopicBuilder.name(Topics.STEP_COMMANDS).partitions(partitions).replicas(1).build();
    }

    @Bean
    NewTopic stepResultsTopic(@Value("${flowforge.kafka.partitions:3}") int partitions) {
        return TopicBuilder.name(Topics.STEP_RESULTS).partitions(partitions).replicas(1).build();
    }
}
