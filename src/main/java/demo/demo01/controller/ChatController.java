package demo.demo01.controller;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/demo01")
public class ChatController {
    @Autowired
    private ObservationRegistry registry;   // ★ 注入 Boot 自动装配的注册表

    @GetMapping("/stream")
    public String stream(String prompt) {
        // ★ 这就是"一个最小观测"：把一段业务包进观测
        return Observation.createNotStarted(
                        "hello.request",                         // ① 观测名（将来指标名/观测名）
                        Observation.Context::new,         // ② 上下文（状态袋，第 03 关详讲）
                        registry)                                 // ③ 注入的注册表
                .lowCardinalityKeyValue("path", "/hello")         // ④ 低基数标签
                .highCardinalityKeyValue("session.id", "s-demo")  // ⑤ 高基数标签
                .observe(() -> "hello");                          // ⑥ 被测观测的业务（returns 值透传）
    }
}
