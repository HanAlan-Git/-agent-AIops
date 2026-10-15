package org.example.agent.tool;

import org.example.eval.EvalRunTrace;
import org.example.eval.EvalTraceContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;

@Component
public class DateTimeTools {
    
    /** 工具名常量，用于动态构建提示词 */
    public static final String TOOL_GET_CURRENT_DATETIME = "getCurrentDateTime";
    
    @Tool(description = "Get the current date and time in the user's timezone")
    public String getCurrentDateTime() {
        EvalRunTrace.ToolCall trace = EvalTraceContext.beginTool(TOOL_GET_CURRENT_DATETIME, Map.of());
        String result = LocalDateTime.now().atZone(LocaleContextHolder.getTimeZone().toZoneId()).toString();
        EvalTraceContext.finishTool(trace, result);
        return result;
    }
}
