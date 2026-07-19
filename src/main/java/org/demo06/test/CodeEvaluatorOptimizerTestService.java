package org.demo06.test;

import org.demo06.util.TriFunction;
import org.demo06.workflows.EvaluatorOptimizerService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Service;

import java.util.function.BiFunction;

@Service
public class CodeEvaluatorOptimizerTestService extends EvaluatorOptimizerService {
    protected CodeEvaluatorOptimizerTestService(ChatClient client) {
        super(client, 3);
    }

    @Override
    protected BiFunction<String, String, String> generator() {
        return (prompt, sid) -> call("生成高质量的代码评审报告", prompt, sid);
    }

    @Override
    protected TriFunction<String, String, String, EvalResult> evaluator() {
        return (prompt, code, sid) -> client.prompt()
                .system("""
                        判断报告是否合格，还有报告的改进点，输出 JSON：
                        {"pass": true/false, "feedback": "..."}
                        只输出 JSON。
                        """)
                .user("需求：" + prompt + "\n报告：" + code)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, sid))
                .call()
                .entity(EvalResult.class);
    }
}
