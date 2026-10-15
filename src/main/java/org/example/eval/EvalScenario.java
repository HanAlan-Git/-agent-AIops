package org.example.eval;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A deterministic input used by the offline AIOps evaluation endpoint.
 * Ground-truth fields intentionally stay outside this runtime model so they
 * cannot be exposed to an agent through a tool call.
 */
@Data
public class EvalScenario {

    private String scenarioId;
    private List<Alert> alerts = new ArrayList<>();
    private List<Log> logs = new ArrayList<>();

    @Data
    public static class Alert {
        private String alertName;
        private String description;
        private String state;
        private String activeAt;
        private String duration;
    }

    @Data
    public static class Log {
        private String topic;
        private String timestamp;
        private String level;
        private String service;
        private String instance;
        private String message;
        private Map<String, String> metrics;
        /** At least one term must be present in the tool query to expose this log. */
        private List<String> queryAnyTerms = new ArrayList<>();
    }
}
