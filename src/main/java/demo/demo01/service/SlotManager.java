package demo.demo01.service;

import demo.demo01.dto.ExchangeSlotState;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class SlotManager {
    @Autowired
    private SlotStateStore store;
    @Autowired
    private SlotExtractor extractor;

    public SlotTurn handle(String sessionId, String userManage) {
        // 读该会话当前槽位
        ExchangeSlotState state = store.get(sessionId);
        // 从用户话术抽取可能给出的槽位值
        ExchangeSlotState proposed = extractor.applyTo(userManage, state);
        boolean complete = proposed.isComplete();
        ExchangeSlotState outcome = complete && state.status().equals("COLLECTING")
                ? new ExchangeSlotState(state.sessionId(), proposed.orderId(), proposed.newSize(), "READY") : proposed;

        store.save(outcome);
        String directive = outcome.isComplete()
                ? "槽位已齐全（订单 %s，尺码 %s），现在可以调用 createExchange 工具。".formatted(
                outcome.orderId(), outcome.newSize())
                : "当前还缺槽位：" + missing(outcome)
                  + "，本轮必须先向用户追问这些信息，禁止在缺槽位时调用工具。";

        return new SlotTurn(directive, outcome.isComplete(), outcome);

    }

    private String missing(ExchangeSlotState s) {
        if (s.orderId() == null) return "订单号";
        if (s.newSize() == null) return "新尺码";
        return "";
    }


    public record SlotTurn(String directive, boolean ready, ExchangeSlotState state) {

    }
}
