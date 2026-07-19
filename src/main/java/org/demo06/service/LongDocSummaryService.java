package org.demo06.service;

import org.demo06.workflows.OrchestratorService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.function.BiFunction;

@Service
public class LongDocSummaryService extends OrchestratorService {
    protected LongDocSummaryService(ChatClient client) {
        super(client);
    }

    @Override
    protected BiFunction<String, String, String> worker() {
        return (prompt, sid) -> call("你是文档子任务的执行者，按子任务描述处理原文", prompt, sid);
    }
}
