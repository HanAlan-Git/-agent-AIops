package org.example.eval;

import java.util.Optional;

/**
 * Keeps a scenario scoped to one synchronous AIOps evaluation run.
 * It is cleared in the controller finally block because executor threads are reused.
 */
public final class EvalScenarioContext {

    private static final ThreadLocal<EvalScenario> CURRENT = new ThreadLocal<>();

    private EvalScenarioContext() {
    }

    public static void set(EvalScenario scenario) {
        CURRENT.set(scenario);
    }

    public static Optional<EvalScenario> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static void clear() {
        CURRENT.remove();
    }
}
