package org.demo06.workflows;

import org.demo06.util.TriFunction;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;

import java.util.function.BiFunction;

public abstract class EvaluatorOptimizerService {
    protected final ChatClient client;

    private final int maxIterations;

    protected abstract BiFunction<String, String, String> generator();

    protected abstract TriFunction<String, String, String, EvalResult> evaluator();

    protected EvaluatorOptimizerService(ChatClient client, int maxIterations) {
        this.client = client;
        this.maxIterations = maxIterations;
    }

    public String run(String prompt, String sid) {
        String output = generator().apply(prompt, sid);

        for (int i = 0; i < maxIterations; i++) {
            EvalResult eval = evaluator().apply(prompt, output, sid);
            if (eval.pass) {
                return output;
            }
            output = generator().apply(prompt + "\n\n之前的输出有这些问题，请改进：\n" + eval.feedback, sid);
        }

        return output; // maxIterations 用完强制退出
    }

    public record EvalResult(boolean pass, String feedback) {

    }

    protected String call(String system, String prompt, String sid) {
        return client.prompt()
                .system(system)
                .user(prompt)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, sid))
                .call()
                .content();
    }


}
