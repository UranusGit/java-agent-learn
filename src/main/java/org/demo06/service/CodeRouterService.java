package org.demo06.service;

import org.demo06.workflows.RoutingService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.function.BiFunction;

@Service
public class CodeRouterService extends RoutingService {
    protected CodeRouterService(ChatClient client) {
        super(client);
    }

    @Override
    protected BiFunction<String, String, String> classifier() {
        return (prompt, sid) -> classify("""
                判断代码类型，只能输出一个词：
                - controller
                - service
                - repository
                - model
                - other
                """, prompt, sid).toLowerCase().trim();
    }

    @Override
    protected Map<String, BiFunction<String, String, String>> handlers() {
        return Map.of(
                "controller", (prompt, sid) -> call("你是 Controller 评审专家，重点检查路由、参数校验、异常处理", prompt, sid),
                "service", (prompt, sid) -> call("你是 Service 评审专家，重点检查事务、业务逻辑、性能", prompt, sid),
                "repository", (prompt, sid) -> call("你是 Repository 评审专家，重点检查 SQL、索引、N+1", prompt, sid),
                "other", (prompt, sid) -> call("你是通用代码评审专家", prompt, sid)
        );
    }

    @Override
    protected String defaultRoute() {
        return "other";
    }
}
