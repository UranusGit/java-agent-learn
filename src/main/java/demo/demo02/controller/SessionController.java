package demo.demo02.controller;

import demo.demo02.entity.SessionEntity;
import demo.demo02.service.SessionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/demo02")
public class SessionController {
    @Autowired
    private SessionService service;

    @GetMapping("/sessions")
    public ResponseEntity sessions() {
        List<SessionEntity> sessions = service.sessions();
        return ResponseEntity.ok(sessions);
    }
}
