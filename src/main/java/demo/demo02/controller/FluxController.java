package demo.demo02.controller;

import org.reactivestreams.Subscription;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/demo02")
public class FluxController {
    @GetMapping("/test01")
    public Flux<String> test01() {
        Flux.range(1, 1000)
                .doOnNext(v -> System.out.printf("[%s] >>> 生产(入队): %s%n",
                        Thread.currentThread().getName(), v))
                .publishOn(Schedulers.boundedElastic(), 10)   // 关键：预取队列容量 10
                .onBackpressureBuffer(
                        1
                )
                .subscribe(new BaseSubscriber<Integer>() {
                    int count = 0;

                    @Override
                    protected void hookOnSubscribe(Subscription s) {
                        System.out.println("=== 订阅建立，request(10) ===");
                        request(10);
                    }

                    @Override
                    protected void hookOnNext(Integer value) {
                        count++;
                        try {
                            Thread.sleep(500);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        System.out.printf("[%s] <<< 消费完第 %d 条: %s%n",
                                Thread.currentThread().getName(), count, value);
                        if (count < 30) request(5);
                        else System.exit(0);
                    }
                });
        return Flux.just("123123123").onBackpressureBuffer();
    }
}
