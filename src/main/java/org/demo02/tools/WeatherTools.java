package org.demo02.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class WeatherTools {
    public record Weather(String city, double temp, String condition) {
    }

    @Tool(description = "查询指定城市的天气")
    public Weather getWeather(@ToolParam(description = "城市名称，如：北京、上海") String city) {
        return new Weather(city, 25.0, "晴天");
    }
}
