package org.demo05.config;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncEdgeAction;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.alibaba.cloud.ai.graph.action.EdgeAction;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import lombok.extern.slf4j.Slf4j;
import org.demo05.advisor.SimpleLogAdvisor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

@Slf4j
@Configuration
public class ChatConfig {
    @Bean
    public ChatClient chatClient(ChatClient.Builder builder, ChatMemory memory) {
        return builder
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(memory).build(),
                        new SimpleLogAdvisor())
                .build();
    }

    @Bean
    public CompiledGraph routerGraph(ChatClient client) throws GraphStateException {
        // 1. 定义 State：声明会被节点读写的 key，每个 key 绑定一个合并策略
        KeyStrategyFactory stateFactory = KeyStrategy.builder()
                .addStrategy("user_query", KeyStrategy.REPLACE)
                .addStrategy("route", KeyStrategy.REPLACE)
                .addStrategy("answer", KeyStrategy.REPLACE)
                .build();

        // 2. 定义节点：路由判断（用 LLM 输出一个词）
        AsyncNodeAction routerNode = AsyncNodeAction.node_async(state -> {
            String query = state.value("user_query")
                    .orElse("")
                    .toString();

            String route = client.prompt()
                    .system("""
                            判断用户问题属于哪个域。只输出一个词：
                            - billing（账单/支付/退款）
                            - tech（技术故障）
                            - sales（产品咨询/购买）
                            """)
                    .user(query)
                    .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, state.value("sessionId").orElse("default").toString()))
                    .call()
                    .content();
            return Map.of("route", route);
        });

        // 3. 定义三个 Worker 节点
        AsyncNodeAction billingNode = AsyncNodeAction.node_async(state -> {
            String answer = client.prompt()
                    .system("你是客服，处理账单问题")
                    .user(state.value("user_query").orElse("").toString())
                    .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, state.value("sessionId").orElse("default").toString()))
                    .call()
                    .content();
            return Map.of("answer", answer);
        });

        AsyncNodeAction techNode = AsyncNodeAction.node_async(state -> {
            String answer = client.prompt()
                    .system("你是技术支持，处理故障问题")
                    .user(state.value("user_query").orElse("").toString())
                    .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, state.value("sessionId").orElse("default").toString()))
                    .call()
                    .content();
            return Map.of("answer", answer);
        });

        AsyncNodeAction salesNode = AsyncNodeAction.node_async(state -> {
            String answer = client.prompt()
                    .system("你是销售顾问，介绍产品")
                    .user(state.value("user_query").orElse("").toString())
                    .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, state.value("sessionId").orElse("default").toString()))
                    .call()
                    .content();
            return Map.of("answer", answer);
        });

        // 4. 条件边：根据 state["route"] 决定下一节点
        //    第二个参数返回的字符串必须是第三个参数 Map 的 key（或 END）
        //    注意：addConditionalEdges 只接受 AsyncEdgeAction，
        //    同步 EdgeAction 必须用 AsyncEdgeAction.edge_async(...) 包装
        EdgeAction guard = state -> {
            String route = state.value("route").orElse("tech").toString();

            return switch (route) {
                case "billing" -> "billing";
                case "sales" -> "sales";
                default -> "tech";
            };
        };

        // 5. 组装图
        StateGraph graph = new StateGraph("customer-service-router", stateFactory);
        graph.addNode("router", routerNode);
        graph.addNode("billing", billingNode);
        graph.addNode("sales", salesNode);
        graph.addNode("tech", techNode);

        graph.addEdge(StateGraph.START, "router");
        graph.addConditionalEdges("router", AsyncEdgeAction.edge_async(guard), Map.of(
                "billing", "billing",
                "tech", "tech",
                "sales", "sales"
        ));
        graph.addEdge("billing", StateGraph.END);
        graph.addEdge("tech", StateGraph.END);
        graph.addEdge("sales", StateGraph.END);


        // 任意位置注入 CompiledGraph，输出 Mermaid 文本
        String mermaid = graph.getGraph(
                com.alibaba.cloud.ai.graph.GraphRepresentation.Type.MERMAID
        ).content();

        log.info("graph mermain is \n{}", mermaid);

        // 6.编译成可执行图
        return graph.compile();
    }

}
