package org.example.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/** Loads labels for a future scorer without exposing them through agent tools. */
@Service
public class EvalGoldenScenarioService {

    private static final Pattern SCENARIO_ID = Pattern.compile("[a-z0-9-]+");
    private static final String SCENARIO_PATTERN = "classpath*:eval/scenarios/*.json";

    private final ObjectMapper objectMapper;

    public EvalGoldenScenarioService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Lists every scenario id that has both a runtime payload and a golden label file.
     *
     * <p>Uses the classpath resource resolver rather than a directory URI so the same code
     * works from a folder (IDE / {@code mvn exec:java}) and from inside a packaged jar.
     */
    public List<String> listScenarioIds() {
        Resource[] resources;
        try {
            resources = new PathMatchingResourcePatternResolver().getResources(SCENARIO_PATTERN);
        } catch (IOException e) {
            throw new IllegalStateException("无法枚举评测场景", e);
        }
        return Arrays.stream(resources)
                .map(Resource::getFilename)
                .filter(name -> name != null && name.endsWith(".json"))
                .map(name -> name.substring(0, name.length() - ".json".length()))
                .filter(name -> SCENARIO_ID.matcher(name).matches())
                .filter(name -> new ClassPathResource("eval/golden/" + name + ".json").exists())
                .sorted()
                .toList();
    }

    public Optional<EvalGoldenScenario> load(String scenarioId) {
        if (scenarioId == null || !SCENARIO_ID.matcher(scenarioId).matches()) {
            return Optional.empty();
        }

        ClassPathResource resource = new ClassPathResource("eval/golden/" + scenarioId + ".json");
        if (!resource.exists()) {
            return Optional.empty();
        }

        try (InputStream inputStream = resource.getInputStream()) {
            EvalGoldenScenario golden = objectMapper.readValue(inputStream, EvalGoldenScenario.class);
            return scenarioId.equals(golden.getScenarioId()) ? Optional.of(golden) : Optional.empty();
        } catch (IOException e) {
            throw new IllegalStateException("无法读取评测标注: " + scenarioId, e);
        }
    }
}
