package org.example.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The batch runner can only evaluate scenarios it can discover. This test guards the one
 * failure mode that would otherwise waste a whole batch of model calls: discovery silently
 * returning an empty or partial list.
 *
 * <p>A scenario is only usable when both files exist — the runtime payload under
 * {@code eval/scenarios} and its labels under {@code eval/golden}. Counting the runtime
 * payloads independently is what makes the equality assertion meaningful.
 */
class EvalScenarioCorpusTest {

    private static final List<String> NEGATIVE_SAMPLES = List.of(
            "neg-insufficient-evidence-01",
            "neg-tool-param-invalid-01"
    );

    private final EvalGoldenScenarioService scenarioService =
            new EvalGoldenScenarioService(new ObjectMapper());

    @Test
    @DisplayName("每个运行时场景都有对应金标准标注（否则该场景不可评分）")
    void everyScenarioHasGoldenLabels() throws IOException {
        Resource[] scenarioFiles = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:eval/scenarios/*.json");

        assertThat(scenarioFiles)
                .as("src/main/resources/eval/scenarios 下应当有场景文件")
                .isNotEmpty();

        for (Resource file : scenarioFiles) {
            String scenarioId = stripJsonSuffix(file.getFilename());
            assertThat(scenarioService.load(scenarioId))
                    .as("场景 %s 缺少 eval/golden/%s.json，跑批量评测时会拿到 GOLDEN_NOT_FOUND", scenarioId, scenarioId)
                    .isPresent();
        }
    }

    @Test
    @DisplayName("可发现场景数与运行时场景数一致，且覆盖五类告警与负样本")
    void discoversEveryUsableScenario() throws IOException {
        List<String> discovered = scenarioService.listScenarioIds();
        int runtimeScenarioCount = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:eval/scenarios/*.json").length;

        assertThat(discovered).hasSize(runtimeScenarioCount);
        assertThat(discovered).doesNotHaveDuplicates();
        assertThat(discovered).containsAll(NEGATIVE_SAMPLES);

        // 简历里写的"覆盖 CPU / 内存 / 磁盘 / 服务不可用 / 慢响应五类 + 2 条负样本"必须可核对
        assertThat(countByPrefix(discovered, "cpu-")).as("CPU 类场景数").isGreaterThanOrEqualTo(4);
        assertThat(countByPrefix(discovered, "memory-")).as("内存类场景数").isGreaterThanOrEqualTo(4);
        assertThat(countByPrefix(discovered, "disk-")).as("磁盘类场景数").isGreaterThanOrEqualTo(4);
        assertThat(countByPrefix(discovered, "svc-")).as("服务不可用类场景数").isGreaterThanOrEqualTo(4);
        assertThat(countByPrefix(discovered, "slow-")).as("慢响应类场景数").isGreaterThanOrEqualTo(4);
        assertThat(countByPrefix(discovered, "neg-")).as("负样本数").isEqualTo(2);
    }

    @Test
    @DisplayName("可发现场景数应是生产语料的 23 条")
    void corpusSizeMatchesResumeClaim() {
        assertThat(scenarioService.listScenarioIds())
                .as("简历写的是 23 条场景；数量变了就要同步改简历")
                .hasSize(23);
    }

    @Test
    @DisplayName("单测夹具不得混入生产语料（否则批量评测会多跑一条假场景）")
    void unitFixtureIsNotDiscoverable() {
        assertThat(getClass().getClassLoader().getResource("eval/fixtures/unit-eval-scenario-01.golden.json"))
                .as("评分器夹具应当位于 src/test/resources/eval/fixtures")
                .isNotNull();
        assertThat(getClass().getClassLoader().getResource("eval/golden/unit-eval-scenario-01.json"))
                .as("该文件若出现在 test 的 eval/golden 下，会被场景发现逻辑当成真实场景")
                .isNull();
        assertThat(scenarioService.listScenarioIds()).doesNotContain("unit-eval-scenario-01");
    }

    private long countByPrefix(List<String> scenarioIds, String prefix) {
        return scenarioIds.stream().filter(id -> id.startsWith(prefix)).count();
    }

    private String stripJsonSuffix(String fileName) {
        assertThat(fileName).endsWith(".json");
        return fileName.substring(0, fileName.length() - ".json".length());
    }
}
