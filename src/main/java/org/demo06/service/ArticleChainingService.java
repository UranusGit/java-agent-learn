package org.demo06.service;

import org.demo06.workflows.chaining.ChainingService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.BiFunction;

@Service
public class ArticleChainingService extends ChainingService {

    public ArticleChainingService(ChatClient chatClient) {
        super(chatClient);
    }

    @Override
    protected List<BiFunction<String, String, String>> steps() {
        return List.of(
                (topic, sid) -> call("生成大纲，只输出大纲", topic, sid),
                (outline, sid) -> call("根据大纲生成草稿，只输出草稿", outline, sid),
                (draft, sid) -> call("润色草稿让它更流畅，只输出最终文本", draft, sid)
        );
    }
}
