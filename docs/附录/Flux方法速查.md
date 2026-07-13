# Reactor Flux 方法速查（从零开始，按使用频率排序）

## 先建立概念：什么是 Flux？

把 `Flux` 想象成一条**会持续吐数据的水管**。

- 普通的 `List` 像一桶水——你打开盖子看，里面的水都在那儿了。
- `Flux` 像一条水管——水一段一段流过来，你**不知道下一秒会流来什么**，可能流一会儿就停了，也可能一直流下去。

每个 `Flux` 有三种"信号"会发出来：
1. **数据**：吐出一个元素（`onNext`）
2. **结束**：水龙头关了，再也不会有数据了（`onComplete`）
3. **出错**：水管爆了，出问题了（`onError`）

所有 `Flux` 方法，本质上就是"在某个信号发生时做点事"或者"把流过来的数据改一改"。

### 一个核心区分：变换 vs 旁路观察

- **变换型方法**（`map`、`filter`、`flatMap`...）：会**改变**流的内容。就像在管子中间加了一个过滤器，流出去的水变了。
- **旁路观察型方法**（`doOnNext`、`doOnComplete`...）：方法名**以 `do` 开头**就是这一类。像在管子上钻个洞装个仪表盘，看一眼里面流什么，但水还是原来的水继续流。

**记忆口诀**：方法名以 `do` 开头 → 只看不改，不影响流。

### 还有最重要的一点：声明 ≠ 执行

你写一长串 `.map().filter().doOnNext()` 时，**水流并没有真的开始流动**。你只是在描述"如果有人开水龙头，应该这么处理"。

只有当你调用 `.subscribe()`（订阅），或者 Spring 框架帮你订阅时，水龙头才真的打开，整条流水线才开始跑。

---

下面所有示例都可以**直接粘贴到 main 方法里运行**看效果。

---

## 第一档：天天用

### 1. `map` — 把每个元素改个样

**大白话**：每个数据进来，套一个公式算出新值。
**类比**：传送带上每个物品经过一道工序，比如"每个数字 ×10"。

```java
Flux.range(1, 3)              // 流进来：1, 2, 3
    .map(n -> n * 10)          // 每个乘 10
    .subscribe(System.out::println);
// 输出：10, 20, 30
```

### 2. `filter` — 挑出符合条件的

**大白话**：给数据设个关卡，满足条件的放过去，不满足的扔掉。

```java
Flux.range(1, 5)               // 流进来：1, 2, 3, 4, 5
    .filter(n -> n % 2 == 0)   // 只留偶数
    .subscribe(System.out::println);
// 输出：2, 4
```

### 3. `flatMap` — 一个元素展开成多个

**大白话**：每个进来的人，给他发多个物品。比如进来一个 `a`，出去 `A` 和 `a_x`。
**注意**：结果顺序可能乱（因为并行的）。

```java
Flux.just("a", "b", "c")
    .flatMap(s -> Flux.just(s.toUpperCase(), s + "_x"))
    .subscribe(System.out::println);
// 输出（顺序不一定）：A, a_x, B, b_x, C, c_x
```

### 4. `collectList` — 把所有元素攒成一个 List

**大白话**：把整条流水线的物品都装进一个袋子里，最后只给你一个袋子。
**注意**：返回值变成 `Mono`（袋子里只有一袋东西），不再是 `Flux`。

```java
Flux.range(1, 3)
    .collectList()
    .subscribe(list -> System.out.println(list));
// 输出：[1, 2, 3]
```

### 5. `subscribe` — 把水龙头拧开（开始执行）

**大白话**：前面你写的代码都是"设计图"，调了 `subscribe` 才真的开始跑。

```java
Flux.just(1, 2, 3)
    .subscribe(
        item -> System.out.println("收到：" + item),   // 收到数据时做什么
        err  -> err.printStackTrace(),                  // 出错时做什么
        ()   -> System.out.println("水龙头关了")        // 结束时做什么
    );
// 输出：收到：1 / 收到：2 / 收到：3 / 水龙头关了
```

### 6. `doOnNext` — 每个数据经过时偷偷看一眼

**大白话**：在管子上装个透明的观察窗，每个数据经过你都能看到，但你**不能改它**，它继续往前流。

```java
Flux.range(1, 3)
    .doOnNext(n -> System.out.println("我看到了：" + n))
    .map(n -> n * 10)
    .subscribe(n -> System.out.println("最终：" + n));
// 输出：
// 我看到了：1
// 最终：10
// 我看到了：2
// 最终：20
// 我看到了：3
// 最终：30
```

### 7. `doOnComplete` — 水龙头正常关闭时喊一声

**大白话**：流结束时（不是出错，是正常结束）的通知。

```java
Flux.range(1, 3)
    .doOnComplete(() -> System.out.println("全部流完了"))
    .subscribe();
// 输出：全部流完了（在 1, 2, 3 都流完之后）
```

### 8. `doOnError` — 水管爆了时喊一声

**大白话**：流异常中断时通知你。

```java
Flux.<Integer>error(new RuntimeException("水管爆了"))
    .doOnError(err -> System.out.println("出错了：" + err.getMessage()))
    .subscribe();
// 输出：出错了：水管爆了
```

### 9. `onErrorResume` — 出错时换一条备用流

**大白话**：主水管爆了，自动切换到备用管子继续供水。

```java
Flux.<Integer>error(new RuntimeException("主水管挂了"))
    .onErrorResume(err -> {
        System.out.println("主挂了，换备用");
        return Flux.just(99, 100);
    })
    .subscribe(System.out::println);
// 输出：主挂了，换备用 / 99 / 100
```

### 10. `onErrorReturn` — 出错时给一个固定默认值

**大白话**：出错时塞一个"挡板值"过去，调用方完全感受不到出错了。

```java
Flux.<Integer>error(new RuntimeException("挂了"))
    .onErrorReturn(-1)
    .subscribe(System.out::println);
// 输出：-1
```

---

## 第二档：常用

### 11. `reduce` — 把所有元素累加成一个值

**大白话**：像算盘一样，把每个元素累加到一起，最后只剩一个结果。

```java
Flux.range(1, 5)
    .reduce(0, Integer::sum)   // 从 0 开始累加
    .subscribe(sum -> System.out.println(sum));
// 输出：15  （1+2+3+4+5）
```

### 12. `take` — 只要前 N 个

**大白话**：流水线只放前 N 个物品过去，多了的不要。

```java
Flux.range(1, 100)
    .take(3)
    .subscribe(System.out::println);
// 输出：1, 2, 3
```

### 13. `skip` — 跳过前 N 个

**大白话**：开头几个不要，从第 N+1 个开始要。

```java
Flux.range(1, 5)
    .skip(2)
    .subscribe(System.out::println);
// 输出：3, 4, 5
```

### 14. `defaultIfEmpty` — 流空了就给个默认值

**大白话**：如果水管里啥都没流出来，给一个"保底"值。

```java
Flux.<Integer>empty()           // 这是一条空流
    .defaultIfEmpty(0)          // 空的话就发 0
    .subscribe(System.out::println);
// 输出：0
```

### 15. `switchIfEmpty` — 流空了就切到另一条流

**大白话**：如果这条管子没水，切换到另一条管子继续供水。

```java
Flux.<Integer>empty()
    .switchIfEmpty(Flux.just(1, 2, 3))
    .subscribe(System.out::println);
// 输出：1, 2, 3
```

### 16. `doFinally` — 不管成功失败，结束时都喊一声

**大白话**：无论水管正常关了、爆了、还是被人取消了，最后都要做一件事（比如清理资源）。
**和 `doOnComplete` 的区别**：`doOnComplete` 只在正常结束时触发，`doFinally` 在所有结束情况下都触发。

```java
Flux.range(1, 3)
    .doFinally(sig -> System.out.println("结束信号类型：" + sig))
    .subscribe();
// 输出：结束信号类型：onComplete
```

### 17. `concatMap` — 像 flatMap，但保持顺序串行

**大白话**：和 `flatMap` 一样能把一个元素展开成多个，但**严格按顺序**，一个处理完才处理下一个。
**何时用**：需要保序的场合（比如按时间顺序处理消息）。

```java
Flux.just("a", "b", "c")
    .concatMap(s -> Flux.just(s + "1", s + "2"))
    .subscribe(System.out::println);
// 输出（严格按序）：a1, a2, b1, b2, c1, c2
```

### 18. `publishOn` — 从这里开始换个线程处理

**大白话**：在管子中间换了一组工人接手，后面所有处理都由新工人做。

```java
Flux.range(1, 3)
    .publishOn(reactor.core.scheduler.Schedulers.boundedElastic())
    .doOnNext(n -> System.out.println("在哪个线程处理：" + Thread.currentThread().getName()))
    .subscribe();
```

### 19. `subscribeOn` — 设置整条流在哪个线程跑

**大白话**：决定"拧开水龙头"这个动作由谁来做。整条流水线的源头由这个线程发起。

```java
Flux.range(1, 3)
    .subscribeOn(reactor.core.scheduler.Schedulers.parallel())
    .doOnNext(n -> System.out.println(Thread.currentThread().getName()))
    .subscribe();
```

### 20. `retry` — 失败了重试

**大白话**：水管爆了别马上放弃，再试 N 次。

```java
java.util.concurrent.atomic.AtomicInteger count = new java.util.concurrent.atomic.AtomicInteger(0);
Flux.<Integer>create(sink -> {
    if (count.incrementAndGet() < 3) {
        sink.error(new RuntimeException("第 " + count + " 次失败"));
    } else {
        sink.next(100);
        sink.complete();
    }
}).retry(5)   // 最多重试 5 次
  .subscribe(System.out::println);
// 输出：100（前 2 次失败，第 3 次成功）
```

### 21. `timeout` — 等太久就报错

**大白话**：流到一半长时间不下一个数据，就判定为"超时"，发错误信号。

```java
Flux.range(1, 3)
    .delayElements(java.time.Duration.ofMillis(100))  // 每个延迟 100ms
    .timeout(java.time.Duration.ofMillis(50))         // 但 50ms 内必须有下一个
    .subscribe(
        System.out::println,
        err -> System.out.println("超时了：" + err.getMessage())
    );
// 输出：超时了（50ms 等不到第二个）
```

### 22. `distinct` — 去重

**大白话**：把出现过的元素都记住，再遇到相同的就扔掉。

```java
Flux.just(1, 2, 2, 3, 3, 3, 1)
    .distinct()
    .subscribe(System.out::println);
// 输出：1, 2, 3
```

### 23. `delayElements` — 每个元素延迟一下再发

**大白话**：在管子里加个延时阀，每个数据慢悠悠地出来。

```java
Flux.range(1, 3)
    .delayElements(java.time.Duration.ofMillis(500))   // 每个延迟 0.5 秒
    .subscribe(n -> System.out.println(System.currentTimeMillis() + " - " + n));
// 每 0.5 秒打印一个
```

### 24. `delaySequence` — 整条流先等一会儿

**大白话**：水龙头打开后先干等 N 时间，然后才开始正常流。

```java
Flux.range(1, 3)
    .delaySequence(java.time.Duration.ofSeconds(2))   // 先等 2 秒
    .subscribe(n -> System.out.println(n));
// 2 秒后开始：1, 2, 3
```

### 25. `index` — 给每个元素贴个序号

**大白话**：每个数据前面加个编号（0, 1, 2...），方便你知道是第几个。

```java
Flux.just("a", "b", "c")
    .index()
    .subscribe(t -> System.out.println(t.getT1() + ": " + t.getT2()));
// 输出：0: a / 1: b / 2: c
```

---

## 第三档：场景用到再查

### 26. `zipWith` — 两条流配对

**大白话**：两条管子并排，左边来一个右边来一个，配成一对发出去。**两边都到位才能配对**。

```java
Flux.just(1, 2, 3)
    .zipWith(Flux.just("a", "b", "c"))
    .subscribe(t -> System.out.println(t.getT1() + "-" + t.getT2()));
// 输出：1-a / 2-b / 3-c
```

### 27. `mergeWith` — 两条流混着流

**大白话**：两条管子合流，里面的东西**乱序混着**出来。

```java
Flux.just(1, 2, 3)
    .mergeWith(Flux.just(10, 20))
    .subscribe(System.out::println);
// 输出（顺序可能乱）：1, 2, 3, 10, 20
```

### 28. `concatWith` — 一条流完事再接另一条

**大白话**：第一条流跑完，**再**开始第二条流。顺序严格。

```java
Flux.just(1, 2)
    .concatWith(Flux.just(3, 4))
    .subscribe(System.out::println);
// 输出：1, 2, 3, 4
```

### 29. `startWith` — 在前面插队

**大白话**：本来要流的数据前面，**强行插几个**先发出去。

```java
Flux.just(3, 4)
    .startWith(1, 2)
    .subscribe(System.out::println);
// 输出：1, 2, 3, 4
```

### 30. `then` / `thenMany` — 流完了再切到新流

**大白话**：当前流**所有元素都丢弃**，等它结束时切换到新流。常用作"先做完A事，再做B事"的衔接。

```java
Flux.range(1, 3)
    .thenMany(Flux.just("a", "b"))   // 前面的 1,2,3 被丢掉
    .subscribe(System.out::println);
// 输出：a, b
```

### 31. `cast` — 把元素强制转成另一种类型

```java
Flux<Number> nums = Flux.just(1, 2, 3);
Flux<Integer> ints = nums.cast(Integer.class);
ints.subscribe(System.out::println);
// 输出：1, 2, 3
```

### 32. `ofType` — 只留某种类型的元素

**大白话**：流水线里有多种物品，只让某种类型的过去。

```java
Flux<Number> mixed = Flux.just(1, 2.0, 3, 4.0);
mixed.ofType(Integer.class)
     .subscribe(System.out::println);
// 输出：1, 3（小数被扔了）
```

### 33. `doOnSubscribe` — 有人开水龙头时喊一声

**大白话**：流**刚开始**（被订阅时）的回调，比第一个数据还早。

```java
Flux.range(1, 3)
    .doOnSubscribe(s -> System.out.println("水龙头打开了"))
    .subscribe();
// 输出：水龙头打开了（在第一个数字出来之前）
```

### 34. `handle` — `map` + `filter` 二合一

**大白话**：进来的元素，你想发就发（`sink.next`），不想发就不调用（相当于过滤掉）。

```java
Flux.range(1, 10)
    .handle((n, sink) -> {
        if (n % 2 == 0) {
            sink.next(n * 10);   // 偶数才发，且 ×10
        }
        // 奇数不发 = 过滤
    })
    .subscribe(System.out::println);
// 输出：20, 40, 60, 80, 100
```

### 35. `scan` — 像 reduce，但中间每一步都发出来

**大白话**：累加过程中，**每一步**的中间结果都吐出来给你看一眼。

```java
Flux.range(1, 5)
    .scan(0, Integer::sum)
    .subscribe(System.out::println);
// 输出：0, 1, 3, 6, 10, 15（每一步的累加结果）
```

### 36. `buffer` — 按批装起来

**大白话**：每 N 个元素装一袋，发给下游的是一袋一袋的。

```java
Flux.range(1, 10)
    .buffer(3)
    .subscribe(System.out::println);
// 输出：[1, 2, 3] / [4, 5, 6] / [7, 8, 9] / [10]
```

### 37. `window` — 类似 buffer，但发的是"小流"

**大白话**：和 `buffer` 像，但每袋里装的不是 List，而是另一条小 Flux。

```java
Flux.range(1, 6)
    .window(2)
    .subscribe(window -> window.collectList().subscribe(System.out::println));
// 输出：[1, 2] / [3, 4] / [5, 6]
```

### 38. `groupBy` — 按条件分组

**大白话**：按某个标准给数据分组，输出的是多条小流。

```java
Flux.range(1, 10)
    .groupBy(n -> n % 2 == 0 ? "偶数" : "奇数")
    .subscribe(group ->
        group.collectList().subscribe(list -> System.out.println(group.key() + ": " + list)));
// 输出：偶数: [2, 4, 6, 8, 10] / 奇数: [1, 3, 5, 7, 9]
```

### 39. `sort` — 排序

**大白话**：等所有数据都到了，再排好序发出去（注意要等流结束才能排）。

```java
Flux.just(3, 1, 4, 1, 5, 9, 2, 6)
    .sort()
    .subscribe(System.out::println);
// 输出：1, 1, 2, 3, 4, 5, 6, 9
```

### 40. `next` — 只要第一个，剩下的都不要

```java
Flux.range(1, 100)
    .next()
    .subscribe(System.out::println);
// 输出：1（返回类型变成 Mono）
```

### 41. `last` — 只要最后一个

```java
Flux.range(1, 5)
    .last()
    .subscribe(System.out::println);
// 输出：5（要等流结束才能拿到）
```

### 42. `elementAt` — 取指定位置的元素

```java
Flux.range(10, 5)         // 10, 11, 12, 13, 14
    .elementAt(2)
    .subscribe(System.out::println);
// 输出：12
```

### 43. `hasElement` — 检查流里有没有数据

```java
Flux.<Integer>empty()
    .hasElement()
    .subscribe(b -> System.out.println(b));
// 输出：false
```

### 44. `count` — 数一数一共多少个

```java
Flux.range(1, 100)
    .count()
    .subscribe(System.out::println);
// 输出：100
```

---

## 第四档：阻塞消费（**仅测试/CLI 用，WebFlux 项目禁止！**）

### 45. `blockFirst` — 阻塞等第一个元素

**大白话**：让当前线程停下来等，直到拿到第一个数据。
**警告**：在 WebFlux 项目里调用会抛异常或拖死性能。

```java
Integer first = Flux.range(1, 10).blockFirst();
System.out.println(first);   // 输出：1
```

### 46. `blockLast` — 阻塞等最后一个元素

```java
Integer last = Flux.range(1, 10).blockLast();
System.out.println(last);    // 输出：10
```

### 47. `toStream` — 转成普通 Java Stream

```java
Flux.range(1, 5)
    .toStream()
    .forEach(System.out::println);
```

### 48. `toIterable` — 转成可迭代对象，用 for 遍历

```java
for (Integer n : Flux.range(1, 5).toIterable()) {
    System.out.println(n);
}
```

---

## 第五档：创建 Flux（业务代码很少手写，多由框架替你创建）

### 49. `just` — 从几个固定值创建

```java
Flux.just("a", "b", "c").subscribe(System.out::println);
```

### 50. `fromIterable` — 从 List 创建

```java
Flux.fromIterable(java.util.List.of(1, 2, 3)).subscribe(System.out::println);
```

### 51. `fromArray` — 从数组创建

```java
Flux.fromArray(new Integer[]{1, 2, 3}).subscribe(System.out::println);
```

### 52. `fromStream` — 从 Java Stream 创建

```java
Flux.fromStream(java.util.stream.Stream.of("x", "y")).subscribe(System.out::println);
```

### 53. `range` — 生成一段连续整数

```java
Flux.range(1, 5).subscribe(System.out::println);   // 1,2,3,4,5
```

### 54. `empty` — 创建一条空流（什么都没有，立即结束）

```java
Flux.empty().subscribe(System.out::println);   // 啥都不输出
```

### 55. `error` — 创建一条直接出错的流

```java
Flux.error(new IllegalStateException("炸了"))
    .subscribe(System.out::println, Throwable::printStackTrace);
```

### 56. `never` — 永远不结束的流（不发数据也不结束）

```java
Flux.never().subscribe();   // 永远等下去（除非你取消订阅）
```

### 57. `interval` — 每隔 N 时间发一个递增数字

```java
Flux.interval(java.time.Duration.ofSeconds(1))
    .take(3)
    .subscribe(n -> System.out.println(n));
// 输出：0 / 1 / 2（每秒一个）
```

### 58. `create` — 手动控制发射什么

**大白话**：你拿个"发射器"，想发啥发啥，想啥时候发就啥时候发。

```java
Flux.create(sink -> {
    for (int i = 0; i < 3; i++) sink.next(i);   // 发射 0, 1, 2
    sink.complete();                              // 然后关闭
}).subscribe(System.out::println);
```

### 59. `generate` — 一项一项生成（更严格）

**大白话**：每次只能生成一个，必须按顺序。

```java
Flux.generate(
    () -> 0,                            // 初始状态
    (state, sink) -> {
        sink.next(state);               // 发射当前值
        if (state == 3) sink.complete();
        return state + 1;               // 下一个状态
    }
).subscribe(System.out::println);       // 输出：0, 1, 2, 3
```

### 60. `defer` — 订阅时才创建

**大白话**：流不是一开始就造好的，每次有人订阅才临时造一个。

```java
java.util.function.Supplier<Flux<Integer>> supplier =
    () -> Flux.just(java.util.concurrent.ThreadLocalRandom.current().nextInt(100));
Flux.defer(supplier).subscribe(System.out::println);   // 每次订阅都生成新随机数
```

### 61. `push` — 类似 create，但只能单线程调用

```java
Flux.push(sink -> {
    sink.next("a");
    sink.next("b");
    sink.complete();
}).subscribe(System.out::println);
```

### 62. `using` — 用完自动关资源（像 try-with-resources）

```java
Flux.using(
    () -> new java.util.Scanner("1 2 3"),                                    // 创建资源
    scanner -> Flux.fromStream(scanner.tokens().map(Integer::parseInt)),     // 使用
    java.util.Scanner::close                                                  // 关闭
).subscribe(System.out::println);
// 输出：1, 2, 3（用完 Scanner 自动关闭）
```

---

## 第六档：高级/少见

### 63. `onBackpressureBuffer` — 下游来不及处理时，先攒着

**大白话**：上游发太快、下游处理不过来时，先放在缓冲区里。

```java
Flux.range(1, 1_000_000)
    .onBackpressureBuffer(1000)
    .subscribe(n -> { /* 慢慢处理 */ });
```

### 64. `onBackpressureDrop` — 来不及处理就丢弃

**大白话**：处理不过来，新来的直接扔。

```java
Flux.interval(java.time.Duration.ofMillis(1))
    .onBackpressureDrop()
    .subscribe(System.out::println);
```

### 65. `onBackpressureLatest` — 只保留最新的

**大白话**：处理不过来时，只留最新的一个，旧的扔掉。

```java
Flux.interval(java.time.Duration.ofMillis(1))
    .onBackpressureLatest()
    .subscribe(n -> {
        try { Thread.sleep(10); } catch (Exception ignored) {}
        System.out.println(n);
    });
```

### 66. `sample` — 周期性采样

**大白话**：每隔一段时间，从流里抽一个最新的。

```java
Flux.interval(java.time.Duration.ofMillis(100))
    .sample(java.time.Duration.ofSeconds(1))
    .take(3)
    .subscribe(System.out::println);
```

### 67. `throttleFirst` — 每段时间取第一个

**大白话**：每过 N 时间，只拿这段时间里第一个数据。

```java
Flux.interval(java.time.Duration.ofMillis(100))
    .throttleFirst(java.time.Duration.ofSeconds(1))
    .take(3)
    .subscribe(System.out::println);
```

### 68. `expand` — 递归展开

**大白话**：每个元素**自己生成下一个**，像递归。

```java
Flux.just(5)
    .expand(n -> n <= 0 ? Flux.empty() : Flux.just(n - 1))
    .subscribe(System.out::println);
// 输出：5, 4, 3, 2, 1, 0
```

### 69. `repeat` — 流完了从头再来 N 次

```java
Flux.just("a", "b")
    .repeat(2)
    .subscribe(System.out::println);
// 输出：a, b, a, b, a, b（原 + 重复 2 次 = 3 轮）
```

### 70. `cache` — 把第一次跑的结果缓存起来

**大白话**：第一次订阅时正常跑，跑完结果存起来；之后再来订阅，直接拿缓存，**不会重新跑**。

```java
Flux<Long> cached = Flux.range(1, 3)
    .doOnSubscribe(s -> System.out.println("水龙头打开了"))
    .cache();
cached.subscribe(System.out::println);   // 打印"水龙头打开了" + 1,2,3
cached.subscribe(System.out::println);   // 直接 1,2,3（不会再打开水龙头）
```

### 71. `elapsed` — 给每个数据打上"距离上一个的时间戳"

```java
Flux.range(1, 3)
    .delayElements(java.time.Duration.ofMillis(100))
    .elapsed()
    .subscribe(t -> System.out.println(t.getT1() + "ms - " + t.getT2()));
// 输出：100ms - 1 / 100ms - 2 / 100ms - 3
```

### 72. `log` — 自动打印每个信号（调试神器）

**大白话**：在流上装个监控，每个动作都打印到日志里。

```java
Flux.range(1, 3)
    .log()
    .subscribe();
// 控制台会输出：onSubscribe, onNext(1), onNext(2), onNext(3), request, onComplete
```

### 73. `checkpoint` — 出错时附上调用位置

**大白话**：调试时用，错能定位到具体哪段代码。

```java
Flux.range(1, 3)
    .map(n -> { throw new RuntimeException("oops"); })
    .checkpoint("after-map")
    .subscribe(System.out::println, Throwable::printStackTrace);
// 报错时会带上 "after-map" 标记，方便定位
```

---

## 总览表

| 档位 | 用途 | 方法数 |
|---|---|---|
| 第一档 | 天天用 | 10 |
| 第二档 | 常用 | 15 |
| 第三档 | 场景用到再查 | 19 |
| 第四档 | 阻塞消费（测试用，生产禁用） | 4 |
| 第五档 | 创建型 | 14 |
| 第六档 | 高级/少见 | 11 |
| **合计** | | **73** |

---

## 记忆建议

1. **第一档（1-10）必须背熟**，覆盖 90% 日常场景。重点理解 `map`/`filter`/`flatMap` 三个变换操作，以及 `do*` 系列和 `onError*` 系列。
2. **第二档（11-25）理解为主**，遇到场景能想起"有这个方法"就行，回头查文档。
3. **第三档（26-44）按需查**，看到别人代码能看懂即可。
4. **第四档（45-48）只记住"WebFlux 项目禁用"** 这一条。
5. **第五档（49-62）创建型**：实际项目里框架（如 Spring AI）会替你创建 Flux，你很少手写。
6. **第六档（63-73）背压/调试**：等遇到性能问题、调试问题再回来看。

## 一个最关键的理解

记住下面这 5 句话，你就掌握了 80% 的 Flux：

1. **`Flux` 是流水线**，数据从源头一段一段流出来。
2. **方法链 = 设计流水线**，不调用 `subscribe` 不会真的跑。
3. **`do*` 开头的方法只看不动**，其他大部分是变换。
4. **`map` 同步一对一、`flatMap` 异步一对多**，记牢这两个的区别。
5. **出错处理用 `onError*` 系列**，不要让异常偷偷冒到顶层。
