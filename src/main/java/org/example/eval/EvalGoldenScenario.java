package org.example.eval;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Evaluation-only expectations. These data are never supplied to an agent.
 * A diagnosis matches when every term group contains at least one phrase from
 * the report; several diagnoses may therefore be accepted for one scenario.
 */
@Data
public class EvalGoldenScenario {

    private String scenarioId;
    private List<String> requiredTools = new ArrayList<>();
    private List<String> optionalTools = new ArrayList<>();
    private List<Diagnosis> acceptableDiagnoses = new ArrayList<>();
    /** Human-readable intent of the scenario, mainly used to flag negative samples. */
    private String notes;

    @Data
    public static class Diagnosis {
        private String id;
        private String description;
        private List<List<String>> rootCauseTermGroups = new ArrayList<>();
        private List<List<String>> evidenceTermGroups = new ArrayList<>();
        private List<List<String>> actionTermGroups = new ArrayList<>();
    }
}
