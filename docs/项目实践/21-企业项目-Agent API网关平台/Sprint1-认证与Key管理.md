# Sprint 1 · 认证与 API Key 管理

> P21 AgentGateway · 第 1 周

---

## 目标

实现 API Key 的创建、认证、权限控制完整闭环。

## 任务清单

- [ ] API Key 创建接口（生成 Key + 绑定权限）
- [ ] 认证过滤器（Bearer Token 解析 + 校验）
- [ ] 权限范围检查（Scope 控制）
- [ ] IP 白名单
- [ ] Key 吊销接口
- [ ] Key 用量查询

## 核心代码

### API Key 实体

```java
@Entity
public class ApiKey {
    @Id private String keyId;
    private String keyHash;        // BCrypt 哈希，不存明文
    private String tenantId;
    private boolean active;
    @ElementCollection private Set<String> scopes;
    @ElementCollection private Set<String> ipWhitelist;
    private long monthlyTokenBudget;
    private Instant createdAt;
    private Instant expiresAt;
}
```

### 创建 Key

```java
@PostMapping("/api/admin/keys")
public Map<String, Object> createKey(@RequestBody CreateKeyRequest req) {
    String rawKey = "sk-" + UUID.randomUUID().toString().replace("-", "");
    String keyId = "key_" + System.currentTimeMillis();
    String hash = BCrypt.hashpw(rawKey, BCrypt.gensalt());

    apiKeyRepo.save(new ApiKey(keyId, hash, req.tenantId(),
        true, req.scopes(), req.ipWhitelist(), req.budget(), Instant.now(), null));

    return Map.of("keyId", keyId, "apiKey", rawKey, "note", "请妥善保存，仅显示一次");
}
```

### 认证过滤器

```java
@Component
public class AuthFilter implements GlobalFilter, Ordered {
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String token = extractToken(exchange);
        if (token == null) return reject(exchange, 401, "missing_token");

        ApiKey key = apiKeyRepo.findByHash(BCrypt.hashpw(token, salt));
        if (key == null || !key.active) return reject(exchange, 401, "invalid_key");

        // Scope 检查
        if (!hasScope(key, exchange.getRequest().getPath().value()))
            return reject(exchange, 403, "no_permission");

        // IP 白名单
        if (!key.ipWhitelist.isEmpty()) {
            String ip = exchange.getRequest().getRemoteAddress().getAddress().getHostAddress();
            if (!key.ipWhitelist.contains(ip)) return reject(exchange, 403, "ip_blocked");
        }

        exchange.getRequest().mutate().header("X-Tenant-Id", key.tenantId).build();
        return chain.filter(exchange);
    }
    @Override public int getOrder() { return -100; }
}
```

## 验收

- [ ] 创建 Key → 拿到 `sk-xxx` 格式的 Key
- [ ] 用正确 Key 访问 → 通过
- [ ] 用错误 Key 访问 → 401
- [ ] 无权限路径访问 → 403
- [ ] IP 不在白名单 → 403
- [ ] 吊销 Key 后访问 → 401
