# Reactor 调度器与线程模型：subscribeOn / publishOn 到底是啥

> **配套文档**：[Reactor 响应式入门](./01-Reactor响应式入门.md) 教你 `Mono`/`Flux` 是"菜谱不是结果"、`subscribe` 才执行、WebFlux 里永不 `block`——但留了一个**最大的坑没填**：到底在哪个线程上跑？管数分离文档里到处是 `Schedulers.boundedElastic()`，[Reactor 背压详解](./04-Reactor背压详解.md) 第 6 章也警告"subscribeOn/publishOn 控制的是**线程**不是速率"，但一直没人系统讲清楚**线程**这一层。本篇就是来填这个坑的。
>
> **难度假设**：你读完了 [01-Reactor响应式入门](./01-Reactor响应式入门.md)，懂 `Mono`/`Flux`/订阅，会写 `.map().flatMap()`，但一打印 `Thread.currentThread().getName()` 就懵：怎么一会儿 `main`、一会儿 `parallel-1`、一会儿 `boundedElastic-1`？这正是本篇要解决的。所有代码基于 Spring Boot 4.x + WebFlux + Reactor 3.7，照抄能跑。
>
> **本篇用一句话回答标题**：`subscribeOn` 影响**上游**（源/事件产生）在哪个线程，`publishOn` 影响**下游**（它之后的操作符）在哪个线程。

---

## 第 1 章：为什么会有线程问题——先建立心智模型

### 1.1 传统代码：就在"你写的那个线程"上跑

```java
public String getUserName(int id) {
    User u = userDao.findById(id);          // ① 阻塞等数据库
    return u.getName().toUpperCase();       // ② 拿结果继续算
}
```

这段代码**从头到尾跑在同一个线程上**——谁调用这个方法，①②③ 就都在那个线程上执行。线程是"自己从头走到尾的"，天然好理解。

### 1.2 响应式代码：线程会"跳"

响应式不是这样。看这条链：

```java
userDao.findByIdReactive(id)        // 源：发起异步查询
    .map(User::getName)             // 转换
    .subscribeOn(Schedulers.parallel())        // 切线程
    .publishOn(Schedulers.boundedElastic())    // 又切线程
    .map(String::toUpperCase)       // 转换
    .subscribe(System.out::println);
```

`findByIdReactive` 是**异步**的——数据回来时，是由**底层 I/O 框架的回调线程**（比如 Netty 的线程）把值继续往下送的。于是：

- 源和第一个 `map` 可能在 `parallel-1` 上执行；
- 中间某个操作符把值丢给另一个调度器，第二个 `map` 就跑到 `boundedElastic-1` 上；
- 消费的 `println` 又在另一个线程上。

**同样的"一段代码"，不同片段在不同线程上跑。** 这就是初学者最大的坎——你盯着链看，以为它在"你写的那个线程"上，其实它在多个线程之间跳。

### 1.3 心智模型：接力赛

把一条响应式链想成**接力赛**：

```
源(起跑) ──(parallel-1 接棒)──> map1 ──(parallel-1 接棒)──> map2 ──(boundedElastic-1 接棒)──> map3 ──> 终点(println)
```

- **每一棒（每个操作符）由谁跑，取决于上一棒把"棒"（数据）递到了哪个线程上**。
- 默认情况下没人干预，所有棒都在**起跑线程**（订阅者所在线程）上跑；
- 但数据源的**异步回调**、以及我们主动放的 `subscribeOn`/`publishOn`，会让某一段**换到别的线程去跑**。

> **两条铁律**：
> 1. **搭链（写 `.map().flatMap()`）的代码**，在"你写的那个线程"上执行——因为它只是**搭菜谱**，不耗时。
> 2. **链里的 lambda（`.map(x -> ...)` 里的那截代码）**，在"**订阅之后、数据流经时**"的线程上执行——也就是上一棒递过来的线程。
>
> 所以你在 lambda 里打印 `Thread.currentThread().getName()`，看到的**不一定是写代码时所在的线程**。这个认知是本章最重要的收获。

### 1.4 验证：什么都不写，全在调用线程

先看默认情况——**没有任何调度器**时，整条链就在订阅的那个线程上跑：

```java
import reactor.core.publisher.Flux;

public class DefaultThreadDemo {

    static void log(String stage, Object s) {
        System.out.println("[" + Thread.currentThread().getName() + "] " + stage + ": " + s);
    }

    public static void main(String[] args) {
        System.out.println("main 线程: " + Thread.currentThread().getName());

        Flux.just("A", "B")
            .doOnNext(s -> log("map1", s))   // doOnNext：值流经时看一眼，不改值
            .doOnNext(s -> log("map2", s))
            .subscribe(s -> log("subscribe", s));
    }
}
```

> **`.doOnNext()` 是什么**：和 `.map()` 一样在数据流经时执行一段代码，但**不改变数据**（map 要返回值，doOnNext 不用）。它天生适合打印线程名做验证，后文会一直用它。

运行输出：

```
main 线程: main
[main] map1: A
[main] map2: A
[main] subscribe: A
[main] map1: B
[main] map2: B
[main] subscribe: B
```

**结论**：默认情况下，源 `Flux.just`、所有操作符、subscribe 全在 `main` 线程上跑——因为没有人切线程，棒就一直握在起跑线程手里。**只有当源是异步的，或我们主动用调度器切线程，才会"跳"。**

---

## 第 2 章：Schedulers 全家桶——Reactor 的"线程池超市"

`Schedulers` 是 Reactor 内置的**线程池管理类**，`subscribeOn`/`publishOn` 的参数就是它。先认识四个最常用的。

### 2.1 一张表看懂

| 调度器 | 线程规模 | 典型用途 | 什么时候用 |
|--------|----------|----------|-----------|
| `Schedulers.immediate()` | 不建线程，**用当前线程** | 测试、不想要线程切换 | 默认行为的等价物，或调试 |
| `Schedulers.single()` | **1 个固定线程**，全 JVM 共享 | 极低开销的串行任务 | 定时器、UI 事件分发、需要"单线程串行"的边角 |
| `Schedulers.parallel()` | **CPU 核数**个线程 | CPU 密集型、非阻塞计算 | 加解密、压缩、计算；`Flux.interval` 默认用它 |
| `Schedulers.boundedElastic()` | **弹性扩容**，默认上限约 `10 × CPU核数`，队列上限 10 万，空闲线程 60s 回收 | **阻塞 I/O 的专用隔离池** | JDBC / MyBatis / RestTemplate / 文件 IO / 外部 API 等**一切阻塞调用** |

> **一句话记忆**：
> - 要**隔离阻塞调用** → `boundedElastic()`（90% 的场景都在这）；
> - 要做**CPU 密集计算** → `parallel()`；
> - 要**一条线程慢慢串** → `single()`；
> - 想**不切线程** → `immediate()`。

### 2.2 `boundedElastic()`：阻塞 I/O 专用（最常用，最重要）

这是 WebFlux 项目里**出现频率最高**的调度器。它的设计目的只有一个：**给"不得不做的阻塞调用"一个专属线程池，别污染响应式的事件循环线程**。

- 它是"弹性"的：任务多就多开线程，空闲就回收（60s 无任务销毁）。
- 它又是有"上限"的：默认最多约 `10 × CPU核数` 个线程，排队任务上限 10 万，**防止无脑开线程打爆内存**（这是对老 `elastic()` 的改进，老版无上限、已废弃）。

```java
Mono.fromCallable(() -> jdbcTemplate.queryForList("select ...")) // 阻塞 JDBC
    .subscribeOn(Schedulers.boundedElastic());                    // 丢进弹性线程池
```

### 2.3 `parallel()`：CPU 密集

固定 **CPU 核数**个线程，适合真正的计算密集任务（解密、压缩、序列化、正则大文本）。**不要**用它做阻塞 I/O——核数个线程一被阻塞就没了。

> **注意**：`Flux.interval(...)`、`Mono.delay(...)` 这类**定时器**默认就跑在 `parallel()` 上。所以你在定时器链里打印线程名，看到的是 `parallel-1`，不是你以为的"定时器专属线程"。

### 2.4 `single()`：一个固定线程

整个 JVM 共享**一个**线程（名字形如 `single-1`），适合"极低开销、必须串行"的任务（如 Swing/JavaFX 的 UI 事件分发）。别把慢任务丢给它——一条线程串行，慢了全堵。

### 2.5 `immediate()`：不切换

**"当前线程"调度器**——不建线程、不切换，就在调用它的线程上跑。等价于"什么都不写"。用在你不确定要不要切、或想显式表达"这里就地在当前线程跑"的场景（比如测试）。

### 2.6 验证：每个调度器的线程名

```java
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

public class SchedulersDemo {

    public static void main(String[] args) throws Exception {
        System.out.println("main 线程: " + Thread.currentThread().getName());

        Mono.just("immediate")
            .flatMap(n -> Mono.fromCallable(() -> Thread.currentThread().getName())
                              .subscribeOn(Schedulers.immediate())
                              .map(t -> n + " → " + t))
            .subscribe(System.out::println);

        Mono.just("single")
            .flatMap(n -> Mono.fromCallable(() -> Thread.currentThread().getName())
                              .subscribeOn(Schedulers.single())
                              .map(t -> n + " → " + t))
            .subscribe(System.out::println);

        Mono.just("parallel")
            .flatMap(n -> Mono.fromCallable(() -> Thread.currentThread().getName())
                              .subscribeOn(Schedulers.parallel())
                              .map(t -> n + " → " + t))
            .subscribe(System.out::println);

        Mono.just("boundedElastic")
            .flatMap(n -> Mono.fromCallable(() -> Thread.currentThread().getName())
                              .subscribeOn(Schedulers.boundedElastic())
                              .map(t -> n + " → " + t))
            .subscribe(System.out::println);

        Thread.sleep(500);  // 等异步线程打印完，别让 JVM 提前退出
    }
}
```

运行输出：

```
main 线程: main
immediate → main
single → single-1
parallel → parallel-1
boundedElastic → boundedElastic-1
```

> **验证结论**：`immediate` 不切线程（还在 `main`），`single`/`parallel`/`boundedElastic` 各建自己的线程池。线程名格式一眼可辨：`xxx-编号`。

### 2.7 补充：自定义线程池

内置的四个不够用时，可以自己造（比如给池子起个一眼能认的名字，方便排查）：

```java
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

// 自定义有界弹性池：上限 20 线程，排队 2000，线程名 myBlocking-N
Scheduler myBlocking = Schedulers.newBoundedElastic(20, 2000, "myBlocking", 60);

// 自定义并行池：4 线程，线程名 myCpu-N
Scheduler myCpu = Schedulers.newParallel("myCpu", 4);
```

> **命名技巧**：生产环境建议给自定义池起名，否则排查时满屏 `boundedElastic-3`、`parallel-5`，分不清是谁的。

---

## 第 3 章：subscribeOn vs publishOn——核心中的核心

这是全篇最关键的一章。把它彻底搞懂，线程问题就通关了。

### 3.1 一句话各归各

> - **`subscribeOn(s)`：影响"上游"**——**源（订阅源 / 事件产生）在哪个线程上发起**。源发出的数据从 `s` 线程出发往下流。
> - **`publishOn(s)`：影响"下游"**——**它之后的操作符**在哪个线程上执行。它在哪，就从哪把后面的链切到 `s`。
>
> **最容易记错的**：`subscribeOn` **不是**只管 `subscribe()` 那个方法本身，它管的是**整条链的源头**。而 `publishOn` 管的是**它后面**的所有操作符。

### 3.2 一张图

```
 Flux.just("A","B","C")
   .doOnNext(map1)              ┐
   .subscribeOn(parallel())     ├─ 这一段跑在 parallel-N
   .doOnNext(map2)              ┘
   .publishOn(boundedElastic()) ┐
   .doOnNext(map3)              ├─ 这一段跑在 boundedElastic-N
   .subscribe(println)          ┘

 源往下的"棒"：parallel-N ── 递到 publishOn 时被"截断" ──> 换成 boundedElastic-N
```

**核心机制**：
- `subscribeOn` 决定**源在哪起跑**，源发出的数据一路往下传时，**沿途操作符都在这个线程上**——直到遇到第一个 `publishOn` 才被截断换线程。
- `publishOn` 是一道"闸门"：数据流经它时，被排队并**转交给它指定的线程**去执行**它之后**的链。

### 3.3 完整例子验证（本系列最该跑一遍的 demo）

```java
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

public class ThreadSwitchDemo {

    static void log(String stage, Object s) {
        System.out.println("[" + Thread.currentThread().getName() + "] " + stage + ": " + s);
    }

    public static void main(String[] args) throws Exception {
        System.out.println("main 线程: " + Thread.currentThread().getName());

        Flux.just("A", "B")
            .doOnNext(s -> log("map1(源附近)", s))        // ①
            .subscribeOn(Schedulers.parallel())            // ②
            .doOnNext(s -> log("map2(中间)", s))          // ③
            .publishOn(Schedulers.boundedElastic())        // ④
            .doOnNext(s -> log("map3(下游)", s))          // ⑤
            .subscribe(s -> log("subscribe(消费)", s));   // ⑥

        Thread.sleep(1000);  // 等异步线程跑完（见第 6 章坑 2）
    }
}
```

运行输出（每次可能编号不同，但**线程类名和分段规律一致**）：

```
main 线程: main
[parallel-1] map1(源附近): A
[parallel-1] map2(中间): A
[parallel-1] map1(源附近): B
[parallel-1] map2(中间): B
[boundedElastic-1] map3(下游): A
[boundedElastic-1] subscribe(消费): A
[boundedElastic-1] map3(下游): B
[boundedElastic-1] subscribe(消费): B
```

### 3.4 逐段解读

| 段 | 跑在哪个线程 | 为什么 |
|----|-------------|--------|
| ① `map1(源附近)` | `parallel-1` | ② 的 `subscribeOn(parallel)` 让**源在 parallel 上起跑**，数据从 parallel 出发 |
| ③ `map2(中间)` | `parallel-1` | **还没遇到 `publishOn`**，棒还握在 parallel 手里 |
| ⑤ `map3(下游)` | `boundedElastic-1` | ④ 的 `publishOn(boundedElastic)` 是一道闸门，**它之后**全部切到 boundedElastic |
| ⑥ `subscribe` | `boundedElastic-1` | ⑥ 在 ④ 之后，跟着 ⑤ 一起在 boundedElastic 上跑 |

> **验证结论（背下来）**：`subscribeOn` 管的是"源 → 直到第一个 `publishOn` 之前"这一段；`publishOn` 管的是"它之后"这一段。**两刀把一条链切成三段，每段一个线程。**

### 3.5 位置很重要：publishOn 放哪，就在哪切

`publishOn` 的**位置**决定它切哪一段。把它挪到最前面，切点就整体前移：

```java
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

public class PositionDemo {

    static void log(String stage, Object s) {
        System.out.println("[" + Thread.currentThread().getName() + "] " + stage + ": " + s);
    }

    public static void main(String[] args) throws Exception {
        Flux.just("A", "B")
            .publishOn(Schedulers.parallel())     // 闸门放最前
            .doOnNext(s -> log("map1(在 publishOn 后)", s))
            .subscribeOn(Schedulers.single())     // subscribeOn 反而在后面
            .doOnNext(s -> log("map2(还在 publishOn 后)", s))
            .subscribe(s -> log("subscribe", s));

        Thread.sleep(1000);
    }
}
```

运行输出：

```
[parallel-1] map1(在 publishOn 后): A
[parallel-1] map2(还在 publishOn 后): A
[parallel-1] subscribe: A
[parallel-1] map1(在 publishOn 后): B
[parallel-1] map2(还在 publishOn 后): B
[parallel-1] subscribe: B
```

**解读**：`publishOn` 在链最前面，它之后的所有操作符（map1/map2/subscribe）**全在 parallel**。后面的 `subscribeOn(single)` 只悄悄影响了"源在 single 上起跑"这个看不见的环节——但 `Flux.just` 订阅时就同步发完了，你没机会观察到。

> **规则永远不变**：`publishOn` 前面的段跟着源/上游的线程跑，`publishOn` 后面的段切到它指定的线程跑。**想让哪段在哪个线程，就把 `publishOn` 放在那段之前。**

### 3.6 多个 subscribeOn / publishOn 的规则

用一个小 demo 一次性看清多条规则：

```java
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

public class MultipleSwitchDemo {

    static void log(String stage, Object s) {
        System.out.println("[" + Thread.currentThread().getName() + "] " + stage + ": " + s);
    }

    public static void main(String[] args) throws Exception {
        System.out.println("===== 多个 subscribeOn：离源最近的生效 =====");
        Flux.just("X")
            .subscribeOn(Schedulers.single())      // 离源最近 → 它赢
            .subscribeOn(Schedulers.parallel())    // 离源更远 → 无效
            .doOnNext(s -> log("map", s))
            .subscribe(s -> log("subscribe", s));

        System.out.println("\n===== 多个 publishOn：每个切一段 =====");
        Flux.just("A", "B")
            .doOnNext(s -> log("map1", s))                  // main
            .publishOn(Schedulers.parallel())               // 第一个闸门
            .doOnNext(s -> log("map2", s))                  // parallel
            .publishOn(Schedulers.single())                 // 第二个闸门
            .doOnNext(s -> log("map3", s))                  // single
            .subscribe(s -> log("subscribe", s));           // single

        Thread.sleep(1500);
    }
}
```

运行输出：

```
===== 多个 subscribeOn：离源最近的生效 =====
[single-1] map: X
[single-1] subscribe: X

===== 多个 publishOn：每个切一段 =====
[main] map1: A
[main] map1: B
[parallel-1] map2: A
[parallel-1] map2: B
[single-1] map3: A
[single-1] subscribe: A
[single-1] map3: B
[single-1] subscribe: B
```

**规则总结**：

| 场景 | 规则 |
|------|------|
| 多个 `subscribeOn` | **离源最近的那个生效**，其他的基本无效 |
| 多个 `publishOn` | **每个都生效**，各自把"它之后"切到对应线程（位置决定切哪段） |
| `publishOn` 在前、`subscribeOn` 在后 | 源还是被 `subscribeOn` 影响；`publishOn` 之后照常切 |

> **一句话**：`subscribeOn` 管"源"，重复放没意义（最近源者赢）；`publishOn` 管"闸门后"，放几个就切几段。

### 3.7 常见误区（新手三连）

**误区一：把 subscribeOn 放链尾，以为只影响 subscribe**

```java
// ❌ 想"让订阅跑在 parallel"，其实它管的是源
userDao.findByIdReactive(id)
    .map(User::getName)
    .subscribeOn(Schedulers.parallel())   // 它影响的是"源"起跑的线程，不是 subscribe 方法
    .subscribe(System.out::println);
```

`subscribeOn` 无论放在链的哪个位置，**效果基本都作用在源上**（前提是中间没有 `publishOn` 截断）。所以别指望"在链尾放个 subscribeOn 就能把消费切到别的线程"——消费在哪个线程，由**源起的线程**和**途中的 publishOn** 决定。

**误区二：放多个 subscribeOn 想"叠加"**

如上验证，**离源最近的才生效**，其他的都是噪音。

**误区三：以为 subscribeOn 只管 subscribe() 那一下**

它是"源"级的操作符——影响的是**整条上游链**（源 + 到第一个 publishOn 之前的所有操作符），远远不止 subscribe 那一行。

**误区四：拿 subscribeOn/publishOn 当限流**

> **这两个操作符只切线程，不控速率。** 想控制消费速率，用 `flatMap(fn, 并发度)` / `limitRate` / `onBackpressureXxx`——见 [Reactor 背压详解](./04-Reactor背压详解.md) 第 6 章坑 5。

---

## 第 4 章：阻塞调用怎么隔离——boundedElastic 的正确用法

### 4.1 为什么不能在 Netty event loop 上做阻塞调用

WebFlux 底层是 Netty，而 **Netty 只有很少几个事件循环线程**（通常约 `CPU核数 × 2` 个）。这些线程是"事件的神经中枢"——所有请求的收发都靠它们。

如果在一个事件循环线程上做了阻塞调用（比如直接调 JDBC）：

```
netty-thread-N: [发起请求1] → [JDBC 阻塞等待...50ms...] → [等待期间，请求2/3/4 全部没人处理！]
```

**一个线程被卡住 = 一批请求排队超时。** 线程越少，越不能容忍阻塞。这就是 [01-入门](./01-Reactor响应式入门.md) 第 1 章讲的"线程干等"老问题，在响应式世界里直接表现为**请求大面积超时**。

> **判据**：响应式栈里，**一切可能阻塞的操作**（JDBC、MyBatis-Flex、RestTemplate、`Thread.sleep`、文件读写、加解密大文件）**都必须丢出事件循环线程**，丢到 `boundedElastic()`。

### 4.2 标准姿势：`Mono.fromCallable` + `subscribeOn(boundedElastic)`

```java
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

public Mono<List<User>> users() {
    return Mono.fromCallable(() ->                    // 把阻塞代码包进 Callable
                jdbcTemplate.queryForList("SELECT * FROM user")  // 阻塞 JDBC
            )
            .subscribeOn(Schedulers.boundedElastic())  // ★ 丢进弹性池，别在事件循环上跑
            .map(rows -> convert(rows));
}
```

**为什么是 `subscribeOn` 而不是 `publishOn`**：阻塞代码在**源**里（`fromCallable` 是源），我们要让**源在弹性池线程上执行**——这正是 `subscribeOn` 的活。

### 4.3 验证：阻塞代码确实跑在 boundedElastic

```java
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

public class BlockingIsolationDemo {

    public static void main(String[] args) throws Exception {
        System.out.println("main 线程: " + Thread.currentThread().getName());

        Mono.fromCallable(() -> {
                System.out.println("[" + Thread.currentThread().getName() + "] 开始执行 JDBC SELECT ...");
                Thread.sleep(100);        // 假装阻塞 100ms（真实项目是数据库调用）
                return "查询到的行";
            })
            .subscribeOn(Schedulers.boundedElastic())
            .subscribe(rows -> System.out.println("[" + Thread.currentThread().getName() + "] 拿到结果: " + rows));

        Thread.sleep(500);
    }
}
```

运行输出：

```
main 线程: main
[boundedElastic-1] 开始执行 JDBC SELECT ...
[boundedElastic-1] 拿到结果: 查询到的行
```

> **验证结论**：`main` 线程发起后立刻返回（没有干等那 100ms），真正的"阻塞工作"在 `boundedElastic-1` 上完成。**main 没被卡住，事件循环也没被污染**——这就是隔离的意义。

### 4.4 实战对照：项目里就是这么用的

- **管数分离实战**（[35-管数分离实战](../../tutorials/spring-ai-2.0/35-管数分离实战-从Sinks到Kafka演进.md)）：MyBatis-Flex/JDBC 是阻塞的，每个 DB 调用都用 `Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())` 包一层；Redisson 的 `RLock`（阻塞 API）也一样处理。
- **Redis reactive**（[Redis Streams 与 Pub/Sub 实战](../Redis专题/01-Redis-Streams与PubSub实战.md)）：用的是 `ReactiveRedisTemplate`，**天然非阻塞**，不用包 `boundedElastic`。但一旦在链里混入阻塞的 `JdbcTemplate`/`RestTemplate`，就要立刻隔离。

> **判断一个 API 要不要包 boundedElastic**：看它是"阻塞"还是"非阻塞"。`ReactiveRedisTemplate`/`WebClient`/R2DBC 非阻塞，直接链式用；`JdbcTemplate`/`RestTemplate`/`Thread.sleep` 阻塞，**必须**用 `fromCallable + subscribeOn(boundedElastic)` 包。

---

## 第 5 章：和 WebFlux 配合——Controller 返回 Flux 时线程怎么走

### 5.1 什么都不写：跑在 Netty 事件循环线程上

```java
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import java.time.Duration;

@RestController
public class StreamController {

    // 纯非阻塞：不用任何调度器，框架替我们 subscribe
    @GetMapping(value = "/tick", produces = "text/event-stream")
    public Flux<ServerSentEvent<String>> tick() {
        return Flux.interval(Duration.ofSeconds(1))          // 定时器默认跑在 parallel
                   .map(i -> ServerSentEvent.builder("tick " + i).build())
                   .doOnNext(ev -> System.out.println(        // 打印执行线程
                       "[" + Thread.currentThread().getName() + "] 推送: " + ev.data()));
    }
}
```

请求打进来时，你能看到日志里出现 `reactor-http-nio-N` 这样的线程名——**这就是 Netty 事件循环线程**。框架在它上面替你 `subscribe`，纯非阻塞的链就在它上面一路跑完。

> **关键认知**：WebFlux 项目里，**大部分链根本不需要手动切线程**。`ReactiveRedisTemplate`、`WebClient`、`ReactiveMongoTemplate` 都是非阻塞的，一条链从 Netty 线程到 Netty 线程，性能最好。**不要为了"切线程"而切线程。**

### 5.2 什么时候才需要自己切

只有两类情况：

1. **链里混进了阻塞调用** → 用 `subscribeOn(boundedElastic())` 把阻塞源丢出去（第 4 章）。
2. **有一段很耗 CPU 的纯计算**，不想占着事件循环线程 → 用 `publishOn(parallel())` 把计算段切到 CPU 池：

```java
@GetMapping("/report")
public Mono<String> report() {
    return queryData()
            .publishOn(Schedulers.parallel())        // 下面的计算不占事件循环
            .map(this::heavyCpuComputation)          // 跑在 parallel
            .map(obj -> obj.toString());             // 跑在 parallel
}
```

### 5.3 `@Async` 的坑——响应式栈里别用

Spring 的 `@Async` 是**传统 MVC / 任务执行器**世界的概念（配 `@EnableAsync` + 一个线程池）。它要求方法返回 `void` 或 `Future`/`CompletableFuture`，由代理在**另一个线程**上执行方法体。

在 WebFlux 里混用 `@Async` 会踩坑：

```java
// ❌ 灾难级混搭：@Async + 返回 Mono
@Async
@GetMapping("/user/{id}")
public Mono<User> user(@PathVariable int id) {
    return userDao.findByIdReactive(id);   // @Async 期望返回 Future，却收到 Mono，语义混乱
}
```

问题在于：`@Async` 的线程跳转和响应式的线程模型**各跳各的**，上下文（上下文、TraceId、事务）容易丢，而且返回 `Mono` 时 `@Async` 代理的行为很迷。

> **正确做法**：响应式栈里**不要用 `@Async`**。要切线程就用 `subscribeOn`/`publishOn`，要并行走 `flatMap`。`@Async` 留给传统 MVC 的 `@Service` 方法（`void`/`Future` 返回）用。

### 5.4 `block()` 的下场——在 WebFlux 线程上直接报错

在 WebFlux 的 Netty 事件循环线程（`reactor-http-nio-*`）或 Reactor 的调度器线程上调用 `.block()`，Reactor 会**直接抛异常**，而不是"默默阻塞"：

```
java.lang.IllegalStateException: block()/blockFirst()/blockLast() are blocking, which is not supported in thread reactor-http-nio-3
```

> **这个异常是 Reactor 的保护机制**：它知道在事件循环线程上阻塞等于自杀，干脆让你立刻崩，而不是拖着整台机器超时。

**哪里可以 block**：普通 `main()` 方法、传统 MVC（Tomcat 线程）里可以；**WebFlux 的任何 reactor 线程上都不行**。详见 [01-Reactor响应式入门](./01-Reactor响应式入门.md) 第 4.2 节的铁律。

---

## 第 6 章：常见坑（初学者必看）

### 坑 1：println 看到的线程名跟自己想的不一样

```java
// ❌ 以为链在"写代码的线程"上跑
Flux.just("A")
    .map(s -> Thread.currentThread().getName())   // 链执行时（订阅后）才取线程名
    .subscribe();
```

**真相**：lambda 在**订阅后、数据流经时**才执行，取到的是**执行线程**，不是写代码时的线程。这就是为什么所有验证都靠 `doOnNext`/`map` 里打印 `Thread.currentThread().getName()`。

### 坑 2：main 方法里打印不到输出

```java
public static void main(String[] args) {
    Flux.just("A")
        .subscribeOn(Schedulers.parallel())     // 异步执行
        .subscribe(System.out::println);        // 可能在 main 退出后才打印
    // main 结束，JVM 退出，调度器线程是 daemon，输出可能没打出来
}
```

**真相**：Reactor 调度器线程是**守护线程（daemon）**，`main` 一结束 JVM 就退，异步输出会丢。**解决**：`main` 里用 `Thread.sleep(...)` 等一拍，或直接用 `.blockLast()` 等结果。

### 坑 3：`block()` 用在了 WebFlux 线程上

见 5.4。**解决**：WebFlux 里永不 block；要调阻塞 API，用 `Mono.fromCallable(...).subscribeOn(boundedElastic())` 并继续链式。

### 坑 4：线程池耗尽——大量阻塞任务把 boundedElastic 塞爆

`boundedElastic()` 默认上限约 `10 × CPU核数` 个线程、排队上限 10 万。如果业务里**大量**阻塞调用（且每个都慢），池子会满、队列会满，新任务被拒绝，表现为**一批请求超时/失败**。

```java
// ❌ 每个请求都开一堆阻塞调用，boundedElastic 扛不住
return Flux.range(1, 1000)
    .flatMap(i -> Mono.fromCallable(() -> slowBlockingCall(i))
                      .subscribeOn(Schedulers.boundedElastic()));  // 1000 个阻塞任务挤进池子
```

**解决**：`flatMap` 限并发（第二参数），别把无限量的阻塞任务丢进池子：

```java
.flatMap(i -> Mono.fromCallable(() -> slowBlockingCall(i))
                  .subscribeOn(Schedulers.boundedElastic()),
          8)   // 并发度限制 8，别打爆弹性池
```

> **本质**：`boundedElastic` 是"隔离阻塞"的工具，不是"无限并行"的通道。真正的正解是换非阻塞驱动（R2DBC、WebClient），让这些任务根本不阻塞。

### 坑 5：把 `subscribeOn`/`publishOn` 当限流

**真相**：它们只切线程，不控速率。限流用 `flatMap(fn, 并发度)`/`limitRate`/`onBackpressureXxx`——见 [Reactor 背压详解](./04-Reactor背压详解.md) 第 6 章坑 5。

### 坑 6：在 `map` 里做阻塞调用，白切了线程

```java
// ❌ 源在 boundedElastic，但 map 里又来了个阻塞调用……
return Mono.fromCallable(jdbc::query)
    .subscribeOn(Schedulers.boundedElastic())
    .map(rows -> restTemplate.getForObject(...));   // 这个阻塞调用跑在哪个线程？
```

`map` 里的阻塞调用跑在**它所在的线程**上——这里 map 在 `subscribeOn` 之后、没有 `publishOn` 截断，所以它还跟着源跑在 boundedElastic 上（还算走运）。但如果这条链先过了 `publishOn(boundedElastic)` 又切回事件循环，`map` 里的阻塞就会卡住 Netty 线程。

**解决**：**一切阻塞调用都包成 `fromCallable + subscribeOn(boundedElastic)`**，一个不漏，别让阻塞代码裸奔在链里。

---

## 总结

- **心智模型**：响应式链是**接力赛**，每段可能在不同线程跑。搭链代码在"你写的线程"，链里的 lambda 在"数据流经时的执行线程"。
- **四个调度器**：`boundedElastic()` 隔离阻塞 I/O（最常用）、`parallel()` 做 CPU 密集、`single()` 单线程串行、`immediate()` 不切换。
- **核心两操作符**：
  - `subscribeOn(s)`：管**上游/源**在哪个线程起跑，源的数据一路传到**第一个 `publishOn`** 才被截断。
  - `publishOn(s)`：管**它之后**的操作符在哪个线程执行；位置决定切哪段。
  - 多个 `subscribeOn` 离源最近者生效；多个 `publishOn` 每个都生效、各切一段。
- **阻塞隔离**：一切阻塞调用用 `Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())` 包起来，绝不裸奔在 Netty 事件循环上。
- **WebFlux 铁律**：纯非阻塞链**不用手动切线程**；`@Async` 别用；`block()` 在 reactor 线程上直接抛异常。
- **池子要省着用**：`boundedElastic` 默认上限约 `10 × CPU核数`，大量阻塞任务要 `flatMap` 限并发。

**自检清单**：看完本篇，你应该能回答——
1. 一条链默认在哪个线程跑？
2. `subscribeOn` 管哪段、`publishOn` 管哪段？中间的 map 跑在哪个线程？
3. 多个 `subscribeOn` 谁生效？多个 `publishOn` 呢？
4. 为什么阻塞调用必须丢 `boundedElastic`？不丢会怎样？
5. WebFlux 里 `block()` 会怎样？`@Async` 为什么不能用？

---

## 参考

- [Reactor 响应式入门](./01-Reactor响应式入门.md) —— Mono/Flux 心智、永不 block 铁律
- [Reactor 背压详解](./04-Reactor背压详解.md) —— 背压机制；"subscribeOn/publishOn 只切线程不控速率"的坑
- [Flux 方法速查](./02-Flux方法速查.md) —— doOnNext / map / flatMap / blockLast 语法
- [管数分离实战](../../tutorials/spring-ai-2.0/35-管数分离实战-从Sinks到Kafka演进.md) —— 大量 `subscribeOn(boundedElastic)` 隔离阻塞 DB/锁的真实用法
- [Redis Streams 与 Pub/Sub 实战](../Redis专题/01-Redis-Streams与PubSub实战.md) —— `ReactiveRedisTemplate` 非阻塞链的实际写法
