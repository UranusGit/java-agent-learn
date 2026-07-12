package org.demo01.util;

import dev.langchain4j.agent.tool.Tool;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class TimeTools {
    @Tool("获取当前系统时间，格式为 yyyy-MM-dd HH:mm:ss")
    public String getTime() {
        return LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }
}
