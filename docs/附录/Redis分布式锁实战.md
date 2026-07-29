# Redis 分布式锁实战（从 SETNX 到 Redisson）

> **配套文档**：[35-管数分离实战](../tutorials/spring-ai-2.0/35-管数分离实战-从Sinks到Kafka演进.md) 第 7 章用 `SETNX` 实现了"单一写者锁"（保证多实例集群里只有一个实例真正去跑生成器）。但 SETNX 是分布式锁的**最朴素版本**，生产级还有一堆坑要填。本篇把分布式锁从最简单讲到最严谨——为什么 SETNX 不够、Lua 脚本怎么救、Redisson 看门狗、fencing token 终极方案。
>
> **难度假设**：你写过单机锁（`synchronized`/`ReentrantLock`），但没做过分布式锁。

---

## 第 1 章：为什么需要分布式锁

### 1.1 单机锁为什么不够

单机锁（`synchronized`）只管**一个 JVM 内**的线程互斥。一旦你的服务部署成**多个实例**（多台机器/多个进程），单机锁就失效了——每个实例有自己的 JVM、自己的锁，互相看不见。

```
实例A 的 synchronized        实例B 的 synchronized
  └─ 锁的是 A 的 JVM            └─ 锁的是 B 的 JVM
      两者互不感知！A 和 B 可以同时进入"临界区"
```

**管数分离文档的真实场景**：部署了两台实例，用户手机和 iPad 同时触发生成，两台实例各自跑生成器——重复触发、烧两次资源、结果分叉。需要一个**全集群只有一个实例能拿到**的锁。

### 1.2 分布式锁的本质

把锁放在**所有实例都能看到的第三方**（通常是 Redis）上：

```
实例A ──┐                    ┌── "拿到了！" → 执行
实例B ──┼──→ Redis（锁）──→ ┤
实例C ──┘                    └── "没拿到"  → 不执行/重试
```

谁的 `SETNX` 成功，谁就持有锁。Redis 是唯一权威。

---

## 第 2 章：SETNX——最朴素的实现

### 2.1 命令

```bash
# SET if Not eXists，带过期时间
SET lock:order-123 "instance-A" NX EX 30
# NX：不存在才设置（抢锁）
# EX 30：30 秒过期（防持有者崩溃后锁永远不释放）
```

- 返回 OK：抢到了。
- 返回 nil：已被别人占用。

### 2.2 Spring Boot 实现

```java
public Mono<Boolean> tryLock(String lockKey, String owner, Duration ttl) {
    return redis.opsForValue().setIfAbsent(lockKey, owner, ttl);  // SET NX EX
}

public Mono<Boolean> unlock(String lockKey, String owner) {
    return redis.opsForValue().get(lockKey)
            .flatMap(val -> {
                if (owner.equals(val)) {
                    return redis.delete(lockKey).map(c -> c > 0);  // 只有持有者能释放
                }
                return Mono.just(false);
            })
            .defaultIfEmpty(false);
}
```

### 2.3 为什么 owner 不能省

释放锁时要**先 GET 检查是不是自己的，再 DEL**。为什么？看这个时序：

```
T1: 实例A 抢到锁（过期 30s）
T2: 实例A 业务卡住了，超过 30s，锁自动过期
T3: 实例B 抢到锁（新的 30s）
T4: 实例A 终于缓过来，执行 DEL lock  —— 把实例B 的锁删了！
T5: 实例C 抢到锁，现在 A 和 C 都以为自己是持有者 → 灾难
```

**所以释放前必须确认"这把锁确实是我的"**。owner（通常用实例ID/UUID）就是干这个的。

---

## 第 3 章：SETNX 的致命缺陷——释放不是原子的

### 3.1 GET-then-DEL 的竞态

第 2.2 节的 `unlock` 是 **GET → 判断 → DEL** 三步，**不是原子的**：

```
实例A: GET lock → "instance-A" ✅ 是我的
                                    ← 此时锁恰好过期，实例B SETNX 成功
实例A: DEL lock → 把实例B 的锁删了！
```

虽然概率低，但在高并发下真实存在。

### 3.2 用 Lua 脚本让"判断+删除"原子化

Redis 执行 Lua 脚本是**单线程、不可打断**的——天然原子。

```java
private static final RedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
        "if redis.call('get', KEYS[1]) == ARGV[1] then " +
        "  return redis.call('del', KEYS[1]) " +
        "else " +
        "  return 0 " +
        "end", Long.class);

public Mono<Boolean> unlockSafe(String lockKey, String owner) {
    return redis.execute(UNLOCK_SCRIPT, List.of(lockKey), List.of(owner))
            .next()
            .map(result -> result != null && result > 0);
}
```

> **Lua 脚本要点**：`KEYS[1]` 是操作的 key，`ARGV[1]` 是参数。整个脚本 Redis 一次性执行完，中间不会被其他命令插入。**这是释放分布式锁的标准做法。**

---

## 第 4 章：过期时间的两难——锁过期 vs 业务没跑完

### 4.1 困境

- **过期太短**：业务还没跑完，锁就过期了，别的实例趁机拿到锁 → 两个实例同时跑（违反互斥）。
- **过期太长**：持有者崩溃了，锁迟迟不释放，别的实例等很久。

你怎么定这个时间都尴尬——你**事先不知道业务要跑多久**。

### 4.2 解决：看门狗（Watchdog）自动续期

**Redisson**（Redis 的 Java 客户端，比 Lettuce/Jedis 更适合做分布式锁）实现了**看门狗**：

- 抢锁时设一个较短默认过期（如 30s）。
- 起一个后台线程（看门狗），每隔 1/3 过期时间（10s）检查：**如果锁还被我持有，就续期到 30s**。
- 业务跑完主动释放，或实例崩溃后看门狗也死了，锁 30s 后自动过期。

这样业务跑多久都不怕过期，崩溃了也能自动释放。

### 4.3 Redisson 用法

```java
// 依赖：org.redisson:redisson-spring-boot-starter

@Configuration
public class RedissonConfig {
    @Bean(destroyMethod = "shutdown")
    public RedissonClient redisson() {
        Config config = new Config();
        config.useSingleServer().setAddress("redis://localhost:6379");
        return Redisson.create(config);
    }
}

@Service
public class LockService {
    private final RedissonClient redisson;

    public void doWithLock(String key, Runnable task) {
        RLock lock = redisson.getLock("lock:" + key);
        lock.lock();          // 看门狗自动续期（默认 30s，每 10s 续）
        try {
            task.run();       // 业务逻辑，跑多久都不怕
        } finally {
            lock.unlock();    // 只有持有者能解（内部 Lua 脚本）
        }
    }
}
```

> **Redisson 的 `lock.lock()` 默认带看门狗**；`lock.lock(10, TimeUnit.SECONDS)` 指定固定过期则**不带**看门狗（锁 10s 后必过期，不管业务跑没跑完）。需要看门狗就别传过期时间。

---

## 第 5 章：终极缺陷——fencing token（管数分离文档第 7 章的进阶方向）

### 5.1 即使用 Redisson，还有一个理论缺陷

Martin Kleppmann（《DDIA》作者）有个著名批评：**即使锁本身完美（Redisson 看门狗 + Lua），仍有一个无法用锁自身解决的问题**——

```
T1: 实例A 抢到锁，准备去写数据库
T2: 实例A 发生长时间的 GC 停顿（STW），整个进程"冻结"
T3: 锁过期（看门狗也被冻结，没续期）
T4: 实例B 抢到锁，开始写数据库
T5: 实例A GC 结束"醒来"，继续写数据库 —— A 和 B 同时写！
```

**A 拿着一把"过期"的锁，自己不知道**。这不是锁实现的问题，是**进程暂停（GC/时钟漂移）**导致的，任何基于"持有者自觉"的锁都有这个洞。

### 5.2 fencing token 解法

让锁**带一个单调递增的 token**：

```
T1: 实例A 抢到锁，token=100
T2: 实例A GC 停顿
T3: 锁过期，实例B 抢到锁，token=101
T4: 实例B 写数据库，附上 token=101，数据库记录"已见最大 token=101"
T5: 实例A 醒来，写数据库附上 token=100
T6: 数据库检查：100 < 101（当前记录），拒绝写入！
```

**核心**：存储层（数据库/Redis）拒绝"旧 token"的写。这样即使锁持有者拿着过期的锁，存储层也能挡住它的陈旧写入。

### 5.3 Redisson 支持吗

**诚实说：Redisson 的 `RLock` 并不直接提供开箱即用的 fencing token。** 网上常见的 `((RedissonLock) lock).getEntry().getThreadId()` 拿到的是**线程 ID**，那是用来实现"可重入"的，**根本不是 fencing token**，别被误导。

真正的 fencing token 需要你自己做两件事：

1. **用 Redis 的 `INCR` 生成单调递增 token**（每次抢锁成功就 `INCR` 一次，拿到一个全局递增的号）。
2. **存储层记录并拒绝旧 token**（这是关键，锁自己防不住，要靠存储）。

```java
// ① 抢锁成功后，生成一个全局递增的 token
//    lockKey 抢到后，立刻对 tokenKey 做一次 INCR
Long token = redis.opsForValue().increment("token:" + resourceKey);

// ② 之后每次写存储，都带上这个 token，存储层校验
//    以数据库为例（用一行版本号/锁版本字段）：
//    UPDATE account SET balance=?, lock_token=?
//    WHERE id=? AND lock_token < ?   ← 拒绝比当前记录更旧的 token
```

这样即使实例 A 拿着过期的锁"醒来"继续写，数据库那条 `lock_token < ?` 会让它的写入被拒（因为 B 已经用更大的 token 写过了）。

> **诚实说明**：fencing token 需要存储层配合（每个写操作都要带 token 校验），实现成本高。多数业务用 SETNX + Lua 或 Redisson 看门狗就足够（互斥性在 99.9% 场景成立）。**只有对正确性要求极高（如金融扣款、库存）才上 fencing token。** 管数分离文档把 fencing 列为"进阶扩展点"而非默认方案，是务实的。

---

## 第 6 章：三种方案对比与选型

| 方案 | 复杂度 | 互斥性 | 适用 |
|------|--------|--------|------|
| **SETNX + EX** | 极低 | 有释放竞态、有过期困境 | 演示/学习/低频 |
| **SETNX + Lua 释放** | 低 | 释放原子了，仍有过期困境 | 中小项目 |
| **Redisson 看门狗** | 中（引依赖） | 自动续期，过期困境解决 | **生产首选** |
| **Redisson + fencing** | 高 | 连 GC 停顿都能挡 | 强正确性场景 |
| **Redlock** | 高 | 跨多 Redis 抗单点 | 有争议（Kleppmann 批评），慎用 |
| **ZooKeeper/etcd 锁** | 高 | 基于 lease+session，最严谨 | 已有 ZK 基础设施时 |

**选型建议**：

- **绝大多数业务**：Redisson 看门狗。简单、成熟、够用。
- **强一致（金融/库存）**：Redisson + fencing，或上 ZooKeeper。
- **学习/演示**：SETNX 看懂原理即可。

---

## 第 7 章：常见坑

### 坑 1：释放锁没检查 owner，删了别人的锁

见第 2.3 节。**解决**：释放前用 Lua 脚本判断 owner。

### 坑 2：锁过期了，持有者还在跑

见第 4 节。**解决**：用 Redisson 看门狗自动续期，或业务里带 fencing token。

### 坑 3：忘记设过期时间

锁永远不释放（持有者崩溃后）。**解决**：`SET NX` 一定带 `EX`，或用 Redisson。

### 坑 4：锁的 key 设计不当

比如用 `lock:userId` 锁整个用户，粒度太粗。**解决**：锁粒度尽量细，按最小临界区设计 key（如 `lock:order:orderId`）。

### 坑 5：可重入没考虑

同一线程递归调用拿不到自己的锁。**解决**：Redisson 的 `RLock` 默认可重入；自己实现要记录持有计数。

### 坑 6：误以为锁能保证业务一定成功

锁只保证**互斥**，不保证业务执行成功。拿锁后业务抛异常，要 try-finally 确保释放。**解决**：永远 `try { 业务 } finally { unlock }`。

---

## 总结

- **SETNX + EX**：最朴素的分布式锁，够学习。
- **Lua 释放**：解决"删除不是原子"的竞态。
- **Redisson 看门狗**：解决"业务没跑完锁过期"的困境——**生产首选**。
- **fencing token**：解决 GC 停顿导致的陈旧写入——强一致场景才需要。
- **核心认知**：分布式锁保证的是"互斥"，不是"业务正确"或"不会重复执行"。后者靠业务幂等 + 存储层防护。

回头看 [管数分离文档第 7 章](../tutorials/spring-ai-2.0/35-管数分离实战-从Sinks到Kafka演进.md) 的 SETNX，你就明白它为什么诚实标注"缺陷是锁过期窗口、无 fencing"，以及第 7 章把 fencing 列为进阶方向的原因了。
