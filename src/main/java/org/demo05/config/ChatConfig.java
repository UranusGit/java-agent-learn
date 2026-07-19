package org.demo05.config;

import com.alibaba.cloud.ai.graph.*;
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
import tools.jackson.databind.ObjectMapper;

import java.util.List;
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

    @Bean("RouterGraphConfig")
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

    @Bean("ReportPipelineGraph")
    public CompiledGraph reportGraph(ChatClient client) throws GraphStateException {
        // State：分清哪些 key 是"输入"、哪些是"中间结果"、哪些是"输出"
        KeyStrategyFactory stateFactory = KeyStrategy.builder()
                // 输入
                .addStrategy("user_query", KeyStrategy.REPLACE)

                // 中间结果
                .addStrategy("outline", KeyStrategy.REPLACE)
                .addStrategy("current_chapter", KeyStrategy.REPLACE)
                .addStrategy("chapter_contents", KeyStrategy.APPEND) // 用 APPEND 累计每章
                .addStrategy("review_pass", KeyStrategy.REPLACE)
                .addStrategy("iteration", KeyStrategy.REPLACE) // reviewer 失败次数

                // 输出
                .addStrategy("final_report", KeyStrategy.REPLACE)
                .build();

        // 节点1 outline 生成大纲
        AsyncNodeAction outliner = AsyncNodeAction.node_async(state -> {
            String outline = client.prompt()
                    .system("""
                            你是技术大纲设计专家。把主题拆成 3-5 个章节，
                            输出 JSON 数组，每个元素形如 {"chapter": "标题", "key_points": ["..."]}
                            只输出 JSON，不要前后缀。
                            """)
                    .user(state.value("user_query").orElse("").toString())
                    .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, state.value("sessionId").orElse("default").toString()))
                    .call()
                    .content();
            return Map.of("outline", outline, "current_chapter", 0, "iteration", 0);
        });

        // 节点2 writer 写当前节点
        AsyncNodeAction writer = AsyncNodeAction.node_async(state -> {
            String userQuery = state.value("user_query").orElse("").toString();
            String outline = state.value("outline").orElse("[]").toString();
            int currentChapter = (int) state.value("current_chapter").orElse(0);
            int totalChapters = countChapters(outline);  // 解析 outline JSON，省略实现

            if (currentChapter >= totalChapters) {
                return Map.of("all_chapters_done", true);
            }

            String content = client.prompt()
                    .system("""
                            你是技术作者，写一章节内容。
                            要求：800-1500 字，有代码示例，结构清晰。
                            """)
                    .user("主题：%s\n章节序号：%d\n大纲：%s".formatted(userQuery, currentChapter + 1, outline))
                    .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, state.value("sessionId").orElse("default").toString()))
                    .call()
                    .content();

            return Map.of(
                    "current_chapter", currentChapter + 1,
                    "chapter_contents", content,   // APPEND 策略会自动追加到列表
                    "all_chapters_done", false
            );
        });

        // 节点3 reviewer 审稿
        AsyncNodeAction reviewer = AsyncNodeAction.node_async(state -> {
            String contents = String.join("\n---\n", state.value("chapter_contents").orElseThrow().toString());
            int iteration = (int) state.value("iteration").orElse(0);
            String review = client.prompt()
                    .system("""
                            你是技术审稿人。检查报告：技术准确性、章节连贯性、代码示例正确性。
                            输出 JSON：{"pass": true/false}
                            只输出 JSON。
                            """)
                    .user(contents)
                    .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, state.value("sessionId").orElse("default").toString()))
                    .call()
                    .content();
            boolean pass = review.contains("\"pass\": true");
            return Map.of(
                    "review_pass", pass,
                    "iteration", iteration + 1
            );
        });

        // 节点 4：formatter — Markdown 输出
        AsyncNodeAction formatter = AsyncNodeAction.node_async(state -> {
            String contents = String.join("\n---\n", state.value("chapter_contents").orElseThrow().toString());
            String formatted = client.prompt()
                    .system("把内容整理为 Markdown，添加目录、标题层级、代码块标记")
                    .user(contents)
                    .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, state.value("sessionId").orElse("default").toString()))
                    .call()
                    .content();
            return Map.of("final_report", formatted);
        });


        // 条件边 1：writer 之后，看是否所有章节写完
        EdgeAction afterWriter = state -> {
            boolean done = (boolean) state.value("all_chapters_done").orElse(false);
            return done ? "reviewer" : "writer";
        };

        // 条件边 2：reviewer 之后，看是否通过；连续失败 3 次也强制通过
        EdgeAction afterReview = state -> {
            boolean pass = (boolean) state.value("review_pass").orElse(false);
            int iteration = (int) state.value("iteration").orElse(0);
            if (pass || iteration >= 3) {
                return "formatter";
            }
            // 回 writer 重写：重置 current_chapter + 清空 chapter_contents
            return "writer_reset";
        };

        // 兜底节点：reset 状态后回到 writer
        AsyncNodeAction writerReset = AsyncNodeAction.node_async(state -> {
            // 这里需要清空 chapter_contents（APPEND 不能直接清，要靠 MARK_FOR_REMOVAL）
            return Map.of(
                    "current_chapter", 0,
                    "chapter_contents", com.alibaba.cloud.ai.graph.OverAllState.MARK_FOR_REMOVAL
            );
        });

        // 组装图
        StateGraph graph = new StateGraph("report-pipeline", stateFactory);
        graph.addNode("outliner", outliner);
        graph.addNode("writer", writer);
        graph.addNode("writer_reset", writerReset);
        graph.addNode("reviewer", reviewer);
        graph.addNode("formatter", formatter);

        graph.addEdge(StateGraph.START, "outliner");
        graph.addEdge("outliner", "writer");
        graph.addConditionalEdges("writer", AsyncEdgeAction.edge_async(afterWriter), Map.of(
                "writer", "writer",
                "reviewer", "reviewer"
        ));
        graph.addEdge("writer_reset", "writer");
        graph.addConditionalEdges("reviewer", AsyncEdgeAction.edge_async(afterReview), Map.of(
                "formatter", "formatter",
                "writer_reset", "writer_reset"
        ));
        graph.addEdge("formatter", StateGraph.END);

        GraphLifecycleListener listener = new GraphLifecycleListener() {
            @Override
            public void onStart(String nodeId, Map<String, Object> state, RunnableConfig config) {
                log.info("[{}] node start, state keys={}, runnable config is {}", nodeId, state.keySet(), config);
            }

            @Override
            public void onComplete(String nodeId, Map<String, Object> state, RunnableConfig config) {
                log.info("[{}] node end, result keys={}, runnable config is {}", nodeId, state.keySet(), config);
            }
        };

        // recursionLimit 是图执行总跳转次数（每经过一个节点计数 +1），不是 reviewer 重试次数。
        // 5 章 writer 循环 + reviewer + 可能重写 + formatter，至少 20+ 跳。
        // 默认值偏小（alibaba-graph 默认 5），按预估章节量 ×3 + 10 留足余量。
        CompiledGraph compiled = graph.compile(
                CompileConfig.builder()
                        .withLifecycleListener(listener)
                        .recursionLimit(50)
                        .build());
        return compiled;
    }

    private int countChapters(String outline) {
        if (outline == null || outline.isBlank()) {
            return 0;
        }
        // LLM 输出可能有前后缀文字，先抽第一个 [...] 子串再 parse
        int start = outline.indexOf('[');
        int end = outline.lastIndexOf(']');
        if (start < 0 || end <= start) {
            log.warn("outline 不是合法 JSON 数组: {}", outline);
            return 0;
        }
        String json = outline.substring(start, end + 1);
        try {
            List<?> list = new ObjectMapper().readValue(json, List.class);
            return list.size();
        } catch (Exception e) {
            log.warn("parse outline JSON failed: {}", e.getMessage());
            return 0;
        }
    }

}
