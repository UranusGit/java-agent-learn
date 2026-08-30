package demo.demo01.config;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.advisor.observation.AdvisorObservationContext;
import org.springframework.ai.chat.client.observation.ChatClientObservationContext;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.tool.observation.ToolCallingObservationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.ObjectUtils;

import java.util.concurrent.atomic.AtomicInteger;

@Configuration
@Slf4j
public class ObservationContextHandler {
    private static final AtomicInteger TOOL_COUNT = new AtomicInteger();

    @Bean
    public ObservationHandler<ToolCallingObservationContext> toolCallingObservationContextObservationContextHandler() {
        return new ObservationHandler<>() {
            @Override
            public boolean supportsContext(Observation.Context context) {
                return context instanceof ToolCallingObservationContext;
            }

            @Override
            public void onStart(ToolCallingObservationContext context) {
                log.info("开始工具调用，工具名称：{}，工具参数：{}", context.getToolDefinition().name(), context.getToolCallArguments());
            }

            @Override
            public void onStop(ToolCallingObservationContext context) {
                log.info("工具调用结束，工具名称：{}，第 {} 次工具调用，工具参数：{}，工具调用结果：{}", context.getToolDefinition().name(), TOOL_COUNT.incrementAndGet(), context.getToolCallArguments(), context.getToolCallResult());
            }

            @Override
            public void onEvent(Observation.Event event, ToolCallingObservationContext context) {
                log.info("工具调用事件，工具名称：{}，event：{}", context.getToolDefinition().name(), context.getAllKeyValues());
            }
        };
    }

    @Bean
    public ObservationHandler<ChatModelObservationContext> chatModelObservationContextObservationHandler() {
        return new ObservationHandler<>() {
            @Override
            public boolean supportsContext(Observation.Context context) {
                return context instanceof ChatModelObservationContext;
            }

            @Override
            public void onStart(ChatModelObservationContext context) {
                log.info("开始调用：{}", context.getRequest().getUserMessage().getText());
            }

            /**
             *
             * 特别注意：这个地方只打印工具调用的内容，如果想看分析结果，需要在ChatClient中增加默认的system说明增加工具调用的原因
             * @param context an {@link Observation.Context}
             */
            @Override
            public void onStop(ChatModelObservationContext context) {
                if (!ObjectUtils.isEmpty(context.getResponse()) && !ObjectUtils.isEmpty(context.getResponse().getResult()) && !ObjectUtils.isEmpty(context.getResponse().getResult().getOutput())) {
                    log.info("模型响应：{}", context.getResponse().getResult().getOutput().getText());
                }
            }
        };
    }

    @Bean
    public ObservationHandler<ChatClientObservationContext> chatClientObservationContextObservationHandler() {
        return new ObservationHandler<>() {
            @Override
            public boolean supportsContext(Observation.Context context) {
                return context instanceof ChatClientObservationContext;
            }

            @Override
            public void onStop(ChatClientObservationContext context) {
                log.info("本次问答结束，消息条数：{}，流式：{}，生效 Advisor 数：{}",
                        context.getRequest().prompt().getInstructions().size(),
                        context.isStream(), context.getAdvisors().size());
            }
        };
    }

    @Bean
    public ObservationHandler<AdvisorObservationContext> advisorObservationContextObservationHandler() {
        return new ObservationHandler<>() {
            @Override
            public boolean supportsContext(Observation.Context context) {
                return context instanceof AdvisorObservationContext;
            }

            @Override
            public void onStop(AdvisorObservationContext context) {
                log.info("Advisor 执行结束，名称：{}，order：{}", context.getAdvisorName(), context.getOrder());
            }
        };
    }
}
