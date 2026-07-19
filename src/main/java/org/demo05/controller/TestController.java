package org.demo05.controller;


import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/demo05/graph")
public class TestController {
    @Autowired
    private ChatClient client;

    @Autowired
    @Qualifier("ReportPipelineGraph")
    private CompiledGraph routerGraph;

    @GetMapping("/chat")
    public String test(@RequestParam String prompt, @RequestHeader String sessionId) {
        return client.prompt()
                .user(prompt)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, sessionId))
                .call()
                .content();
    }

    @GetMapping("/route")
    public String route(@RequestParam String prompt, @RequestHeader String sessionId) {
        // threadId 是"图的执行身份"，用于 checkpoint 存取：同 threadId 多次调用可续接 state。
        // 注意：threadId 走 RunnableConfig（执行元数据），不是 State（业务数据）。
        RunnableConfig config = RunnableConfig.builder()
                .threadId(sessionId)
                .build();

        OverAllState result = routerGraph
                .invoke(Map.of("user_query", prompt, "sessionId", sessionId), config)
                .orElseThrow(() -> new IllegalStateException("graph returned empty state"));

        System.out.println("=== final state keys ===");
        result.data().keySet().forEach(k -> System.out.println("  " + k + " = " + result.value(k)));
        System.out.println("========================");

        return result.value("answer")
                .or(() -> result.value("final_report"))
                .map(Object::toString)
                .orElse("(empty)");
    }

    @GetMapping("/history")
    public Object history(@RequestParam String prompt, @RequestHeader String sessionId) {
        // threadId 是"图的执行身份"，用于 checkpoint 存取：同 threadId 多次调用可续接 state。
        // 注意：threadId 走 RunnableConfig（执行元数据），不是 State（业务数据）。
        RunnableConfig config = RunnableConfig.builder()
                .threadId(sessionId)
                .build();

        return routerGraph.getStateHistory(config);
    }
}
