package demo.demo01.tools;

import demo.demo01.dto.FaqItem;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;

public class FaqTool {
    private static final List<FaqItem> FAQS = List.of(
            new FaqItem("退换货政策是什么", "7天无理由退换，15天内质量问题包换，需保留吊牌与购买凭证。"),
            new FaqItem("支持以旧换新吗", "支持，需旧机可开机、屏幕完好，抵价以门店检测为准。"),
            new FaqItem("发货时效", "现货商品 48 小时内发货，预售商品以页面标注为准。")
    );

    @Tool(description = "根据关键词查询常见问题解答")
    public List<FaqItem> queryFaq(@ToolParam(description = "查询关键词") String keyword) {
        return FAQS.stream()
                .filter(f -> f.question().contains(keyword)
                        || f.answer().contains(keyword))
                .toList();
    }
}
