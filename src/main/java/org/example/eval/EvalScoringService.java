package org.example.eval;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Scores an evaluation run from deterministic labels and the recorded trace.
 * It deliberately exposes coverage values instead of hiding decisions behind
 * an opaque judge model.
 */
@Service
public class EvalScoringService {

    private final EvalGoldenScenarioService goldenScenarioService;

    public EvalScoringService(EvalGoldenScenarioService goldenScenarioService) {
        this.goldenScenarioService = goldenScenarioService;
    }

    public EvalRunTrace.EvalScore score(EvalRunTrace trace) {
        EvalRunTrace.EvalScore score = new EvalRunTrace.EvalScore();
        Optional<EvalGoldenScenario> goldenOptional = goldenScenarioService.load(trace.getScenarioId());
        if (goldenOptional.isEmpty()) {
            score.getFailureTags().add("GOLDEN_NOT_FOUND");
            return score;
        }
        return scoreAgainst(trace, goldenOptional.get());
    }

    /**
     * Scores a trace against labels supplied by the caller.
     *
     * <p>Kept separate from the lookup above so tests can score with an in-memory fixture
     * instead of placing files on the evaluation classpath, where they would be picked up
     * by scenario discovery and silently change what a batch run evaluates.
     */
    public EvalRunTrace.EvalScore scoreAgainst(EvalRunTrace trace, EvalGoldenScenario golden) {
        EvalRunTrace.EvalScore score = new EvalRunTrace.EvalScore();
        Set<String> calledTools = new HashSet<>();
        int accurateCalls = 0;
        int totalCalls = trace.getToolCalls().size();
        boolean ragResultFound = false;

        for (EvalRunTrace.ToolCall call : trace.getToolCalls()) {
            calledTools.add(call.getToolName());
            boolean expectedTool = golden.getRequiredTools().contains(call.getToolName())
                    || golden.getOptionalTools().contains(call.getToolName());
            if (expectedTool && call.isSuccess()) {
                accurateCalls++;
            }
            if ("queryInternalDocs".equals(call.getToolName()) && call.isDataFound()) {
                ragResultFound = true;
            }
        }

        int requiredHits = (int) golden.getRequiredTools().stream().filter(calledTools::contains).count();
        double requiredToolRecall = ratio(requiredHits, golden.getRequiredTools().size());
        double toolCallAccuracy = ratio(accurateCalls, totalCalls);

        String report = normalize(trace.getFinalReport());
        DiagnosisMatch diagnosisMatch = bestDiagnosisMatch(golden.getAcceptableDiagnoses(), report);

        score.setScored(true);
        score.setRequiredToolRecall(requiredToolRecall);
        score.setToolCallAccuracy(toolCallAccuracy);
        score.setRootCauseCoverage(diagnosisMatch.rootCauseCoverage());
        score.setEvidenceCoverage(diagnosisMatch.evidenceCoverage());
        score.setActionCoverage(diagnosisMatch.actionCoverage());
        score.setRagResultFound(ragResultFound);
        score.setMatchedDiagnosisId(diagnosisMatch.diagnosisId());

        double toolScore = 25 * (requiredToolRecall * 0.6 + toolCallAccuracy * 0.4);
        double total = toolScore
                + 30 * diagnosisMatch.rootCauseCoverage()
                + 25 * diagnosisMatch.evidenceCoverage()
                + 10 * diagnosisMatch.actionCoverage()
                + (trace.isSuccess() && !report.isBlank() ? 10 : 0);
        score.setTotal(round(total));

        if (!trace.isSuccess()) {
            score.getFailureTags().add("EXECUTION_FAILURE");
        }
        if (requiredToolRecall < 1) {
            score.getFailureTags().add("TOOL_MISSING");
        }
        if (toolCallAccuracy < 1) {
            score.getFailureTags().add("TOOL_INVALID_OR_FAILED");
        }
        if (calledTools.contains("queryInternalDocs") && !ragResultFound) {
            score.getFailureTags().add("RAG_NO_RESULT");
        }
        if (diagnosisMatch.rootCauseCoverage() < 1) {
            score.getFailureTags().add("ROOT_CAUSE_MISS");
        }
        if (diagnosisMatch.evidenceCoverage() < 1) {
            score.getFailureTags().add("EVIDENCE_GAP");
        }
        if (diagnosisMatch.actionCoverage() < 1) {
            score.getFailureTags().add("ACTION_GAP");
        }

        score.setTaskSuccess(trace.isSuccess()
                && requiredToolRecall == 1
                && diagnosisMatch.rootCauseCoverage() == 1
                && diagnosisMatch.evidenceCoverage() == 1
                && score.getTotal() >= 80);
        return score;
    }

    private DiagnosisMatch bestDiagnosisMatch(List<EvalGoldenScenario.Diagnosis> diagnoses, String report) {
        DiagnosisMatch best = new DiagnosisMatch(null, 0, 0, 0);
        for (EvalGoldenScenario.Diagnosis diagnosis : diagnoses) {
            DiagnosisMatch candidate = new DiagnosisMatch(
                    diagnosis.getId(),
                    groupsCoverage(diagnosis.getRootCauseTermGroups(), report),
                    groupsCoverage(diagnosis.getEvidenceTermGroups(), report),
                    groupsCoverage(diagnosis.getActionTermGroups(), report));
            if (candidate.weightedCoverage() > best.weightedCoverage()) {
                best = candidate;
            }
        }
        return best;
    }

    /** Each group represents one required concept, while terms within it are synonyms. */
    private double groupsCoverage(List<List<String>> groups, String report) {
        if (groups == null || groups.isEmpty()) {
            return 1;
        }
        int matched = 0;
        for (List<String> group : groups) {
            boolean groupMatched = group.stream().map(this::normalize).anyMatch(report::contains);
            if (groupMatched) {
                matched++;
            }
        }
        return ratio(matched, groups.size());
    }

    private double ratio(int numerator, int denominator) {
        return denominator == 0 ? 1 : round((double) numerator / denominator);
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private record DiagnosisMatch(String diagnosisId,
                                  double rootCauseCoverage,
                                  double evidenceCoverage,
                                  double actionCoverage) {
        private double weightedCoverage() {
            return rootCauseCoverage * 0.5 + evidenceCoverage * 0.35 + actionCoverage * 0.15;
        }
    }
}
