# 10 · Agent Saga 补偿事务（补充篇）

> 阶段：4 生产化 · 难度：⭐⭐⭐⭐⭐ · 预计：2 天
> 前置：[09 Agent 配置中心](09-Agent配置中心.md)
> 产出：掌握 Agent 多步骤操作的补偿事务设计

---

## 为什么 Agent 需要 Saga

Agent 经常执行跨多个系统的多步骤操作：

```
退款流程：
  Step 1: 查询订单 → 成功
  Step 2: 验证退款条件 → 成功
  Step 3: 创建退款记录 → 成功
  Step 4: 调用支付平台退款 API → ❌ 失败！
  Step 5: 发送通知 → 未执行

问题：Step 3 已创建了退款记录，但实际退款没成功。
      数据不一致——用户看到了退款记录但钱没到账。
```

**Saga 模式**：每一步都有对应的**补偿操作**，失败时逆序执行补偿。

---

## Saga 实现

### 注解驱动的 Saga

```java
package com.example.saga;

import java.lang.annotation.*;

/**
 * 标记一个方法为 Saga 步骤
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface SagaStep {
    String name();
    String compensate();  // 补偿方法名
}

/**
 * 标记补偿方法
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Compensate {
    String forStep();  // 对应的步骤名
}
```

### Saga 编排器

```java
package com.example.saga;

import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class SagaOrchestrator {

    /**
     * 执行 Saga
     */
    public <T> T execute(SagaDefinition<T> saga) {
        List<SagaStepResult> completedSteps = new ArrayList<>();

        try {
            for (SagaStep<T> step : saga.steps()) {
                Object result = step.action().apply(saga.context());
                completedSteps.add(new SagaStepResult(step.name(), result));
                saga.context().put(step.name() + "_result", result);
            }
            return saga.buildResult(saga.context());

        } catch (Exception e) {
            // 逆序补偿
            Collections.reverse(completedSteps);
            for (SagaStepResult stepResult : completedSteps) {
                try {
                    saga.findCompensate(stepResult.name())
                        .ifPresent(compensate -> compensate.accept(saga.context()));
                } catch (Exception补偿失败) {
                    // 补偿也失败了——记录到死信队列，人工处理
                    log.error("补偿失败：{}", stepResult.name(), 补偿失败);
                    deadLetterQueue.send(new CompensationFailed(
                        saga.id(), stepResult.name(), 补偿失败.getMessage()
                    ));
                }
            }
            throw new SagaExecutionException("Saga 失败并已补偿", e);
        }
    }

    public record SagaStepResult(String name, Object result) {}
    public record SagaDefinition<T>(
        String id,
        Map<String, Object> context,
        List<SagaStep<T>> steps,
        java.util.function.Function<Map<String, Object>, T> buildResult
    ) {
        Optional<java.util.function.Consumer<Map<String, Object>>> findCompensate(String stepName) {
            return steps.stream()
                .filter(s -> s.name().equals(stepName))
                .map(SagaStep::compensate)
                .findFirst();
        }
    }
    public record SagaStep<T>(
        String name,
        java.util.function.Function<Map<String, Object>, Object> action,
        java.util.function.Consumer<Map<String, Object>> compensate
    ) {}
}
```

### Agent 退款 Saga 示例

```java
@Service
public class RefundSagaService {

    private final SagaOrchestrator sagaOrchestrator;

    /**
     * 退款 Saga——5 个步骤，每个都有补偿
     */
    public RefundResult processRefund(String orderId, String reason) {
        Map<String, Object> context = new HashMap<>();
        context.put("orderId", orderId);
        context.put("reason", reason);

        var saga = new SagaOrchestrator.SagaDefinition<RefundResult>(
            UUID.randomUUID().toString(),
            context,
            List.of(
                // Step 1: 查询订单
                new SagaOrchestrator.SagaStep<RefundResult>(
                    "query_order",
                    ctx -> {
                        var order = orderService.findById((String) ctx.get("orderId"));
                        ctx.put("order", order);
                        return order;
                    },
                    ctx -> { /* 查询不需要补偿 */ }
                ),
                // Step 2: 创建退款记录
                new SagaOrchestrator.SagaStep<RefundResult>(
                    "create_refund",
                    ctx -> {
                        var order = (Order) ctx.get("order");
                        var refund = refundService.create(order, (String) ctx.get("reason"));
                        ctx.put("refundId", refund.id());
                        return refund;
                    },
                    ctx -> {
                        // 补偿：删除退款记录
                        refundService.delete((String) ctx.get("refundId"));
                    }
                ),
                // Step 3: 调用支付平台
                new SagaOrchestrator.SagaStep<RefundResult>(
                    "call_payment",
                    ctx -> {
                        var order = (Order) ctx.get("order");
                        paymentClient.refund(order.paymentId(), order.amount());
                        return null;
                    },
                    ctx -> {
                        // 补偿：撤销退款（支付平台可能需要手动处理）
                        var order = (Order) ctx.get("order");
                        paymentClient.cancelRefund(order.paymentId());
                    }
                ),
                // Step 4: 更新订单状态
                new SagaOrchestrator.SagaStep<RefundResult>(
                    "update_order",
                    ctx -> {
                        var order = (Order) ctx.get("order");
                        orderService.updateStatus(order.id(), "REFUNDED");
                        return null;
                    },
                    ctx -> {
                        // 补偿：恢复订单状态
                        var order = (Order) ctx.get("order");
                        orderService.updateStatus(order.id(), "PAID");
                    }
                ),
                // Step 5: 发送通知
                new SagaOrchestrator.SagaStep<RefundResult>(
                    "notify",
                    ctx -> {
                        var order = (Order) ctx.get("order");
                        notificationService.send(order.userId(), "您的退款已处理");
                        return null;
                    },
                    ctx -> { /* 通知不需要补偿 */ }
                )
            ),
            ctx -> new RefundResult(
                (String) ctx.get("orderId"),
                (String) ctx.get("refundId"),
                "SUCCESS"
            )
        );

        return sagaOrchestrator.execute(saga);
    }
}
```

### 补偿执行场景

```
正常执行：                失败场景（Step 3 失败）：
  query_order ✅           query_order ✅
  create_refund ✅         create_refund ✅
  call_payment ✅          call_payment ❌ → 触发补偿
  update_order ✅          ↓ 补偿（逆序）
  notify ✅                create_refund ← 删除退款记录 ✅
  ↓                        query_order ← 无补偿（查询操作）
  SUCCESS                  ↓
                           FAILED + COMPENSATED
```

---

## Agent 工具调用中的补偿

```java
/**
 * 工具调用包装器——为有副作用的工具添加补偿
 */
@Component
public class CompensableToolWrapper {

    private final SagaOrchestrator saga;

    /**
     * 包装一个有副作用的工具调用
     */
    public String executeWithCompensation(
            String toolName,
            java.util.function.Supplier<String> action,
            Runnable compensate) {

        try {
            return action.get();
        } catch (Exception e) {
            // 执行补偿
            try {
                compensate.run();
                return "⚠️ " + toolName + " 执行失败，已回滚。错误：" + e.getMessage();
            } catch (Exception ce) {
                // 补偿也失败
                return "🚫 " + toolName + " 执行失败且回滚失败！需要人工处理。"
                     + "原始错误：" + e.getMessage()
                     + "  回滚错误：" + ce.getMessage();
            }
        }
    }
}

// 使用示例
@Tool(description = "创建用户账户")
public String createUser(String name, String email) {
    return compensableToolWrapper.executeWithCompensation(
        "createUser",
        () -> {
            // 正向操作
            var user = userService.create(name, email);
            // 副作用操作
            emailService.sendWelcome(email);
            return "✅ 用户已创建：" + user.id();
        },
        () -> {
            // 补偿操作
            userService.deleteByEmail(email);
        }
    );
}
```

---

## 验收检查

- [ ] 理解为什么 Agent 多步骤操作需要 Saga
- [ ] 能实现 Saga 编排器
- [ ] 能为退款流程设计补偿链
- [ ] 理解补偿失败的处理策略（死信队列）
- [ ] 能用 CompensableToolWrapper 包装有副作用的工具

---

## 下一步

→ 进入 [阶段 5 架构师](../阶段5-架构师/01-多Agent编排.md)

---

## 延伸阅读：Saga 事务深化路线

| 方向 | 文档 | 内容 |
|------|------|------|
| 可靠性工程 | [02-Agent可靠性工程](02-Agent可靠性工程.md) | 幂等/重试/持久化执行 |
| 事件驱动 | [阶段5-13-EventDrivenAgent架构](../阶段5-架构师/13-EventDrivenAgent架构.md) | 事件驱动的 Saga |
| 工作流引擎 | [项目07-FlowEngine Sprint4](../项目实践/07-企业项目-工作流引擎/Sprint4-集成监控.md) | Saga 在工作流中的应用 |
| Durable Exec | [阶段6-01-DurableAgentExecution](../阶段6-前沿/01-DurableAgentExecution.md) | Temporal 持久化执行 |
| 工具错误处理 | [阶段3-07-工具错误处理规范](../阶段3-Agent工程化/07-工具错误处理规范.md) | 补偿的前置基础 |
