# Reactor Sinks 入门——从原理到企业级实践

> **33b 文档（Agent 可观测性）大量使用 `Sinks.Many`**：AgentEventBus、ShardedEventBus 都靠它做事件广播。但 Sinks 本身是独立的知识体系，没系统学过的话，看到"热流""背压""multicast"这些术语容易晕。
>
> 这份文档从零开始，由浅入深，带你完整掌握 Reactor Sinks——不仅是 API，更是理解"响应式事件驱动架构"的核心认知。

---

## 第 1 章：Sink 是什么——从一个"为什么"开始

### 1.1 传统 Flux 的局限：你没法从外面往里塞数据

Reactor 里一个典型的 Flux：

```java
Flux<String> stream = Flux.just("A", "B", "C");
// 或者
Flux<String> stream = Flux.fromIterable(list);
// 或者
Flux<String> stream = webClient.get().uri(url).retrieve().bodyToFlux(String.class);
```

不管是哪种，都是**数据源驱动**的——数据已经在那里了，Flux 只是把它推给订阅者。

**问题来了**：如果你的数据不是预先存在的，而是**外部事件动态产生的**——比如用户点击按钮、WebSocket 消息、其他服务发来的事件——你没法用传统 Flux 表达。你需要一个**能从外面往里推数据的"入口"**。

### 1.2 Sink 就是那个入口

```
        命令式代码（主动 push）                  响应式代码（被动接收）
  ┌──────────────────────┐       ┌──────────────────────┐
  │  eventBus.emit(x)   │──────→│   sink.asFlux()     │──→ 订阅者
  │  （你主动调）      │  Sink  │   （Flux 流）       │
  └──────────────────────┘       └──────────────────────┘
```

**Sink 是"命令式世界"和"响应式世界"之间的桥梁**。你代码主动调 `sink.tryEmitNext(value)`，它就把 value 塞进一个 Flux——所有订阅这个 Flux 的人立刻收到。

```java
// 创建一端 Sink
Sinks.Many<String> sink = Sinks.many().multicast().onBackpressureBuffer(16, false);

// 订阅者拿到的是普通 Flux，用法和任何 Flux 一样
sink.asFlux().subscribe(System.out::println);

// 你在代码的任何地方调 tryEmitNext——订阅者就收到
sink.tryEmitNext("来自命令式世界的数据");
```

这就是 33b 里 `AgentEventBus.emit()` 的本质——`emit` 内部调 `sink.tryEmitNext(event)`，SseController 订阅 `sink.asFlux()`。**一个塞、一个接，Sink 是中间的桥**。

### 1.3 冷流 vs 热流——Sink 为什么是"热"的

理解 Sink 的关键是理解"热流"：

| | 冷流 | 热流 |
|---|---|---|
| 什么时侯执行 | 有人订阅才执行 | 不受订阅影响，随时可能 emit |
| 订阅者关系 | 每订阅一次就**重新执行一遍**数据源逻辑 | 所有订阅者**共享同一条流** |
| 历史 | 新订阅者从第一条数据开始收到全部 | 新订阅者只收到**订阅之后**的数据 |
| 典型 | `Flux.just()`、`Flux.fromIterable()`、`webClient.get()` | `Sinks.Many`、`Flux.interval()` |

`Sinks.Many` 是热流——`emit` 时数据就发出去了，不管有没有人订阅。订阅者订上来时，只能从"订阅那一刻"开始收后面的数据，之前 emit 的历史收不到（除非用了 `replay()`）。

**33b 为什么用热流**：AgentEventBus 要广播事件给多个消费者（SSE、日志、成本统计），而且**写作任务只需执行一次**（冷流的话每多一个消费者就重跑一次 LLM）。热流让"写作执行一次、事件广播给所有人"。

---

## 第 2 章：Sinks.One——最简单的 Sink（入门从这里开始）

`Sinks.One<T>` 是最简单的 Sink，对应 `Mono<T>`——要么发一个值、要么发空、要么发错误。

### 2.1 基本用法

```java
Sinks.One<String> sink = Sinks.one();

// 从外面发一个值进 Mono
sink.tryEmitValue("hello");

// 订阅者收到这个值
Mono<String> mono = sink.asMono();
mono.subscribe(System.out::println);  // 输出 hello
```

### 2.2 三种结束方式

```java
sink.tryEmitValue("hello");   // 发一个值然后完成
sink.tryEmitEmpty();          // 发空然后完成（Mono.empty() 的效果）
sink.tryEmitError(new RuntimeException("出错了"));  // 发错误
```

只能发一次——`Sinks.One` 只能 emit 一个值或结束信号，再发后面的被忽略（返回 `FAIL_TERMINATED`）。

### 2.3 典型场景：命令式代码需要返回 Mono

```java
class UserService {
    Sinks.One<User> sink = Sinks.one();

    void onUserLoaded(String id) {
        // 模拟异步回调——从外部系统拿到结果后，通过 Sink 传回响应式世界
        User user = db.query("SELECT * FROM users WHERE id=?", id);
        sink.tryEmitValue(user);
    }

    Mono<User> getUser() {
        return sink.asMono();   // 返回 Mono，调用方就能在响应式链里 await
    }
}
```

33b 的 `ChainingService.run` 没直接用 `Sinks.One`，但 `Mono.fromCallable(...).subscribeOn(boundedElastic)` 底层的思路类似——把同步调用包装成 Mono。

---

## 第 3 章：Sinks.Many——核心主力（33b 的基石）

`Sinks.Many<T>` 对应 `Flux<T>`——可以连续发多个值。这是 33b 真正使用的 Sink。

### 3.1 创建方式——选对变体是关键

`Sinks.Many` 有三种风味，选哪种决定了"新订阅者能看到多少历史"：

```java
// 方式一：multicast（默认：不缓存历史）
Sinks.Many<String> a = Sinks.many().multicast().onBackpressureBuffer(16, false);

// 方式二：replay（缓存历史给新订阅者）
Sinks.Many<String> b = Sinks.many().replay().all();          // 全部历史
Sinks.Many<String> c = Sinks.many().replay().limit(5);       // 最近 5 条
Sinks.Many<String> d = Sinks.many().replay().limit(Duration.ofSeconds(10));  // 最近 10 秒

// 方式三：unicast（只允许一个订阅者）
Sinks.Many<String> e = Sinks.many().unicast().onBackpressureBuffer(16);
```

三者区别在"新订阅者能看到什么"：

| 变体 | 新订阅者能看到什么？ | 33b 用在哪 |
|------|-------------------|-----------|
| `multicast()` | 只能看到**订阅之后**的事件 | ✅ AgentEventBus——重连靠 Redis 回放，不靠 sink 缓存 |
| `replay()` | 能看到**订阅之前**的全部或部分历史 | ❌ 33b 不用（历史用 Redis/DB 持久化） |
| `unicast()` | 只允许 **1 个**订阅者，多了抛异常 | ❌ 33b 不用（要广播给多消费者） |

**实验验证区别**：

```java
// multicast——先发后订，收不到之前的
Sinks.Many<String> mc = Sinks.many().multicast().onBackpressureBuffer(16, false);
mc.tryEmitNext("A");    // 还没人订阅
mc.tryEmitNext("B");
mc.asFlux().subscribe(v -> System.out.println("收到: " + v));  // 现在才订阅
mc.tryEmitNext("C");    // 只有 C 能收到
// 输出：收到: C

// replay().all()——先发后订，能收到全部
Sinks.Many<String> rp = Sinks.many().replay().all();
rp.tryEmitNext("A"); rp.tryEmitNext("B");
rp.asFlux().subscribe(v -> System.out.println("收到: " + v));
rp.tryEmitNext("C");
// 输出：收到: A  收到: B  收到: C
```

### 3.2 autoCancel——没人订阅时怎么办

创建 `multicast` 时最后一个参数是 `autoCancel`：

```java
Sinks.many().multicast().onBackpressureBuffer(256, false);  // autoCancel=false
```

- **`true`（默认）**：当最后一个订阅者取消订阅时，Sink 自动关闭。之后 emit 返回 `FAIL_TERMINATED`。
- **`false`**：即使所有人都走了，Sink 也保持打开。之后再有新订阅者还能正常收事件。

**33b 为什么用 `false`**：AgentEventBus 可能某个时段没人在线（零订阅者），但之后会有人连上来。`autoCancel=false` 保证总线一直活着，新来的 SSE 连接还能收到之后的事件。

> ⚠️ **注意**：`autoCancel=false` 只适用于 `multicast()`。`replay()` 和 `unicast()` 行为不同——unicast 总是 autoCancel，replay 有自己的 TTL 逻辑。

### 3.3 完整的 33b AgentEventBus 源码分析

```java
@Component
public class AgentEventBus {
    private final Sinks.Many<AgentEvent> sink =
            Sinks.many().multicast().onBackpressureBuffer(256, false);

    public void emit(AgentEvent event) {
        sink.tryEmitNext(event);   // 非阻塞塞事件
    }

    public Flux<AgentEvent> subscribe(String sessionId) {
        return sink.asFlux().filter(event -> sessionId.equals(event.sessionId()));
    }
}
```

| 部分 | 含义 | 为什么这么选 |
|------|------|------------|
| `multicast()` | 不缓存历史，新订阅者只收之后的 | 重连用 Redis 回放，sink 不需要缓存 |
| `onBackpressureBuffer(256, false)` | 缓冲 256 条，autoCancel=false | 防短暂积压、零订阅者时不关 |
| `tryEmitNext`（非阻塞） | 失败不抛异常 | 事件总线不能拖垮生产者 |
| `subscribe(sessionId)` | 按会话过滤 | 每个 SSE 连接只要自己的事件 |

### 3.4 背压策略——消费者太慢怎么办

`onBackpressureBuffer` 是**背压策略**的一种。Sinks.Many 支持多种策略：

```java
// ===== 缓冲策略 =====
// 第二个 boolean 是 autoCancel，不是"满了是否抛异常"！见 3.2 节解释。
Sinks.many().multicast().onBackpressureBuffer(256, false);    // autoCancel=false：没人订阅也保持存活
Sinks.many().multicast().onBackpressureBuffer(256, true);     // autoCancel=true：最后一个订阅者离开后自动关闭
Sinks.many().multicast().onBackpressureBuffer(256,
    false, Duration.ofMinutes(1));   // autoCancel=false + 每条数据缓冲 1 分钟后被回收（TTL）

// ===== 其他策略 =====
Sinks.many().multicast().onBackpressureError();      // 不缓冲，满了直接让 emit 返回错误
Sinks.many().multicast().onBackpressureDrop(v -> {}); // 满了就直接丢掉新来的
Sinks.many().multicast().onBackpressureLatest();     // 满了时只保留最新的一条
```

| 策略 | 满时行为 | 适合场景 |
|------|---------|---------|
| `onBackpressureBuffer(N)` | 缓冲 N 条，再满则溢出 | 可容忍短暂积压 |
| `onBackpressureError()` | 立即返回错误 | 不能丢但也不能等（实时报警） |
| `onBackpressureDrop()` | 直接丢掉新事件 | DISCARDABLE 级别事件（33b 的 CONTENT_DELTA） |
| `onBackpressureLatest()` | 丢掉旧事件，只保留最新的 | 心跳/状态监听，只看最新值 |

**33b 为什么选 `onBackpressureBuffer(256)`**：第 1 章的注释写得清楚——"256 对调试场景够，生产按 metrics 调"。CRITICAL 事件由 Redis 兜底（第 2 章），buffer 只是瞬态缓冲。

---

## 第 4 章：emit 方法详解——tryEmitNext vs emitNext

### 4.1 tryEmitNext——非阻塞安全检查

```java
EmitResult result = sink.tryEmitNext(event);
```

不抛异常（除非参数为 null），返回一个枚举告诉你结果：

| 返回值 | 含义 | 怎么办 |
|--------|------|--------|
| `OK` | 成功 | 什么都不用做 |
| `FAIL_OVERFLOW` | 缓冲满了 | 记录溢出事件（33b 第 2 章事故 1 的根因） |
| `FAIL_TERMINATED` | Sink 已关闭 | 放弃或重建 Sink |
| `FAIL_CANCELLED` | 下游取消订阅了 | 不需要做什么 |
| `FAIL_ZERO_SUBSCRIBER` | 零订阅者 + autoCancel=true | 一般忽略 |

33b 的 AgentEventBus 简化了——不检查返回值。但生产里**至少应该 log 溢出**：

```java
EmitResult r = sink.tryEmitNext(event);
if (r == EmitResult.FAIL_OVERFLOW) {
    log.warn("AgentEventBus 缓冲满，丢弃事件: type={}", event.type());
}
```

### 4.2 emitNext——需要自定义失败策略时

```java
// 缓冲满时阻塞等待（直到有空间）
sink.emitNext(event, EmitFailureHandler.FAIL_FAST);
// └─ 失败立即返回（不等缓冲）——和 tryEmitNext 行为一样

// 缓冲满时无限重试（阻塞，危险！）
sink.emitNext(event, EmitFailureHandler.busyLooping(Duration.ofSeconds(10)));
// └─ 10 秒内一直重试，超时抛异常

// 忽略所有失败（同 tryEmitNext，但函数参数形式）
sink.emitNext(event, (signalType, emitResult) -> { });
```

**一般用 `tryEmitNext` 就够了**——它的非阻塞特性对事件总线来说是必要的（emit 不能拖垮业务代码）。只有在你**必须保证事件一定被发出**时才用 `emitNext` + `busyLooping`。

### 4.3 onEmitFailureHandler 深度解读

`emitNext` 的第二个参数是一个函数式接口：

```java
public interface EmitFailureHandler {
    boolean onEmitFailure(SignalType signalType, EmitResult emitResult);
    // 返回 true = 重试，返回 false = 放弃
}
```

`SignalType` 告诉你是哪个阶段失败了——`ON_NEXT`（普通值）、`ON_COMPLETE`（流结束信号）还是 `ON_ERROR`（错误信号）。大多数时候你只关心 `EmitResult`。

---

## 第 5 章：Sinks 的线程安全——企业级关键认知

Sinks 是**线程安全**的——这是它和 `Flux.create`（`FluxSink`）的重要区别。多个线程可以同时调 `tryEmitNext` 而不会数据错乱。

```java
Sinks.Many<Integer> sink = Sinks.many().multicast().onBackpressureBuffer(256, false);

// 多个线程同时往里塞——安全
for (int i = 0; i < 10; i++) {
    int value = i;
    new Thread(() -> sink.tryEmitNext(value)).start();
}

// 订阅者收到的是并发安全的（但不能保证顺序）
sink.asFlux().subscribe(v -> System.out.println("收到: " + v));
```

**但注意**：Sink 保证每个 `emit` 原子地进入流，**不保证多线程的 emit 顺序**。如果你需要严格按时间顺序的事件（33b 的场景），应该在 emit 的调用方保证顺序——通常是单线程生产者（`ChainingService.run` 的 for 循环里依次 emit 事件）。

---

## 第 6 章：Sinks 和 Flux.create（FluxSink）的区别

`Flux.create(sink -> { ... sink.next(v) ... })` 和 `Sinks.Many` 都叫"sink"，但**出身不同**：

| 对比维度 | `FluxSink`（Flux.create） | `Sinks.Many` |
|----------|--------------------------|--------------|
| **创建方式** | `Flux.create(callback)` | `Sinks.many().XXX()` |
| **生命周期** | 随 `Flux.create` 的订阅而创建，流结束时自动销毁 | 独立创建，手动管理 |
| **热/冷** | 冷流——订阅时才执行 callback | 热流——独立于订阅 |
| **多订阅者** | ❌ 每次订阅都重建 callback | ✅ 所有订阅者共享同一份数据 |
| **线程安全** | 要看实现——callback 内部可能单线程 | ✅ 天生线程安全（内部用 CAS） |
| **背压控制** | callback 里自己实现 | ✅ 内置 `onBackpressureBuffer/Error/Drop/Latest` |
| **33b 用法** | 旧版 streamStep 里的 `Flux.create`（已重构）| ✅ 全文档的核心总线 |

**33b 为什么最终选择了 `Sinks.Many` 而不是 `Flux.create`**：

第 1 章旧版用 `Flux.create` + 同步 for 循环 + `FluxSink`（已重构掉）。第 1 章的 run 返回一个冷流（Flux），只有一个消费者（SSE）。但第 2 章开始需要**多个消费者共享事件**（SSE + 日志 + 成本统计），`Flux.create` 的冷流特性导致每次新订阅者都重跑整条链——这正是 1.1 讲的"为什么需要 EventBus"的原因：**冷流每多一个订阅者就重跑一次 LLM，热流只广播一次**。

所以最终架构是：**run 冷流（SSE 主消费者驱动） + EventBus 热流（广播给其他消费者）**，两者各司其职。

---

## 第 7 章：完整实战——手写一个 Mini 事件总线

把 33b 的 AgentEventBus 核心逻辑抽象出来，看 Sinks 在真实场景里的完整用法：

```java
/**
 * 最小事件总线（基于 Sinks.Many）
 * 33b AgentEventBus 的简化版，突出 Sinks 的使用模式。
 */
public class MiniEventBus<T> {

    private final Sinks.Many<T> sink =
            Sinks.many().multicast().onBackpressureBuffer(256, false);

    // ===== 生产者 API =====

    /** 发射事件。返回 true=成功，false=失败（缓冲满/已关闭）。 */
    public boolean emit(T event) {
        EmitResult r = sink.tryEmitNext(event);
        if (r == EmitResult.FAIL_OVERFLOW) {
            System.err.println("[MiniBus] 缓冲满，丢弃: " + event);
        }
        return r == EmitResult.OK;
    }

    public void emitComplete() {
        sink.tryEmitComplete();
    }

    // ===== 消费者 API =====

    /** 订阅全部事件。 */
    public Flux<T> allEvents() {
        return sink.asFlux();
    }

    /** 按条件订阅（33b 用到这个模式——按 sessionId 过滤）。 */
    public Flux<T> subscribe(java.util.function.Predicate<T> filter) {
        return sink.asFlux().filter(filter);
    }

    // ===== 统计 =====

    public long getBufferSize() {
        // Sinks 不直接暴露当前缓冲数——这是设计上的"不关心"，生产用 Metrics 监控
        return -1;
    }
}
```

验证：

```java
MiniEventBus<String> bus = new MiniEventBus<>();
bus.emit("start");

// 订阅者 1（此时订阅，之前的 "start" 收不到——multicast 不缓存）
bus.allEvents().subscribe(v -> System.out.println("订阅者1: " + v));

bus.emit("step1");
bus.emit("step2");

// 订阅者 2（step1/step2 已发完，收不到——但后续能收到）
bus.allEvents().subscribe(v -> System.out.println("订阅者2: " + v));

bus.emit("step3");  // 订阅者1 和 2 都能收到 step3
```

---

## 第 8 章：常见坑与排查方法

### 坑 1：缓冲满丢事件（33b 第 2 章事故 1）

**现象**：`SESSION_COMPLETED` 没到前端，页面一直转圈。

**根因**：高峰期 `tryEmitNext` 返回 `FAIL_OVERFLOW`，但没检查返回值。缓冲满了事件被静默吞掉。

**修复**：
1. 检查返回值并 log 溢出（至少），方便排查
2. 关键事件落 Redis 兜底（33b 第 2 章的做法）
3. 生产里按 metrics 调 buffer 大小

### 坑 2：autoCancel=true 导致新订阅者收不到事件

**现象**：一段时间没人订阅后，新连接上来就绪，但收不到任何事件。AgentEventBus 是 "dead" 状态。

**根因**：`multicast().onBackpressureBuffer(256, true)` — `autoCancel=true`（默认）。最后一个订阅者取消时，sink 自动关闭（`FAIL_TERMINATED`）。之后再 `subscribe` 拿到的是已关闭的 sink。

**修复**：33b 用的是 `false`，这是对的。但如果你在自己项目里默认用了 `true`（Lombok `@Builder` 之类的默认值），就会踩这个坑。

### 坑 3：replay().all() 导致 OOM

**现象**：运行一段时间后内存飙升，GC 频繁。

**根因**：`Sinks.many().replay().all()` 把所有 emit 过的事件都缓存在内存里。如果事件量大（如每步几十个 CONTENT_DELTA），几天下来积累几千万条事件。

**修复**：
1. 只缓存最近 N 条（`replay().limit(N)`）
2. 或设时间窗口（`replay().limit(Duration)`）
3. 持久化的历史放 Redis/DB（33b 的做法）

### 坑 4：`emitNext` 无限重试导致线程 hang

**现象**：某个 emit 调用一直不返回，卡死生产者线程。

**根因**：
```java
sink.emitNext(event, EmitFailureHandler.busyLooping(Duration.ofSeconds(30)));
// 如果下游消费极慢、缓冲一直被占满，emitNext 会在 30 秒内无数重试
```

**修复**：用 `tryEmitNext`（33b 的做法）或设较短超时。

### 坑 5：多线程 emit 顺序不可预测

Sinks 保证单次 emit 的原子性（不会两个事件内容混在一起），但**不保证多线程的 emit 顺序**。两个线程同时 emit，事件 1 可能在事件 2 之后到达订阅者。

**33b 怎么做的**：`ChainingService.run` 在一个线程里顺序 emit（for 循环），单生产者模式。EventBus 本身不担心顺序问题——顺序由生产者保证。

---

## 第 9 章：和 33b 文档的逐章对照

| 33b 章节 | Sinks 相关用法 | 学习到的知识点 |
|---------|--------------|--------------|
| **第 1 章** AgentEventBus | `Sinks.many().multicast().onBackpressureBuffer(256, false)` | multicast + autoCancel=false + tryEmitNext |
| **第 2 章** 事故 1 | `tryEmitNext` 返回 `FAIL_OVERFLOW` 没检查 | 必须检查 EmitResult |
| **第 2 章** CriticalEventStore | 关键事件落 Redis | 不要依赖 Sink 内存缓存 — 持久化靠外部存储 |
| **第 4 章** ShardedEventBus | 16 个 `Sinks.Many` 数组 + hash 路由 | 多个 Sink 分片，减小单点缓冲压力 |
| **第 1 章旧版** Flux.create | `FluxSink` | Flux.create 是冷流 -> 不适合多消费者 |

---

## 第 10 章：深入学习

- [Project Reactor Sinks 官方文档](https://projectreactor.io/docs/core/release/reference/#sinks) —— 最权威的参考（英文）
- [33b-Agent可观测性企业级演进实践](../../tutorials/spring-ai-2.0/33b-Agent可观测性企业级演进实践.md) —— 33b 全套源码，Sinks 的企业级实战
- [Flux方法速查](./02-Flux方法速查.md) —— 配套的 Reactor 操作符参考

---

*系统学完这一篇，你应该能回答这些问题：*
- *Sinks 解决了什么问题？*（从外面往里推数据）
- *multicast / replay / unicast 的区别是什么？*（新订阅者能看多少历史）
- *`tryEmitNext` 和 `emitNext` 怎么选？*（事件总线用非阻塞的 `tryEmitNext`，必须保证发出才用 `emitNext`）
- *背压满时业务代码该怎么处理？*（至少 log、关键事件持久化兜底）
