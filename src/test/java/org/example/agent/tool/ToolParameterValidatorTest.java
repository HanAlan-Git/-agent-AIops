package org.example.agent.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tool parameters are the boundary where a model's guess turns into a real query.
 * These tests pin down that an unusable argument is rejected with an actionable
 * message instead of being silently replaced by a default.
 */
class ToolParameterValidatorTest {

    @Test
    @DisplayName("合法参数放行：已知主题 + 受支持地域")
    void acceptsKnownTopicAndRegion() {
        assertThat(ToolParameterValidator.validateLogQuery("ap-guangzhou", "system-metrics")).isNull();
        assertThat(ToolParameterValidator.validateLogQuery("ap-shanghai", "database-slow-query")).isNull();
    }

    @Test
    @DisplayName("地域与主题为空时使用服务端默认值，不算非法")
    void acceptsBlankRegionAndMissingRegion() {
        assertThat(ToolParameterValidator.validateLogQuery(null, "application-logs")).isNull();
        assertThat(ToolParameterValidator.validateLogQuery("  ", "application-logs")).isNull();
    }

    @ParameterizedTest
    @DisplayName("主题大小写不敏感")
    @ValueSource(strings = {"SYSTEM-METRICS", "System-Metrics", "system-metrics"})
    void acceptsTopicCaseInsensitively(String topic) {
        assertThat(ToolParameterValidator.validateLogQuery("ap-guangzhou", topic)).isNull();
    }

    @Test
    @DisplayName("非法地域被拒绝，且错误信息里带可选值")
    void rejectsUnsupportedRegion() {
        String rejection = ToolParameterValidator.validateLogQuery("cn-north-1", "system-metrics");

        assertThat(rejection).isNotNull();
        assertThat(rejection).contains("cn-north-1").contains("ap-guangzhou");
    }

    @ParameterizedTest
    @DisplayName("非主题名一律拒绝；含分隔符的短横线串按 TopicId 约定放行")
    @ValueSource(strings = {"system-metric:REJECT", "applogs:REJECT", "日志:REJECT",
            "unknown-topic-name:REJECT", "system-metrics:ACCEPT", "application-logs:ACCEPT"})
    void rejectsUnknownTopic(String spec) {
        String topic = spec.substring(0, spec.lastIndexOf(':'));
        boolean expectedAccepted = spec.endsWith(":ACCEPT");

        String rejection = ToolParameterValidator.validateLogQuery("ap-guangzhou", topic);

        if (expectedAccepted) {
            assertThat(rejection)
                    .as("含分隔符的值符合文档中‘支持 CLS TopicId’的约定，应放行: %s", topic)
                    .isNull();
        } else {
            assertThat(rejection).as("非主题名应被拒绝: %s", topic).isNotNull();
            assertThat(rejection).contains(topic);
        }
    }

    @ParameterizedTest
    @DisplayName("形似 TopicId 但不含分隔符的猜测值被拒绝")
    @ValueSource(strings = {"applogs", "metrics", "topic2026"})
    void rejectsTopicIdLookalikeWithoutSeparator(String topic) {
        assertThat(ToolParameterValidator.validateLogQuery("ap-guangzhou", topic)).isNotNull();
    }

    @ParameterizedTest
    @DisplayName("空主题被拒绝")
    @NullAndEmptySource
    void rejectsBlankTopic(String topic) {
        assertThat(ToolParameterValidator.validateLogQuery("ap-guangzhou", topic)).isNotNull();
    }

    @ParameterizedTest
    @DisplayName("CLS TopicId 形态仍然可用")
    @ValueSource(strings = {"3f7a9b2c-1d4e-5f60-8a91-b2c3d4e5f607", "topic-2026-09-ab"})
    void acceptsClsTopicId(String topic) {
        assertThat(ToolParameterValidator.validateLogQuery("ap-guangzhou", topic)).isNull();
    }

    @ParameterizedTest
    @DisplayName("limit 归一化：空值与非法值落到默认值，超限被夹紧")
    @CsvSource({
            ", 20",
            "0, 20",
            "-5, 20",
            "1, 1",
            "20, 20",
            "100, 100",
            "5000, 100"
    })
    void normalizesLimit(Integer limit, int expected) {
        assertThat(ToolParameterValidator.normalizeLimit(limit, 20, 100)).isEqualTo(expected);
    }
}
