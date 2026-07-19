package org.demo06.service;

import org.demo06.workflows.ParallelizationService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.BiFunction;


@Service
public class VotingReviewService extends ParallelizationService {
    protected VotingReviewService(ChatClient client) {
        super(client);
    }

    @Override
    protected List<BiFunction<String, String, String>> works() {
        return List.of(
                (prompt, sid) -> call("你是一个严格的代码评审员，给出 1 - 10 分", prompt, sid),
                (prompt, sid) -> call("你是一个严格的代码评审员，给出 1 - 10 分", prompt, sid),
                (prompt, sid) -> call("你是一个严格的代码评审员，给出 1 - 10 分", prompt, sid)
        );
    }

    @Override
    protected BiFunction<List<String>, String, String> aggregator() {
        return (results, sid) -> {
            List<String> list = results.stream()
                    .sorted((o1, o2) -> extractScore(o1) - extractScore(o2))
                    .toList();
            return "中位数评分：" + results.get(list.size() / 2);
        };
    }

    private int extractScore(String result) {
        return 5;
    }
}
