package org.demo01.stream;

import dev.langchain4j.service.TokenStream;
import org.demo01.service.Assistant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/demo01/flux")
public class Test04 {
    @Autowired
    private Assistant agent;


    @GetMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    private Flux<String> chat(@RequestParam String prompt) {
        System.out.println("开始执行");
        return Flux.create(sink -> {
            TokenStream res = agent.chat(prompt);
            res.onPartialResponse(sink::next)
                    .onCompleteResponse(response -> {
                        sink.complete();
                    })
                    .onError(sink::error)
                    .start();
        });
    }
}
