package org.demo01.util;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

public class CalculatorTools {
    @Tool("两个数相加")
    public int add(@P("第一个数") int a, @P("第二个数") int b) {
        System.out.println("使用了我的本地方法，进行加法运算");
        return a + b;
    }

    @Tool("两个数相乘")
    public int multiply(@P("第一个数") int a, @P("第二个数") int b) {
        System.out.println("使用了我的本地方法，进行乘法运算");
        return a * b;
    }
}
