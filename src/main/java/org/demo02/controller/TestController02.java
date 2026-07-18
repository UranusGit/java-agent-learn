package org.demo02.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

@Slf4j
@RestController
@RequestMapping("/demo02/devisor")
public class TestController02 {

    @Autowired
    public ChatClient chatClient;

    @GetMapping("/chat")
    public String chat(String prompt, @RequestHeader String sessionId) {
        return chatClient.prompt()
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, sessionId))
                .user(prompt)
                .call()
                .content();
    }

    @GetMapping("/muti-chat")
    public String mutiChat(@RequestParam String prompt, @RequestHeader String sessionId) {
        return chatClient.prompt().user(prompt)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, sessionId))
                .call().content();
    }

    @GetMapping(value = "/muti-chat-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> mutiChatStream(@RequestParam String prompt, @RequestHeader String sessionId) {
        return chatClient.prompt()
                .user(prompt)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, sessionId))
                .stream()
                .content();
    }


    @GetMapping(value = "/merge-chat-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> mergeChatStream(@RequestParam String prompt, @RequestHeader String sessionId) {
        // SSE 心跳：每隔 1s 发一条注释行（": ping\n\n"），强制服务端写入。
        // Spring SSE 只有在"写下一个 chunk 失败"时才会感知客户端断开（spring-framework#18523），
        // 心跳让断开能在 ~1s 内被检测到，从而触发 cancel 向下游 Flux 传播。

        Flux<String> tech = chatClient.prompt()
                .user("从技术角度回答：" + prompt)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, sessionId + "tech"))
                .stream()
                .content()
                .map(chunk -> "[技术]" + chunk);

        Flux<String> biz = chatClient.prompt()
                .user("从业务角度回答：" + prompt)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, sessionId + "biz"))
                .stream()
                .content()
                .map(chunk -> "[业务]" + chunk);

        return Flux.merge(tech, biz)
                .doOnCancel(() -> log.info("merge-chat-stream 整体已取消（客户端断开）"))
                .doOnComplete(() -> log.info("merge-chat-stream 完成"));
    }

}
