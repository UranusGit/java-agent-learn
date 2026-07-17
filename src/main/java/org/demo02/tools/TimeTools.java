package org.demo02.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Component
public class TimeTools {
    @Tool(description = "获取当前时间，格式为 ISO 标准格式")
    private String getTime() {
        System.out.println("调用了TimeTool");
        return LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }
}
