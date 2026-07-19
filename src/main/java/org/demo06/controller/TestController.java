package org.demo06.controller;

import org.demo06.service.ArticleChainingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/demo06/workflow")
public class TestController {

    @Autowired
    private ArticleChainingService articleChainingService;

    @GetMapping("/chat")
    public String article(@RequestParam String prompt, @RequestHeader String sessionId) {
        return articleChainingService.run(prompt, sessionId);
    }
}
