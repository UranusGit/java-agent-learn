# Sprint 3 · SSE 流式代理与 Token 计量

> P21 AgentGateway · 第 3 周

---

## 目标

实现 SSE 流式透传和实时 Token 计量。

## 任务清单

- [ ] SSE 流式代理（不缓冲，逐 event 转发）
- [ ] 实时 Token 解析（从 SSE event 提取 usage）
- [ ] Token 用量异步写入（不阻塞 SSE 流）
- [ ] 用量看板 API（按 Key / 按租户 / 按模型）

## 核心代码

```java
public class SseStreamProxyFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!isSseRequest(exchange)) return chain.filter(exchange);

        String tenantId = exchange.getRequest().getHeaders().getFirst("X-Tenant-Id");
        String backendUrl = resolveBackend(exchange);

        // 使用 BodyInserter 流式转发
        return webClient.post()
                .uri(backendUrl)
                .headers(h -> h.addAll(filterHeaders(exchange.getRequest().getHeaders())))
                .body(exchange.getRequest().getBody(), DataBuffer.class)
                .retrieve()
                .bodyToFlux(String.class)
                .timeout(Duration.ofMinutes(5))
                .doOnNext(event -> {
                    // 解析 Token 用量
                    parseAndRecordUsage(event, tenantId);
                })
                .map(this::toSseEvent)
                .doFinally(signal -> auditLog(exchange, signal))
                .flatMap(event -> writeSse(exchange, event))
                .then()
                .doOnCancel(() -> log.info("客户端取消 SSE 连接"));
    }

    private void parseAndRecordUsage(String event, String tenantId) {
        // 最后一个 SSE event 通常包含 usage
        if (event.contains("\"usage\"")) {
            try {
                JsonNode usage = objectMapper.readTree(event).path("usage");
                int prompt = usage.path("prompt_tokens").asInt();
                int completion = usage.path("completion_tokens").asInt();
                // 异步写入
                meter.record(tenantId, prompt, completion);
            } catch (Exception ignored) { }
        }
    }
}
```

## 用量看板

```java
@GetMapping("/api/admin/usage/{keyId}")
public UsageReport usage(@PathVariable String keyId,
                         @RequestParam(defaultValue = "24h") String window) {
    return meter.report(keyId, window);
}

@GetMapping("/api/admin/usage/summary")
public Map<String, Object> summary(@RequestParam(defaultValue = "today") String range) {
    return Map.of(
        "totalRequests", meter.countRequests(range),
        "totalTokens", meter.sumTokens(range),
        "totalCost", meter.sumCost(range),
        "byModel", meter.breakdownByModel(range)
    );
}
```

## 验收

- [ ] SSE 流式响应逐 token 到达客户端（不缓冲）
- [ ] Token 用量从 SSE event 准确解析
- [ ] 用量看板展示 24h / 7d / 30d 数据
- [ ] 客户端取消连接时后端也取消（背压传播）
