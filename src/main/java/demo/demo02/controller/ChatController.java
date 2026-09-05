package demo.demo02.controller;

import demo.demo02.service.ChatService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/demo02")
public class ChatController {
    @Autowired
    private ChatService service;

    @GetMapping("/chat")
    public String chat(String prompt) {
        return service.chat(prompt);
    }
}
