package io.github.louaychayeb.flowforge.orchestrator.engine;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RetryPolicyTest {

    private final RetryPolicy policy = new RetryPolicy(new EngineProperties(10, Duration.ofSeconds(10), Duration.ofSeconds(60),
            new EngineProperties.Retry(Duration.ofSeconds(1), Duration.ofSeconds(10), 2.0)));

    @Test
    void backoffGrowsExponentially() {
        assertThat(policy.backoffAfter(1)).isEqualTo(Duration.ofSeconds(1));
        assertThat(policy.backoffAfter(2)).isEqualTo(Duration.ofSeconds(2));
        assertThat(policy.backoffAfter(3)).isEqualTo(Duration.ofSeconds(4));
    }

    @Test
    void backoffIsCapped() {
        assertThat(policy.backoffAfter(5)).isEqualTo(Duration.ofSeconds(10));
        assertThat(policy.backoffAfter(1_000)).isEqualTo(Duration.ofSeconds(10));
    }
}
