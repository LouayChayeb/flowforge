package io.github.louaychayeb.flowforge.orchestrator.run;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

class StepStatusTest {

    @ParameterizedTest
    @EnumSource(value = StepStatus.class, names = {"COMPLETED", "FAILED"})
    void terminalStatesAllowNoTransitions(StepStatus terminal) {
        assertThat(terminal.isTerminal()).isTrue();
        for (StepStatus target : StepStatus.values()) {
            assertThat(terminal.canTransitionTo(target)).as("%s -> %s", terminal, target).isFalse();
        }
    }

    @ParameterizedTest
    @EnumSource(value = StepStatus.class, names = {"PENDING", "RETRY_WAIT"})
    void waitingStatesCanOnlyBeClaimed(StepStatus waiting) {
        for (StepStatus target : StepStatus.values()) {
            assertThat(waiting.canTransitionTo(target)).as("%s -> %s", waiting, target)
                    .isEqualTo(target == StepStatus.RUNNING);
        }
    }
}
