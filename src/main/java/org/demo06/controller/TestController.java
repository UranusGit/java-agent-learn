package org.demo06.controller;

import org.demo06.service.*;
import org.demo06.test.CodeChainingServiceManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/demo06/workflow")
public class TestController {

    @Autowired
    private ArticleChainingService articleChainingService;

    @Autowired
    private MultiAngleAnalysisService multiAngleAnalysisService;

    @Autowired
    private VotingReviewService votingReviewService;

    @Autowired
    private CodeRouterService codeRouterService;

    @Autowired
    private LongDocSummaryService longDocSummaryService;

    @Autowired
    private CodeRefinerService codeRefinerService;

    @Autowired
    private CodeChainingServiceManager codeChainingServiceManager;

    @GetMapping("/chaining")
    public String article(@RequestParam String prompt, @RequestHeader String sessionId) {
        return articleChainingService.run(prompt, sessionId);
    }


    @GetMapping("/parallelization")
    public String sectioning(@RequestParam String prompt, @RequestHeader String sessionId) {
        return votingReviewService.run(prompt, sessionId);
    }


    @GetMapping("/code-router")
    public String codeRouter(@RequestParam String prompt, @RequestHeader String sessionId) {
        return codeRouterService.run(prompt, sessionId);
    }

    @GetMapping("/long-doc")
    public String longDocSummary(@RequestParam String prompt, @RequestHeader String sessionId) {
        return longDocSummaryService.run(prompt, sessionId);
    }

    @GetMapping("/code-refiner")
    public String codeRefiner(@RequestParam String prompt, @RequestHeader String sessionId) {
        return codeRefinerService.run(prompt, sessionId);
    }

    @GetMapping("/code-reviewer")
    public String codeReviewer(@RequestParam String prompt, @RequestHeader String sessionId) {
        return codeChainingServiceManager.run(prompt, sessionId);
    }
}
