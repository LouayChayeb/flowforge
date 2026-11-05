package io.github.louaychayeb.flowforge.payments;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.time.Clock;
import java.time.Duration;

/**
 * A simulated external payment provider. It exists so the demo can prove, rather than just claim, what makes
 * at-least-once execution safe: the provider remembers every idempotency key, so a charge step that runs twice
 * (a worker died after charging and before reporting) charges the customer once.
 */
@SpringBootApplication
public class PaymentsApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentsApplication.class, args);
    }

    @Bean
    Clock clock() {
        return Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000));
    }
}
