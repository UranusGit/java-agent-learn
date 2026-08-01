# Redis 缓存实战（缓存模式、穿透/击穿/雪崩、Spring Cache）

> **本篇定位**：Redis 最常用、最值得讲透的生产场景——**缓存**。同文件夹 [00-基础与SpringBoot使用](./00-Redis基础与SpringBoot使用.md) 讲的是"Redis 本身怎么用"，本篇讲"**怎么把缓存用对**"：缓存模式、三个经典坑（穿透/击穿/雪崩）、Spring Cache 抽象、手动缓存、和数据库的一致性。
>
> **难度假设**：你会用 Redis 基础（`SET/GET/TTL`、RedisTemplate/ReactiveRedisTemplate，见 [00](./00-Redis基础与SpringBoot使用.md)）。本篇不管你是 MVC 还是 WebFlux 都能读——需要区分的地方我会明说。
>
> **技术栈**：Spring Boot 4.x + Spring Data Redis 4.x（配置前缀 `spring.data.redis.*`，见 [00 第 4 章](./00-Redis基础与SpringBoot使用.md)）。
>
> **读完这篇之后**：你能回答"缓存到底怎么设计才对"——用什么模式、防哪三个坑、用注解还是手动、怎么跟数据库保持一致。并对应 [35-管数分离实战](../../tutorials/spring-ai-2.0/35-管数分离实战-从Sinks到Kafka演进.md) 第 6-7 章：它把 run 状态、幂等映射存进 Redis KV（带 1 天 TTL），又在第 7 章迁去 PostgreSQL——这就是"缓存该做什么、不该做什么"的真实样板。

---

## 第 1 章：缓存为什么快 + Cache-Aside（旁路）模式

### 1.1 为什么缓存快

一句话：**缓存把"数据库查询"换成了"内存读取"**，中间省掉了三层开销：

| 环节 | 数据库查询 | Redis 缓存读 |
|------|-----------|-------------|
| 存储介质 | 磁盘（机械/SSD） | **内存** |
| 网络往返 | 应用 → DB 服务器 | 应用 → Redis（通常同机/更近） |
| 计算 | 解析 SQL + 索引查找 + 回表 | `GET key` 哈希查找，O(1) |
| 典型耗时 | **毫秒级（5~50ms）** | **微秒级（0.1~1ms）** |

> **关键认知**：缓存的本质是**用空间（内存）换时间（查询耗时）**，同时把"每次都打数据库"变成"只打一次、后面读内存"。代价是：**内存贵**、**数据可能陈旧**（第 5 章）。所以缓存只适合"**读多写少、能容忍短暂过期**"的数据。

### 1.2 Cache-Aside（旁路缓存）——最常用的模式

**Cache-Aside（也叫旁路缓存/懒加载）是生产里 90% 的场景。** 核心思想：**缓存不做主，数据库才是"真源（source of truth）"，缓存只是"数据库的副本"。**

**读流程（旁路 = 绕过？不对，是"缓存做旁边"）：**

```
① 读请求
   │
   ▼
② 查缓存  GET user:1
   ├── 命中（缓存有）→ ③ 直接返回  ←—— 快，~0.1ms，到此结束
   │
   └── 未命中（缓存没有）
          │
          ▼
       ④ 查数据库  SELECT ... WHERE id=1   ←—— 慢，~10ms
          │
          ▼
       ⑤ 把结果写回缓存  SET user:1 ... EX 600
          │
          ▼
       ⑥ 返回给调用方
```

**写流程（先写数据库，再删缓存）：**

```
① 写请求
   │
   ▼
② 先写数据库  UPDATE user SET ... WHERE id=1   ←—— 数据库是"真源"，必须最先改对
   │
   ▼
③ 再删缓存  DEL user:1                         ←—— 不是"更新"缓存，而是"删掉"！
```

> **为什么写流程是"删缓存"而不是"更新缓存"？** 三个理由：
> 1. **更新成本高**：更新缓存要多写一次，还要保证"更新的值 = DB 最终值"。DB 可能有触发器、其他字段被连带改——你很难知道"最终值"是什么。删掉让下一次读去重建，最省心。
> 2. **更新有竞态**：并发下"DB 写一半、缓存更新一半"会互相覆盖，删掉就没这个问题。
> 3. **删缓存是最简单的失效方式**：缓存里没这个 key 了，下次读自然 miss → 查 DB → 回填新值。

**完整的时序图（读 + 写交错，也是第 5 章一致性的基础）：**

```
          缓存                       数据库
  读A     │                            │
  ──────► │ GET user:1 → miss         │
          │ ────────────────────────► │  SELECT 得 v1
          │ ◄──────────────────────── │
          │ SET user:1 = v1           │
          │                            │
  写B     │                            │
  ──────► │                           │ UPDATE → v2
          │ ◄──────────────────────── │
          │ DEL user:1               │
  读C     │                            │
  ──────► │ GET user:1 → miss         │  ← 缓存已被删，miss
          │ ────────────────────────► │  SELECT 得 v2（新值）
          │ ◄──────────────────────── │
          │ SET user:1 = v2           │
```

### 1.3 Read-Through / Write-Through / Write-Behind 简述

Cache-Aside 是"**应用自己管缓存**"。还有三种"**缓存中间件帮你管**"的模式，**了解一下即可**：

| 模式 | 读/写谁来管 | 一句话 |
|------|------------|--------|
| **Read-Through** | 读：应用只调缓存组件，组件 miss 时自动查 DB 回填 | 把 Cache-Aside 的"miss 查 DB 回填"逻辑收进组件 |
| **Write-Through** | 写：应用只写缓存组件，组件同步写 DB | 每次写都"缓存 + DB"一起改，缓存永远和 DB 一致（代价：写变慢） |
| **Write-Behind** | 写：应用只写缓存组件，组件**异步**批量写 DB | 写最快，但 DB 可能落后，宕机会丢数据 |

> **为什么生产大多数用 Cache-Aside？** Read/Write-Through 需要一套"抽象层"组件（如 Spring Cache 可以近似视为 Read/Write-Through 的中间层），Write-Behind 有丢数据风险。**Cache-Aside 简单、可控、失效时机明确，是默认答案。** 下面的内容全部基于 Cache-Aside。

### 1.4 验证

用 [00](./00-Redis基础与SpringBoot使用.md) 的 RedisTemplate 最小收发，手动走一遍 Cache-Aside：

```bash
# 模拟"写 DB 后删缓存"
redis-cli SET user:1 '{"name":"张三"}'
redis-cli DEL user:1
# 模拟"读：miss 查 DB 后回填"
redis-cli SET user:1 '{"name":"张三"}' EX 600
redis-cli GET user:1        # → {"name":"张三"}
redis-cli TTL user:1        # → 600 左右
```

> **验证**：`DEL` 后 `GET` 返回 `(nil)`（miss），回填后 `GET` 能读到值且带 TTL。这就是 Cache-Aside 读写的全部原语：`GET`/`SET EX`/`DEL`。

---

## 第 2 章：三个经典坑——穿透、击穿、雪崩

这三个词**面试必问、生产必踩**。记住一句话区分：

> **穿透**：缓存和数据库**都没有**这个数据，每次都打到 DB。
> **击穿**：缓存**本来有**，但**恰好过期的那一刻**，并发请求全打 DB。
> **雪崩**：大量 key **同一时间过期**，DB 被一波并发打垮。

### 2.1 穿透（Cache Penetration）——查不存在的 key

**现象**：恶意或误操作，疯狂查询一个**不存在的 id**（如 `user:-1`、随机 UUID）。缓存永远 miss（因为没人会回填不存在的值），**每个请求都穿过缓存直打数据库**。攻击者可以靠这个把 DB 打垮——这是缓存最大的安全坑。

```
攻击者: GET user:-1 → miss → SELECT ... WHERE id=-1 → 空
         GET user:-1 → miss → SELECT ... WHERE id=-1 → 空
         GET user:-1 → miss → SELECT ... WHERE id=-1 → 空
         ...... 每次都打到数据库！
```

**解决 ①：缓存空值（最简单、最常用）**

查到不存在的结果也缓存，但**存一个"空值标记"，且 TTL 很短**（1~5 分钟）。这样同样的请求第二次就命中缓存，不再打 DB。

```java
package com.example.service;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class UserCacheService {
    private static final Duration EMPTY_TTL = Duration.ofMinutes(1);   // 空值 TTL 一定要短
    private static final Duration DATA_TTL  = Duration.ofMinutes(10);
    private static final String  NULL_MARK  = "NULL";                  // 空值标记，别和真数据撞

    private final RedisTemplate<String, Object> redis;
    private final UserRepository userRepository;

    public UserCacheService(RedisTemplate<String, Object> redis, UserRepository userRepository) {
        this.redis = redis;
        this.userRepository = userRepository;
    }

    public User findById(Long id) {
        String key = "user:" + id;

        Object cached = redis.opsForValue().get(key);
        if (cached != null) {
            return NULL_MARK.equals(cached) ? null : (User) cached;   // 命中空值标记 → 返回 null
        }

        // miss → 查 DB
        User user = userRepository.findById(id);
        if (user != null) {
            redis.opsForValue().set(key, user, DATA_TTL);
        } else {
            redis.opsForValue().set(key, NULL_MARK, EMPTY_TTL);       // 缓存空值，防穿透
        }
        return user;
    }
}
```

> **缓存空值的坑**：空值 TTL 必须短（否则 DB 真插入新数据后，1 小时内都读不到）；空值标记要能区分"真数据"和"空"。写入后要记得**清空**（`DEL`）。

**解决 ②：布隆过滤器（Bloom Filter）——在查缓存前先挡一道**

布隆过滤器是一个**极省内存的"可能包含"集合**：判断"这个 key **一定不存在**"非常准，判断"可能存在"可能有小概率误判（假阳性）。把**所有合法 id** 塞进去，查询前先问过滤器：

```
查询 user:123
   │
   ▼
布隆过滤器：123 一定不存在吗？
   ├── "一定不存在" → 直接返回 null，连 Redis 都不查 ← 挡住攻击
   └── "可能存在" → 再走缓存 → DB
```

```java
package com.example.service;

import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;

@Service
public class IdBloomFilter {
    /** 预期元素数量 */
    private static final long EXPECTED_INSERTIONS = 1_000_000L;
    /** 期望误判率：1% */
    private static final double FPP = 0.01;

    private final UserRepository userRepository;
    private BloomFilter<String> bloom;

    public IdBloomFilter(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @PostConstruct
    public void init() {
        // 1% 误判率下，100 万条 id 大约只用不到 1MB 内存
        bloom = BloomFilter.create(Funnels.stringFunnel(StandardCharsets.UTF_8),
                EXPECTED_INSERTIONS, FPP);
        userRepository.findAllIds().forEach(id -> bloom.put(String.valueOf(id)));
    }

    public boolean maybeExists(String id) {
        return bloom.mightContain(id);   // false = 一定不存在；true = 可能存在（小概率误判）
    }
}
```

使用：

```java
// 查询前先挡一道
if (!bloom.maybeExists(id)) {
    return null;                     // 一定不存在，直接返回，连 Redis 都不查
}
User user = userCacheService.findById(id);
```

> **布隆过滤器要点**：① 它**只能判"一定不存在"**，"存在"是要靠缓存/DB 确认的。② 新增合法 id 后要 `put` 进去（或周期性重建）。③ 它挡的是"**恶意/无效 key**"，缓存空值挡的是"**合法但暂时没有**"——两者可以叠加。生产常用：**布隆过滤器在前 + 缓存空值在后**。

> **验证**：
> ```bash
> # 缓存空值：查一个不存在的 id 两次，第二次应命中缓存（不打印 DB 查询日志）
> curl "http://localhost:8080/user/-1"
> curl "http://localhost:8080/user/-1"   # 观察日志：第二次没有 SQL 输出
> redis-cli GET user:-1                   # → "NULL"（空值标记被缓存了）
> ```

### 2.2 击穿（Cache Breakdown）——热点 key 过期瞬间

**现象**：某个 key 是**高并发热点**（秒杀商品、热搜词）。它**过期的那一瞬**，所有请求同时 miss，**一起冲进数据库**。

```
缓存里 user:hot 刚过期
   │
   ▼
请求1 ──► miss ──► SELECT      │
请求2 ──► miss ──► SELECT      ├── 同一瞬间 N 个请求同时打 DB
请求3 ──► miss ──► SELECT      │
请求N ──► miss ──► SELECT      │
```

**解决 ①：互斥锁重建（mutex，只让一个请求去查 DB）**

谁 miss 了先抢一把锁（`SETNX`，见 [02-分布式锁](./02-Redis分布式锁实战.md)），**抢到锁的人去查 DB 回填**，其他人**等一下再读缓存**。这样 DB 最多被"一个请求"打一次。

```java
package com.example.service;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class UserCacheService {
    private final RedisTemplate<String, Object> redis;
    private final UserRepository userRepository;

    public UserCacheService(RedisTemplate<String, Object> redis, UserRepository userRepository) {
        this.redis = redis;
        this.userRepository = userRepository;
    }

    public User findById(Long id) {
        String key = "user:" + id;

        User cached = (User) redis.opsForValue().get(key);
        if (cached != null) return cached;                    // ① 缓存命中，直接返回

        // ② 未命中 → 抢"重建锁"
        String lockKey = "lock:rebuild:" + id;
        boolean locked = redis.opsForValue()
                .setIfAbsent(lockKey, "1", Duration.ofSeconds(5));   // SETNX + 5s 过期

        if (locked) {
            try {
                // ③ 双检：可能在我抢锁期间，别人已经重建好了
                cached = (User) redis.opsForValue().get(key);
                if (cached != null) return cached;

                // ④ 只有我查 DB（DB 只被一个请求打）
                cached = userRepository.findById(id);
                if (cached != null) {
                    redis.opsForValue().set(key, cached, Duration.ofMinutes(10));
                }
                return cached;
            } finally {
                redis.delete(lockKey);                        // ⑤ 释放锁（记得 finally）
            }
        }

        // ⑥ 没抢到锁 → 说明别人正在重建，睡 50ms 重试
        sleep(50);
        return findById(id);
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
```

> **要点**：① 锁要有 TTL（防持有者崩溃锁不释放，见 [02 第 2-3 章](./02-Redis分布式锁实战.md)）。② **双检**（抢到锁后再查一次缓存）是必须的，否则"抢锁期间别人已重建"会白查一次 DB。③ 递归重试要有上限（超时兜底），别无限递归。④ 这是**阻塞版**写法；WebFlux 响应式版见下方"响应式提示"。

**响应式提示（本仓库是 WebFlux）**：上面的 `Thread.sleep` 在 WebFlux 里**会卡死事件循环线程**（见 [00 第 5 章铁律](./00-Redis基础与SpringBoot使用.md)）。响应式版用 `Mono.delay` 代替 sleep：

```java
public Mono<User> findByIdReactive(Long id) {
    String key = "user:" + id;
    return redis.opsForValue().get(key)                      // ① 先查缓存
            .map(v -> (User) v)
            .switchIfEmpty(Mono.defer(() ->                  // ② miss → 抢锁
                    redis.opsForValue()
                            .setIfAbsent("lock:rebuild:" + id, "1", Duration.ofSeconds(5))
                            .flatMap(locked -> locked
                                    ? rebuild(id, key)                       // 抢到 → 重建
                                    : Mono.delay(Duration.ofMillis(50))      // 没抢到 → 50ms 后重试
                                            .flatMap(ignore -> findByIdReactive(id)))));
}

private Mono<User> rebuild(Long id, String key) {
    return redis.opsForValue().get(key)                          // 双检
            .cast(User.class)
            .switchIfEmpty(Mono.defer(() ->
                    // 阻塞 DB 访问用 boundedElastic 隔离（00 第 5 章铁律）
                    Mono.fromCallable(() -> userRepository.findById(id))
                            .subscribeOn(Schedulers.boundedElastic())
                            .flatMap(user -> redis.opsForValue()
                                    .set(key, user, Duration.ofMinutes(10))
                                    .thenReturn(user))))
            .doFinally(sig -> redis.delete("lock:rebuild:" + id));  // 释放锁
}
```

**解决 ②：逻辑过期（返回旧值 + 后台重建，读永不等待）**

思路：缓存里存"**值 + 逻辑过期时间**"。读的时候：逻辑没过期 → 直接返回；逻辑过期 → **先返回旧值**（请求不阻塞），另起一个线程/异步任务去重建缓存。

```java
public class CacheEntry<T> {          // 封装"值 + 逻辑过期时间"
    public final T value;
    public final long expireAt;       // 逻辑过期时间戳
    // 构造器 + getter 略
}

public User findById(Long id) {
    String key = "user:" + id;
    CacheEntry<User> entry = (CacheEntry<User>) redis.opsForValue().get(key);

    if (entry != null && entry.expireAt > System.currentTimeMillis()) {
        return entry.value;                          // 逻辑没过期 → 直接返回（快）
    }
    if (entry != null) {
        asyncRebuild(id, key);                       // 逻辑过期 → 先返回旧值，异步重建
        return entry.value;
    }
    return userRepository.findById(id);              // 缓存完全不存在 → 只能查 DB
}
```

> **对比**：互斥锁是"**大家等一个人重建完**"（读可能挂起，但 DB 压力最小）；逻辑过期是"**读到旧值也能接受**"（读不挂起，但会短暂读到旧值）。**对一致性要求不高、读延迟敏感的选逻辑过期**。

> **验证**：
> ```bash
> # 热点 key 手动设很短 TTL 模拟过期瞬间，并发压测
> redis-cli SET user:hot '{"name":"热点"}' EX 1
> # 用 ab/jmeter 并发 100 打 GET /user/hot
> # 观察 DB 查询日志：只有 1 条 SELECT（互斥锁生效），而不是 100 条
> ```

### 2.3 雪崩（Cache Avalanche）——大量 key 同时过期

**现象**：不是"一个 key"，而是**一大批 key 在同一时刻过期**（比如缓存预热时都设了 `EX 3600`，1 小时后整批到期），一波请求同时 miss，**DB 被打垮**。

```
t=0    大量 key 一起 SET EX 3600
t=3600 全部 key 一起过期 ──► 所有请求同时 miss ──► DB 瞬间被打垮
```

**解决 ①：TTL 加随机（最简单、最有效）**

让过期时间在基础值上**加一个随机偏移**，把"同时过期"打散成"分批过期"：

```java
package com.example.service;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class CacheService {
    private final RedisTemplate<String, Object> redis;

    public CacheService(RedisTemplate<String, Object> redis) {
        this.redis = redis;
    }

    public void putWithRandomTtl(String key, Object value, Duration base) {
        // 基础 TTL + 0~5 分钟随机偏移：10 分钟 → 10~15 分钟
        Duration ttl = base.plusSeconds(ThreadLocalRandom.current().nextLong(0, 300));
        redis.opsForValue().set(key, value, ttl);
    }
}
```

**解决 ②：多级缓存（本地缓存 + Redis，一层挡不住还有第二层）**

Redis 挂之前，先查**应用进程内的本地缓存**（Caffeine）。本地缓存是"每台机器一份"，天然分散，即使 Redis 整批过期，**本地缓存还能挡一批**，DB 压力大幅下降。代价：本地缓存有**副本不一致**（多实例各一份）。

```xml
<!-- Caffeine 本地缓存 -->
<dependency>
    <groupId>com.github.ben-manes.caffeine</groupId>
    <artifactId>caffeine</artifactId>
</dependency>
```

```java
package com.example.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class MultiLevelCacheService {
    // 本地缓存：最大 10000 条，60 秒过期，每台机器各一份
    private final Cache<String, Object> local = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofSeconds(60))
            .build();

    private final RedisTemplate<String, Object> redis;

    public MultiLevelCacheService(RedisTemplate<String, Object> redis) {
        this.redis = redis;
    }

    public Object get(String key) {
        Object v = local.getIfPresent(key);          // ① 本地缓存
        if (v != null) return v;
        v = redis.opsForValue().get(key);             // ② Redis 缓存
        if (v != null) {
            local.put(key, v);                        // ③ 回填本地（60s 内 Redis 挂了也能扛）
            return v;
        }
        v = dbQuery(key);                             // ④ 才到 DB
        local.put(key, v);
        redis.opsForValue().set(key, v, Duration.ofMinutes(10));
        return v;
    }
}
```

**解决 ③：缓存预热错峰 + 依赖服务降级限流**：批处理预热时**分批错峰**加载；Redis 不可用时业务**降级**（返回旧数据/缓存穿透保护）、**限流**，别让 DB 裸奔。

> **三兄弟一句话记忆**：
> - **穿透** = 查**没有**的东西 → 缓存空值 + 布隆过滤器。
> - **击穿** = 热点 key **单点过期** → 互斥锁重建 / 逻辑过期。
> - **雪崩** = 大量 key **同时过期** → TTL 加随机 + 多级缓存。

> **验证**：
> ```bash
> # TTL 随机化后，同一批 key 的 TTL 应该各不相同
> redis-cli SET k1 v EX 600
> redis-cli SET k2 v EX 637
> redis-cli SET k3 v EX 720
> redis-cli TTL k1   # 和 TTL k2 / TTL k3 不一样 → 已打散
> ```

---

## 第 3 章：Spring Cache 抽象——少写样板代码

第 2 章的代码每次都要手写 `GET → miss → DB → SET`，很啰嗦。**Spring Cache 用注解帮你把这个套路自动化**：一个注解下去，`miss 查 DB 回填`、`写后失效`全给你包了。

### 3.1 四个核心注解

| 注解 | 作用 | 相当于 Cache-Aside 的哪一步 |
|------|------|------------------------------|
| `@Cacheable` | 先查缓存，**未命中才执行方法**并把结果缓存 | 读：miss → 查 DB → 回填 |
| `@CachePut` | **总是执行方法**，把返回值写进缓存 | 写：更新缓存（一般配合删除用） |
| `@CacheEvict` | 执行方法后**删除缓存** | 写：删缓存 |
| `@CacheConfig` | 类级别的缓存公共配置（cacheNames 等） | 少写重复配置 |

### 3.2 依赖 + 开启注解

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-cache</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
```

```java
package com.example.config;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Configuration;

@EnableCaching                 // 开关：扫描 @Cacheable/@CachePut/@CacheEvict
@Configuration
public class CacheConfig {
}
```

`application.yaml`：

```yaml
spring:
  cache:
    type: redis              # 缓存后端用 Redis（Boot 会自动装配 RedisCacheManager）
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
```

> **注意**：Boot 4.x 只要同时引了 `cache` + `data-redis` 并设 `spring.cache.type=redis`，**不用写任何配置类就能用**（Boot 自动配一个 `RedisCacheManager`）。但默认 TTL 是**永不过期**、序列化是 JDK 二进制——所以生产要自己配（3.3 节）。

### 3.3 配置 RedisCacheManager（序列化 + TTL）

```java
package com.example.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Configuration
public class RedisCacheConfig {

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory factory) {
        // 默认配置：10 分钟 TTL，key 用 String，value 用 JSON，不缓存 null
        RedisCacheConfiguration defaults = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(10))
                .computePrefixWith(name -> name + ":")                     // 前缀：users:123
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new GenericJackson2JsonRedisSerializer()))
                .disableCachingNullValues();

        // 个别缓存单独设 TTL：hot 缓存只留 1 分钟
        Map<String, RedisCacheConfiguration> perCache = new HashMap<>();
        perCache.put("hot", defaults.entryTtl(Duration.ofMinutes(1)));
        perCache.put("users", defaults.entryTtl(Duration.ofMinutes(30)));

        return RedisCacheManager.builder(factory)
                .cacheDefaults(defaults)
                .withInitialCacheConfigurations(perCache)
                .build();
    }
}
```

> **为什么必须配序列化**：不配的话 value 用 JDK 序列化，`redis-cli` 里看是 `\xAC\xED...` 二进制乱码，而且跨语言/跨服务没法读（见 [00 第 4 章](./00-Redis基础与SpringBoot使用.md)）。`GenericJackson2JsonRedisSerializer` 会把对象序列化成 JSON，并带一个 `@class` 字段记录原类型，反序列化时能还原成 `User`。

### 3.4 @Cacheable 的 key、condition、unless

**key 生成**：不写 `key` 时用 Spring 默认 `SimpleKeyGenerator`——无参数 → 空 key；一个参数 → 这个参数；多个参数 → `SimpleKey[a,b,...]`。**强烈建议显式写 key**（SpEL 表达式）：

```java
@Cacheable(cacheNames = "users", key = "#id")                    // key = users:123
@Cacheable(cacheNames = "users", key = "#user.id")               // 取参数的属性
@Cacheable(cacheNames = "users", key = "'prefix:' + #id")        // 拼接固定前缀
@Cacheable(cacheNames = "users", key = "#root.methodName")       // 用方法名（Spring 提供的根对象）
@Cacheable(cacheNames = "users", keyGenerator = "myKeyGenerator")// 自定义 KeyGenerator Bean
```

**condition vs unless（这两个最容易被问）**：

| 参数 | 在什么时候判断 | 判断为 true 的后果 |
|------|--------------|-------------------|
| `condition` | **方法执行前**（基于入参） | 不缓存，且**方法照常执行** |
| `unless` | **方法执行后**（基于返回值） | 不缓存 |

```java
// 只缓存 id>0 的查询；id<=0 直接跳过缓存逻辑（方法仍执行）
@Cacheable(cacheNames = "users", key = "#id", condition = "#id > 0")
public User getUser(Long id) { ... }

// 不缓存 null 结果（返回值是 null 时不写缓存）
@Cacheable(cacheNames = "users", key = "#id", unless = "#result == null")
public User getUser(Long id) { ... }
```

> **`unless = "#result == null"` 是防穿透的"注解版"**：null 结果不进缓存，每次都要查 DB。所以注解版的穿透防御有限——真正的穿透防御（缓存空值/布隆）还是要手动做（第 2 章）。

### 3.5 @CachePut / @CacheEvict / @CacheConfig

```java
package com.example.service;

import org.springframework.cache.annotation.CacheConfig;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

@Service
@CacheConfig(cacheNames = "users")              // 类级别：默认缓存名，下面可省略
public class UserService {
    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    // 读：先查缓存，miss 才查 DB 并回填
    @Cacheable(key = "#id", unless = "#result == null")
    public User getUser(Long id) {
        return userRepository.findById(id);
    }

    // 写：总是执行方法，并把返回值写进缓存
    @CachePut(key = "#user.id")
    public User updateUser(User user) {
        userRepository.save(user);              // ① 先写 DB
        return user;                            // ② 返回值会被写进缓存
    }

    // 删：执行方法后删除缓存
    @CacheEvict(key = "#id")
    public void deleteUser(Long id) {
        userRepository.deleteById(id);
    }

    // 清空整个 users 缓存
    @CacheEvict(allEntries = true)
    public void clearCache() {
    }
}
```

> **`@CacheEvict` 两个常用属性**：
> - `allEntries = true`：清空整个缓存名下的所有 key（而不是单个 key）。
> - `beforeInvocation = true`：在方法执行**前**删缓存。默认是方法执行后——如果方法抛异常，缓存就不删了。**需要"方法失败也要删缓存"时用它**。
>
> **注意**：`@CachePut` 是"先执行方法再写缓存"，不是 Cache-Aside 的"删缓存"。想严格走"写 DB → 删缓存"，就用 `@CacheEvict`（上面的 `updateUser` 也可以改成"只删不更新"，下次读再回填——两种都对，删更省心，见 1.2 节）。

### 3.6 完整例子：一个可以跑通的服务

```java
package com.example.service;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

@Service
public class ProductService {
    private final ProductRepository productRepository;

    public ProductService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @Cacheable(cacheNames = "products", key = "#sku",
            unless = "#result == null", condition = "#sku != null")
    public Product getBySku(String sku) {
        return productRepository.findBySku(sku);   // 只有 miss 时才执行
    }
}
```

第一次调用 `getBySku("SKU-001")`：查 DB → 回填。第二次调用：直接命中缓存，方法体不执行。

### 3.7 验证

```bash
# 第一次调用：方法执行（日志里有 SQL），Redis 里多了一个 key
curl "http://localhost:8080/product/SKU-001"
redis-cli KEYS 'products:*'        # → 1) "products:SKU-001"
redis-cli GET 'products:SKU-001'   # → 一段 JSON（含 @class 字段）
redis-cli TTL 'products:SKU-001'   # → 接近配置的 TTL

# 第二次调用：方法体不执行（日志里没有 SQL）
curl "http://localhost:8080/product/SKU-001"   # 看日志，没有 SELECT
```

### 3.8 诚实提示：Spring Cache 注解是"阻塞抽象"

> **铁律回顾（[00 第 5 章](./00-Redis基础与SpringBoot使用.md)）**：WebFlux 响应式栈必须用响应式客户端。**Spring Cache 注解（@Cacheable）是阻塞 API**——它的缓存查询/写入是同步的。如果你在 WebFlux 的 `Mono`/`Flux` 方法上直接加 `@Cacheable`：
>
> - 方法返回 `Mono<User>` 时，注解缓存的是**这个 Mono 对象本身**（不是里面的 User）。对冷 Mono，第二次"命中"返回同一个 Mono，**重新订阅会重新执行方法**——缓存形同虚设，甚至更糟。
> - 而且缓存读写发生在事件循环线程上，阻塞 Redis 调用会卡死事件循环（00 铁律）。
>
> **所以本仓库（WebFlux）的做法**：不用 @Cacheable，改用**手动缓存 + `ReactiveRedisTemplate`**（第 4 章）。`@Cacheable` 这套留给**传统 MVC（Tomcat）项目**，它在阻塞栈里非常香。
>
> 如果你确实想在响应式里用注解，正确姿势是手动把缓存查改写进 reactive 链路（`Mono.defer` 包住缓存读写），或升级到 Spring Framework 6.1+ 的**响应式缓存抽象**（`CacheMono`/`CacheableOperator`）——那是另一个话题，初学者先掌握第 4 章手动方案。

---

## 第 4 章：Spring Data Redis 手动缓存（RedisTemplate + JSON）

不想用注解？或者你是 WebFlux（@Cacheable 不适用）？那就在业务代码里**手动读缓存**。这是最直白、最好控制的方式，[35-管数分离实战](../../tutorials/spring-ai-2.0/35-管数分离实战-从Sinks到Kafka演进.md) 的 run 状态缓存（`run:{id}:status`）就是这么干的。

### 4.1 配置 RedisTemplate + JSON 序列化

```java
package com.example.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);

        GenericJackson2JsonRedisSerializer json = new GenericJackson2JsonRedisSerializer();

        // key / hash key 用 String（和 redis-cli 一致、可读）
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        // value / hash value 用 JSON（存进去是 {"name":"张三"}，带 @class 还原类型）
        template.setValueSerializer(json);
        template.setHashValueSerializer(json);

        template.afterPropertiesSet();
        return template;
    }
}
```

> **为什么 value 用 `GenericJackson2JsonRedisSerializer` 而不是 `StringRedisSerializer`**：缓存的是**对象**（User），不是字符串。JSON 序列化后 `redis-cli` 能看、跨服务能读，且带 `@class` 字段能在反序列化时还原成原来的类型。

### 4.2 读写代码（可跑）

```java
package com.example.service;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class UserCacheService {
    private static final String KEY_PREFIX = "user:";
    private static final Duration TTL = Duration.ofMinutes(10);

    private final RedisTemplate<String, Object> redis;
    private final UserRepository userRepository;

    public UserCacheService(RedisTemplate<String, Object> redis, UserRepository userRepository) {
        this.redis = redis;
        this.userRepository = userRepository;
    }

    /** Cache-Aside 读：缓存 → miss → DB → 回填 */
    public User findById(Long id) {
        String key = KEY_PREFIX + id;

        User cached = (User) redis.opsForValue().get(key);   // ① 先查缓存
        if (cached != null) {
            return cached;                                   // ② 命中
        }

        User user = userRepository.findById(id);             // ③ miss → 查 DB
        if (user != null) {
            redis.opsForValue().set(key, user, TTL);         // ④ 回填 + TTL
        }
        return user;
    }

    /** Cache-Aside 写：先写 DB → 删缓存 */
    public User update(User user) {
        User saved = userRepository.save(user);              // ① 先写 DB
        redis.delete(KEY_PREFIX + saved.getId());            // ② 再删缓存
        return saved;
    }
}
```

**防穿透 + 防击穿都加上**（把第 2 章的坑缝进同一段代码）：

```java
public User findByIdSafe(Long id) {
    String key = KEY_PREFIX + id;

    Object cached = redis.opsForValue().get(key);
    if (cached != null) {
        return NULL_MARK.equals(cached) ? null : (User) cached;   // 命中（含空值标记）
    }

    // 击穿：抢重建锁，只让一个请求查 DB
    if (!redis.opsForValue().setIfAbsent("lock:rebuild:" + id, "1", Duration.ofSeconds(5))) {
        sleep(50);                                             // 没抢到 → 稍后重试
        return findByIdSafe(id);
    }
    try {
        Object again = redis.opsForValue().get(key);           // 双检
        if (again != null) {
            return NULL_MARK.equals(again) ? null : (User) again;
        }
        User user = userRepository.findById(id);
        if (user != null) {
            redis.opsForValue().set(key, user, TTL);
        } else {
            redis.opsForValue().set(key, NULL_MARK, Duration.ofMinutes(1));  // 穿透：缓存空值
        }
        return user;
    } finally {
        redis.delete("lock:rebuild:" + id);
    }
}
```

### 4.3 验证

```bash
# 读：第一次 miss 回填，第二次命中
curl "http://localhost:8080/user/1"
redis-cli GET user:1          # → {"@class":"com.example.User","id":1,"name":"张三"}
curl "http://localhost:8080/user/1"    # 看日志：第二次没有 SQL

# 写：先改 DB 再删缓存
curl -X PUT "http://localhost:8080/user/1" -d '{"name":"李四"}'
redis-cli GET user:1          # → (nil)，缓存已被删，下次读会重新回填新值
```

> **关键认知**：手动缓存 = `RedisTemplate` + 你自己写"缓存→DB→回填→失效"的逻辑。它比 @Cacheable 啰嗦，但**可读、可控、能处理穿透/击穿**，且天然适配 WebFlux（用 `ReactiveRedisTemplate`，方法名几乎一样，见 [00 第 4.4 节](./00-Redis基础与SpringBoot使用.md)）。

---

## 第 5 章：和数据库的一致性——先写 DB 还是先删缓存

### 5.1 为什么缓存和 DB 一定会有一致性窗口（诚实讲）

**缓存和数据库是两个独立的存储，两次写入不是原子的**——中间必然有一段时间"一个变了另一个没变"。这不是实现 bug，是分布式系统的物理事实。你只能选择**把窗口做多小、能否容忍**，无法消除。

两个"读写交错"能造成不一致的时序：

**情况一：先删缓存、后写 DB（错误顺序，窗口大）**

```
T1 读A：查缓存 → miss
T2 写B：删缓存（此时 DB 还没写）
T3 读A：查 DB → 读到旧值 v1
T4 写B：写 DB → v2
T5 读A：把旧值 v1 回填缓存   ←── 缓存里是旧值！
T6 之后所有读都命中 v1，直到 TTL 过期
```

**所以"先删缓存再写 DB"是错的**：删除和写入之间的空档，会让一个"读"读到旧值并回填，把旧值"固化"进缓存。

**情况二：先写 DB、后删缓存（正确顺序，窗口极小）**

```
T1 读A：查缓存 → 命中 v1（缓存里还是旧值）
T2 写B：写 DB → v2
T3 写B：删缓存
T4 读A：返回 v1        ←── 这一次读到了旧值（窗口：T1~T4，就这一次）
T5 之后的读：miss → 查 DB 得 v2 → 回填新值
```

窗口从"旧值被固化进缓存"（可能是 TTL 那么长）缩小成"**一次读**"（毫秒级）。这就是 Cache-Aside 写流程必须是"**先写 DB → 再删缓存**"的原因。

> **诚实结论**：**"先写 DB → 再删缓存"只把不一致窗口缩到最小，不能做到零**。T1 命中的那一次读，拿到的是旧值 v1。要彻底消除需要"读和写同一把锁"（串行化，性能全没），那缓存就没意义了。**所以：缓存是"用一致性换性能"的权衡，能容忍短暂旧值才用缓存。**

### 5.2 删缓存失败怎么办——延迟双删

"先写 DB → 删缓存"还有一个真实风险：**删缓存的那条 `DEL` 失败了**（Redis 超时、网络抖动），缓存里旧值还在。解法是**延迟双删（Double Delete）**：

```
① 写 DB
② 删缓存（第一次）
③ sleep 一段时间（如 500ms~1s）
④ 删缓存（第二次）   ← 把第一步到第二步之间"读回填的旧值"再删掉
```

```java
public void updateWithDoubleDelete(User user) {
    userRepository.save(user);                          // ① 写 DB
    redis.delete(KEY_PREFIX + user.getId());            // ② 第一次删
    sleep(500);                                         // ③ 等一下（给"读-回填"留出窗口）
    redis.delete(KEY_PREFIX + user.getId());            // ④ 第二次删
}
```

> **双删的局限（诚实）**：sleep 时间要靠经验估，估短了盖不住慢读，估长了写变慢；也不是原子的。**它是"压小概率"的实用补丁，不是数学证明**。更强的方案：**监听数据库 binlog（如 Debezium/Canal）精确失效缓存**——本仓库 [Debezium-CDC 实战](../Debezium-CDC实战/) 就是这套路，等读到那里再深入。

### 5.3 最终一致才是目标

- **能容忍短暂不一致（绝大多数读多写少业务）**：Cache-Aside（先写 DB → 删缓存）+ TTL 兜底。TTL 是"最后的保鲜期"——就算删缓存失败、就算窗口存在，**TTL 一到缓存自动过期，最终还是和 DB 一致**。这就是"最终一致"。
- **不能容忍（金融、库存、强一致）**：**不要用缓存**，或用读时校验/版本号（fencing，见 [02 第 5 章](./02-Redis分布式锁实战.md) 的 token 思想）。
- **数据形态判断**：**该当缓存用的**（热数据、读多写少、临时状态）放 Redis；**系统记录（system of record）**进数据库。看 [35-管数分离实战](../../tutorials/spring-ai-2.0/35-管数分离实战-从Sinks到Kafka演进.md) 的演进：第 6 章把 run 状态、幂等映射存 Redis KV（带 1 天 TTL），第 7 章因为要 SQL 查询、ACID、长期保留、审计，**把它们迁去 PostgreSQL，Redis 退回锁 + 实时通知**——这就是"什么时候缓存够用、什么时候必须进数据库"的真实决策。

### 5.4 验证

```bash
# 模拟"先写 DB 再删缓存"：写入后缓存应被清掉
redis-cli SET user:1 '{"name":"v1"}'
# 模拟写 DB 成功（v2）
redis-cli DEL user:1            # ① 删缓存
redis-cli GET user:1            # → (nil) 缓存已删
# 下次读：miss → 查 DB(v2) → 回填 → 缓存是 v2
redis-cli SET user:1 '{"name":"v2"}' EX 600
redis-cli GET user:1            # → {"name":"v2"}

# 验证"先删缓存后写 DB"的坑：
redis-cli SET user:1 '{"name":"v1"}'
redis-cli DEL user:1
# 模拟此时有一个读 miss 了、正在查 DB（读到 v1）→ 在"写 DB"之前回填
redis-cli SET user:1 '{"name":"v1"}'      # 读回填旧值
# 写 DB 完成 → 但按错误顺序，删缓存已经错过了
redis-cli GET user:1            # → {"name":"v1"} 旧值！只能等 TTL
```

---

## 总结

| 你该掌握的 | 一句话 |
|-----------|--------|
| **缓存为什么快** | 内存 + O(1) + 省一次 DB 查询：毫秒级 → 微秒级，用空间换时间 |
| **Cache-Aside** | 读：缓存 → miss → DB → 回填；写：**先写 DB → 删缓存**（90% 场景用这个） |
| **穿透** | 查"没有"的数据，每次打 DB → 缓存空值 + 布隆过滤器 |
| **击穿** | 热点 key 过期瞬间并发打 DB → 互斥锁重建 / 逻辑过期 |
| **雪崩** | 大量 key 同时过期 → TTL 加随机 + 多级缓存 |
| **Spring Cache** | `@Cacheable`/`@CachePut`/`@CacheEvict`/`@CacheConfig` + `RedisCacheManager` 配序列化和 TTL；`condition` 看入参、`unless` 看结果 |
| **手动缓存** | `RedisTemplate` + JSON 序列化，自己写"缓存→DB→回填→失效"，WebFlux 用 `ReactiveRedisTemplate` |
| **一致性** | 一定有窗口，只能"先写 DB 再删缓存"把窗口做小 + TTL 兜底最终一致；强一致就别用缓存 |

**缓存设计四步自检**（拿到需求先问自己）：

1. **数据读多写少吗？** 少写才值得缓存；写多（如计数器）另说。
2. **能容忍短暂旧值吗？** 不能容忍就别缓存，或考虑 binlog 精准失效。
3. **三个坑都防了吗？** 穿透（空值/布隆）、击穿（锁/逻辑过期）、雪崩（随机 TTL/多级）。
4. **失效时机对吗？** 写数据库后一定删缓存（不是更新），考虑双删 + TTL 兜底。

**接下来按顺序读**：

1. 缓存里经常要配合 **TTL 和锁**——复习 [00 第 3 章 TTL](./00-Redis基础与SpringBoot使用.md)、[02-分布式锁](./02-Redis分布式锁实战.md)。
2. 缓存数据/状态的另一种载体——[01-Streams 与 Pub/Sub](./01-Redis-Streams与PubSub实战.md)（缓存之外，Redis 还当数据总线用）。
3. 回头看 [35-管数分离实战](../../tutorials/spring-ai-2.0/35-管数分离实战-从Sinks到Kafka演进.md) 第 6-7 章：run 状态/幂等映射先放 Redis KV（第 6 章）、再迁 PostgreSQL（第 7 章）——对照本篇第 5 章，你会理解"缓存和数据库的分工"在真实项目里怎么落。

> **一句话记住缓存**：**数据库是真源，缓存只是它一份带 TTL 的副本——用对模式（Cache-Aside）、防对三坑（穿透/击穿/雪崩）、认命一致性（最终一致），缓存就是性能和成本的甜区。**
