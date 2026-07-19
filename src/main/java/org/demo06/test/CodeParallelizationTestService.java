package org.demo06.test;

import org.demo06.workflows.ParallelizationService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.BiFunction;

@Service
public class CodeParallelizationTestService extends ParallelizationService {
    protected CodeParallelizationTestService(ChatClient client) {
        super(client);
    }

    @Override
    protected List<BiFunction<String, String, String>> works() {
        return List.of(
                (prompt, sid) -> call("从bug风险角度分析", prompt, sid),
                (prompt, sid) -> call("从代码风格角度分析", prompt, sid),
                (prompt, sid) -> call("从安全漏洞角度分析", prompt, sid)
        );
    }

    @Override
    protected BiFunction<List<String>, String, String> aggregator() {
        return (results, sid) -> {
            if (results.size() > 1) {
                return call("整合总结分析结果", String.join("\n\n --- \n\n", results), sid);
            } else {
                return results.get(0);
            }
        };
    }
}
