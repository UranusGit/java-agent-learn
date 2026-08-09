package demo.demo02.controller;

import demo.demo02.entity.HistoryEntity;
import demo.demo02.service.HistoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/demo02")
public class HistoryController {
    @Autowired
    public HistoryService history;

    @GetMapping("/histories")
    public ResponseEntity histories(String sessionId) {
        List<HistoryEntity> histories = history.histories(sessionId);
        return ResponseEntity.ok(histories);
    }
}
