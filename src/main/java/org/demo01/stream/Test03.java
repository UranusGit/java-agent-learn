package org.demo01.stream;

import dev.langchain4j.service.TokenStream;
import org.demo01.service.Assistant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

@RestController
@RequestMapping("/demo01/langchain")
public class Test03 {

    @Autowired
    private Assistant assistant;

    @GetMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestParam String prompt) {
        SseEmitter emitter = new SseEmitter(300000L);

        TokenStream res = assistant.chat(prompt);
        res.onPartialResponse(token -> {
                    try {
                        System.out.print(token);
                        emitter.send(token);
                    } catch (IOException e) {
                        emitter.completeWithError(e);
                    }
                })
                .onCompleteResponse(response -> {
                    emitter.complete();
                    System.out.println("\n完成！！！");
                })
                .onError(emitter::completeWithError)
                .start();
        return emitter;
    }
}
