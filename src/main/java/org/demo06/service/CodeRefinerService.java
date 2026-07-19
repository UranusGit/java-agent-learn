package org.demo06.service;

import org.demo06.util.TriFunction;
import org.demo06.workflows.EvaluatorOptimizerService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Service;

import java.util.function.BiFunction;

@Service
public class CodeRefinerService extends EvaluatorOptimizerService {
    protected CodeRefinerService(ChatClient client) {
        super(client, 3); // 最多重试三次
    }

    @Override
    protected BiFunction<String, String, String> generator() {
        return (prompt, sid) -> call("生成高质量的 java 代码", prompt, sid);
    }

    @Override
    protected TriFunction<String, String, String, EvalResult> evaluator() {
        return (prompt, code, sid) -> client.prompt()
                .system("""
                        判断代码是否合格，输出 JSON：
                        {"pass": true/false, "feedback": "..."}
                        只输出 JSON。
                        """)
                .user("需求：" + prompt + "\n代码：" + code)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, sid))
                .call()
                .entity(EvalResult.class);
    }
}
