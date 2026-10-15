package org.example.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
public class EvalScenarioService {

    private static final Pattern SCENARIO_ID = Pattern.compile("[a-z0-9-]+");

    private final ObjectMapper objectMapper;

    public EvalScenarioService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Optional<EvalScenario> load(String scenarioId) {
        if (scenarioId == null || !SCENARIO_ID.matcher(scenarioId).matches()) {
            return Optional.empty();
        }

        ClassPathResource resource = new ClassPathResource("eval/scenarios/" + scenarioId + ".json");
        if (!resource.exists()) {
            return Optional.empty();
        }

        try (InputStream inputStream = resource.getInputStream()) {
            EvalScenario scenario = objectMapper.readValue(inputStream, EvalScenario.class);
            return scenarioId.equals(scenario.getScenarioId()) ? Optional.of(scenario) : Optional.empty();
        } catch (IOException e) {
            throw new IllegalStateException("无法读取评测场景: " + scenarioId, e);
        }
    }
}
