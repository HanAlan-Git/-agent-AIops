package org.example.agent.tool;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Parameter validation for agent tools.
 *
 * <p>Spring AI derives each tool's JSON Schema from its signature, but a schema can only
 * express structural constraints (type, required). Domain constraints, such as which log
 * topics exist, must be checked at the tool boundary. Rejecting an invalid argument here is
 * what keeps a model from silently querying with a default value and then reporting the
 * unrelated result as evidence.
 */
public final class ToolParameterValidator {

    /** Regions the CLS integration supports. */
    public static final List<String> VALID_REGIONS = List.of(
            "ap-guangzhou", "ap-shanghai", "ap-beijing", "ap-chengdu"
    );

    /** Known log topics, mirrored from the tool description. */
    public static final List<String> KNOWN_TOPICS = List.of(
            "system-metrics", "application-logs", "database-slow-query", "system-events"
    );

    /**
     * CLS TopicId shape, for callers that pass an id instead of a topic name.
     *
     * <p>Real TopicIds look like {@code 3f7a9b2c-1d4e-5f60-8a91-b2c3d4e5f607}: several
     * groups joined by separators, and they contain digits. Requiring both keeps a
     * natural-language guess such as {@code system-metric} or {@code unknown-topic-name}
     * from being accepted as an id and silently reaching the backend.
     *
     * <p>Tradeoff: a purely alphabetic id with fewer than two separators would be rejected.
     * That is the deliberate direction to fail in — the model receives the valid options
     * back and retries, instead of the query silently returning unrelated logs.
     */
    private static final Pattern TOPIC_ID =
            Pattern.compile("^(?=.*[0-9])[a-zA-Z0-9]+(?:[-_][a-zA-Z0-9]+){2,}$");

    private ToolParameterValidator() {
    }

    /**
     * @return {@code null} when the arguments are usable, otherwise a rejection reason that
     *         can be fed straight back to the model so it can correct itself.
     */
    public static String validateLogQuery(String region, String logTopic) {
        if (region != null && !region.isBlank() && !VALID_REGIONS.contains(region.trim())) {
            return String.format("参数非法：region=%s 不受支持，可选值: %s；请修正后重试", region, VALID_REGIONS);
        }
        if (logTopic == null || logTopic.isBlank()) {
            return "参数非法：logTopic 不能为空，可选值: " + KNOWN_TOPICS;
        }
        String trimmedTopic = logTopic.trim();
        String normalizedTopic = trimmedTopic.toLowerCase();
        if (!KNOWN_TOPICS.contains(normalizedTopic) && !TOPIC_ID.matcher(trimmedTopic).matches()) {
            return String.format("参数非法：logTopic=%s 不受支持，可选值: %s，或使用 CLS TopicId；请修正后重试",
                    logTopic, KNOWN_TOPICS);
        }
        return null;
    }

    /** Clamps the requested page size into the range the backend accepts. */
    public static int normalizeLimit(Integer limit, int defaultLimit, int maxLimit) {
        if (limit == null || limit <= 0) {
            return defaultLimit;
        }
        return Math.min(limit, maxLimit);
    }
}
