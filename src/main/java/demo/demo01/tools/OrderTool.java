package demo.demo01.tools;

import demo.demo01.dto.OrderInfo;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.Map;

public class OrderTool {
    private static final Map<String, OrderInfo> ORDERS = Map.of(
            "DD20240810", new OrderInfo("DD20240810", "已发货", "SF123456789"),
            "DD20240811", new OrderInfo("DD20240811", "待支付", null)
    );

    @Tool(description = "根据订单号查询订单状态与物流单号")
    public OrderInfo queryOrder(@ToolParam(description = "订单号") String orderId) {
        return ORDERS.get(orderId);
    }
}
