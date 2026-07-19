package org.demo06.test;

import org.demo06.workflows.ChainingService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.BiFunction;

@Service
public class CodeChainingServiceManager extends ChainingService {
    @Autowired
    private CodeEvaluatorOptimizerTestService codeEvaluatorOptimizerTestService;

    @Autowired
    private CodeParallelizationTestService codeParallelizationTestService;

    @Autowired
    private CodeRoutingTestService codeRoutingTestService;

    @Autowired
    private LongCodeSummaryTestService longCodeSummaryTestService;

    protected CodeChainingServiceManager(ChatClient chatClient) {
        super(chatClient);
    }

    @Override
    protected List<BiFunction<String, String, String>> steps() {
        return List.of(
                (prompt, sid) -> codeRoutingTestService.run(prompt, sid),
                (prompt, sid) -> codeParallelizationTestService.run(prompt, sid),
                (prompt, sid) -> longCodeSummaryTestService.run(prompt, sid),
                (prompt, sid) -> codeEvaluatorOptimizerTestService.run(prompt, sid)
        );
    }
}
