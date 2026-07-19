package org.demo06.workflows;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;

import java.util.Map;
import java.util.function.BiFunction;

public abstract class RoutingService {
    private final ChatClient client;

    /**
     * 子类声明分类器（入参原始输入，出参路由 key）。
     */
    protected abstract BiFunction<String, String, String> classifier();

    /**
     * 子类声明路由表：route key → handler（handler 入参 input + sessionId）。
     */
    protected abstract Map<String, BiFunction<String, String, String>> handlers();

    /**
     * 默认路由 key（必须存在于 handlers，否则 NPE）。
     */
    protected abstract String defaultRoute();

    protected RoutingService(ChatClient client) {
        this.client = client;
    }


    public String run(String input, String sid) {
        String route = classifier().apply(input, sid);

        BiFunction<String, String, String> handler =
                handlers().getOrDefault(route, handlers().get(defaultRoute()));

        return handler.apply(input, sid);
    }

    protected String classify(String system, String prompt, String sid) {
        return client.prompt()
                .system(system)
                .user(prompt)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, sid))
                .call()
                .content();
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
