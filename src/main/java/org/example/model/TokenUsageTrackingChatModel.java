package org.example.model;

import org.example.eval.EvalTraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/**
 * Decorates a {@link ChatModel} so that token usage observed during an offline
 * evaluation run is recorded on the active trace. Outside an evaluation run the
 * decorator only forwards the call, so production traffic is unaffected.
 *
 * <p>Usage is only counted when the provider returns usage metadata. If the
 * wrapped model does not expose it, traces keep {@code tokensReported=false}
 * instead of publishing a fabricated number.
 */
public class TokenUsageTrackingChatModel implements ChatModel {

    private static final Logger logger = LoggerFactory.getLogger(TokenUsageTrackingChatModel.class);

    private final ChatModel delegate;

    public TokenUsageTrackingChatModel(ChatModel delegate) {
        this.delegate = delegate;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        ChatResponse response = delegate.call(prompt);
        recordUsage(response);
        return response;
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return delegate.stream(prompt);
    }

    private void recordUsage(ChatResponse response) {
        if (response == null || response.getMetadata() == null || EvalTraceContext.current().isEmpty()) {
            return;
        }
        try {
            Usage usage = response.getMetadata().getUsage();
            if (usage == null) {
                return;
            }
            int promptTokens = usage.getPromptTokens() == null ? 0 : usage.getPromptTokens();
            int completionTokens = usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens();
            if (promptTokens == 0 && completionTokens == 0) {
                return;
            }
            String model = response.getMetadata().getModel();
            EvalTraceContext.recordUsage(promptTokens, completionTokens, model);
            logger.debug("评测 Token 记录: model={}, prompt={}, completion={}", model, promptTokens, completionTokens);
        } catch (RuntimeException e) {
            // Usage metadata shape differs across providers; never fail a request over metrics.
            logger.debug("读取 Token 用量失败，忽略本次记录", e);
        }
    }
}
