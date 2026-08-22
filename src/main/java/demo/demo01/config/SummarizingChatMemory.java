package demo.demo01.config;

import demo.demo01.service.SummaryService;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class SummarizingChatMemory implements ChatMemory {
    private static final int COMPRESS_THRESHOLD = 30;
    private static final int KEEP_RECENT = 10;

    @Autowired
    private ChatMemoryRepository repository;

    @Autowired
    @Lazy
    private SummaryService summaryService;

    @Override
    public void add(String conversationId, List<Message> messages) {
        List<Message> history = new ArrayList<>(repository.findByConversationId(conversationId));
        history.addAll(messages);

        if (history.size() > COMPRESS_THRESHOLD) {
            List<Message> toSummarize = history.subList(0, history.size() - KEEP_RECENT);
            String summary = summaryService.summarize(toSummarize);

            List<Message> newHistory = new ArrayList<>();
            newHistory.add(new AssistantMessage("【历史摘要】 " + summary));
            newHistory.addAll(history.subList(history.size() - KEEP_RECENT, history.size()));

            history = newHistory;
        }
        repository.saveAll(conversationId, history);

    }

    @Override
    public List<Message> get(String conversationId) {
        return repository.findByConversationId(conversationId);
    }

    @Override
    public void clear(String conversationId) {
        repository.deleteByConversationId(conversationId);
    }
}
