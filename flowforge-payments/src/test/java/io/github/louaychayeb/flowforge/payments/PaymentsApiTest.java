package io.github.louaychayeb.flowforge.payments;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class PaymentsApiTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    MockMvc mvc;

    @Autowired
    PaymentLedger ledger;

    @Test
    void aRepeatedChargeReplaysTheFirstOneInsteadOfChargingAgain() throws Exception {
        String key = UUID.randomUUID().toString();
        String order = "ORD-" + key;

        charge(key, order, "120.00").andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "false"))
                .andExpect(jsonPath("$.status").value("CAPTURED"));
        charge(key, order, "120.00").andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.requests").value(2));

        mvc.perform(get("/v1/charges").param("orderId", order))
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void aKeyReusedForADifferentRequestIsRefused() throws Exception {
        String key = UUID.randomUUID().toString();
        charge(key, "ORD-A-" + key, "10").andExpect(status().isCreated());
        charge(key, "ORD-A-" + key, "11").andExpect(status().isUnprocessableEntity());
    }

    @Test
    void aDeclineIsRememberedToo() throws Exception {
        String key = UUID.randomUUID().toString();
        charge(key, "ORD-" + key, "50000").andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.status").value("DECLINED"));
        charge(key, "ORD-" + key, "50000").andExpect(status().isPaymentRequired())
                .andExpect(header().string("Idempotent-Replayed", "true"));
    }

    @Test
    void refundsHappenOnce() throws Exception {
        String chargeKey = UUID.randomUUID().toString();
        String refundKey = UUID.randomUUID().toString();
        charge(chargeKey, "ORD-" + chargeKey, "75").andExpect(status().isCreated());

        refund(refundKey, chargeKey).andExpect(status().isCreated()).andExpect(jsonPath("$.amount").value(75.0));
        refund(refundKey, chargeKey).andExpect(status().isOk()).andExpect(header().string("Idempotent-Replayed", "true"));
        mvc.perform(get("/v1/charges").param("orderId", "ORD-" + chargeKey))
                .andExpect(jsonPath("$[0].status").value("REFUNDED"));
        refund(UUID.randomUUID().toString(), UUID.randomUUID().toString()).andExpect(status().isNotFound());
    }

    @Test
    void concurrentRequestsWithTheSameKeyChargeOnce() throws Exception {
        String key = UUID.randomUUID().toString();
        String order = "ORD-RACE-" + key;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Boolean>> calls = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                calls.add(() -> !ledger.charge(key, order, new java.math.BigDecimal("42")).replayed());
            }
            long fresh = 0;
            for (Future<Boolean> f : pool.invokeAll(calls)) {
                fresh += f.get() ? 1 : 0;
            }
            assertThat(fresh).as("requests that actually charged").isEqualTo(1);
        } finally {
            pool.shutdown();
        }
        mvc.perform(get("/v1/charges").param("orderId", order))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].requests").value(16));
        mvc.perform(get("/v1/summary")).andExpect(jsonPath("$.ordersChargedMoreThanOnce").value(0));
    }

    @Test
    void chargesNeedAnIdempotencyKey() throws Exception {
        mvc.perform(post("/v1/charges").contentType(MediaType.APPLICATION_JSON).content("""
                {"orderId": "ORD-1", "amount": 5}"""))
                .andExpect(status().isBadRequest());
    }

    private ResultActions charge(String key, String order, String amount) throws Exception {
        return mvc.perform(post("/v1/charges").header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content("{\"orderId\": \"%s\", \"amount\": %s}".formatted(order, amount)));
    }

    private ResultActions refund(String key, String chargeKey) throws Exception {
        return mvc.perform(post("/v1/refunds").header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content("{\"chargeIdempotencyKey\": \"%s\"}".formatted(chargeKey)));
    }
}
