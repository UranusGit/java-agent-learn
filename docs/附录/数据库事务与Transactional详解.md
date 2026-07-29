# 数据库事务与 @Transactional 详解（Spring Boot 实战）

> **配套文档**：本系列教程多处涉及"事务""@Transactional""幂等"——比如 [Redis分布式锁](./Redis分布式锁实战.md) 末尾强调"锁只保证互斥，不保证业务正确，要靠业务幂等 + 存储层防护"。事务就是"存储层防护"的核心。但事务本身的概念（ACID、传播行为、回滚规则、失效场景）一直没系统讲。本篇补上这个地基。
>
> **难度假设**：你写过 `@Transactional` 注解，但说不清它到底怎么生效、什么时候会失效、传播行为 `REQUIRED` 和 `REQUIRES_NEW` 啥区别。

---

## 第 1 章：事务是什么——从一个转账故事开始

### 1.1 没有事务会怎样

经典场景：A 给 B 转 100 块。

```sql
UPDATE account SET balance = balance - 100 WHERE id = 'A';   -- 第1步：A 扣钱
-- 💥 这里程序崩了
UPDATE account SET balance = balance + 100 WHERE id = 'B';   -- 第2步：B 加钱（没执行到）
```

**结果**：A 钱扣了，B 没收到——100 块凭空消失。这就是没有事务的灾难。

### 1.2 事务：把多个操作"打包成一个不可分割的整体"

**事务（Transaction）** 把一组数据库操作绑在一起，保证它们**要么全部成功，要么全部失败回滚**——不存在"做了一半"的中间状态。

```
转账事务：
  A 扣 100  ✅
  B 加 100  ✅   → 全成功 → 提交（commit），永久生效
  
  或：
  A 扣 100  ✅
  B 加 100  💥   → 有失败 → 回滚（rollback），A 的扣款也撤销，回到初始状态
```

> 一句话：**事务 = 要么全做、要么全不做的一组操作。**

### 1.3 ACID——事务的四大特性（面试必背）

| 字母 | 特性 | 含义 | 通俗解释 |
|------|------|------|---------|
| **A** | 原子性 Atomicity | 全做或全不做 | 转账要么成、要么全退，不能半截 |
| **C** | 一致性 Consistency | 事务前后数据合法 | 转账前后总金额不变（钱不凭空产生/消失） |
| **I** | 隔离性 Isolation | 并发事务互不干扰 | A 转账和 B 转账同时进行，不互相算错账 |
| **O** | 持久性 Durability | 提交后永久保存 | 提交了，断电也不丢 |

**最常考的是原子性（A）和隔离性（I）**。原子性靠"回滚"实现，隔离性靠"锁 + MVCC"实现（后面讲）。

---

## 第 2 章：Spring 里怎么用事务——@Transactional

### 2.1 最简单的用法

```java
@Service
public class TransferService {
    private final AccountDao accountDao;

    @Transactional                       // ← 加这个注解，方法就是一个事务
    public void transfer(String from, String to, int amount) {
        accountDao.decrease(from, amount);   // A 扣钱
        accountDao.increase(to, amount);     // B 加钱
        // 方法正常结束 → 提交；抛异常 → 回滚
    }
}
```

加了 `@Transactional`，Spring 会**自动**：方法开始时开事务、正常结束提交、抛异常回滚。**你不用手写 commit/rollback。**

> **背后原理（了解）**：Spring 用**动态代理**包了一层。调用 `transferService.transfer(...)` 时，实际先走代理：代理开启事务 → 调用真实方法 → 根据结果提交/回滚。这层代理正是后面"失效场景"的根源。

### 2.2 注解可以加在类上

```java
@Transactional            // 加在类上 = 类里所有 public 方法都加事务
@Service
public class OrderService { ... }
```

一般**加在方法上**更精确（只给需要事务的方法加）。

### 2.3 前提：必须用 Spring 管理的连接

`@Transactional` 之所以能让方法内多条 SQL 在一个事务里，是因为它们**共用同一个数据库连接**。Spring 通过事务管理器绑定一个连接到当前线程，方法内的 DAO 都拿这个连接。

> **坑**：如果你方法里自己 `new` 了一个 Connection、或用了不参与 Spring 事务管理的连接，那部分操作**不在事务里**，回滚波及不到它。用 `JdbcTemplate`/`JPA`/`MyBatis` 等标准方式没问题。

---

## 第 3 章：回滚规则——什么情况下回滚

### 3.1 默认：只对 RuntimeException 和 Error 回滚

```java
@Transactional
public void doSomething() throws Exception {
    accountDao.update(...);
    if (somethingWrong) {
        throw new Exception("业务失败");   // ← 受检异常，默认【不回滚】！
    }
}
```

**这是最坑的地方**：Spring 默认**只对 `RuntimeException`（及其子类）和 `Error` 回滚**，对受检异常（`Exception` 的直接子类，即 `checked exception`）**不回滚**。

原因：Spring 认为受检异常通常是"可预期的业务情况"（比如余额不足），不该回滚；运行时异常才是"真出错了"。但这个默认在很多业务里是反直觉的。

### 3.2 强制让所有异常都回滚

```java
@Transactional(rollbackFor = Exception.class)   // ← 明确指定：任何异常都回滚
public void doSomething() throws Exception { ... }
```

**强烈建议**：业务代码里**习惯性写 `@Transactional(rollbackFor = Exception.class)`**，避免受检异常不回滚的坑。这是生产代码的常见规范。

### 3.3 不想回滚某个异常

```java
@Transactional(noRollbackFor = SomeException.class)
```

少数场景：某个异常代表"可接受的业务中断"，不该触发回滚。

---

## 第 4 章：传播行为——嵌套事务怎么处理（重点难点）

当一个 `@Transactional` 方法**调用另一个** `@Transactional` 方法时，两个事务怎么合并？这就是**传播行为（Propagation）**。

### 4.1 七种传播行为，只需记住三种

| 传播行为 | 含义 | 何时用 |
|---------|------|--------|
| **`REQUIRED`**（默认） | 有事务就加入，没有就新建 | 90% 场景的默认选择 |
| **`REQUIRES_NEW`** | 不管有没有，**总是新开一个独立事务**，挂起当前事务 | 日志记录必须独立成功（主事务回滚不影响日志） |
| **`NESTED`** | 有事务就建一个**嵌套子事务**（基于保存点） | 部分失败可单独回滚，不影响外层 |

其余 `SUPPORTS`/`MANDATORY`/`NEVER`/`NOT_SUPPORTED` 较少用，查文档即可。

### 4.2 例子：REQUIRED（默认，最常用）

```java
@Service
public class A {
    @Transactional                       // 开了事务 T1
    public void doA() {
        dao.update1();
        b.doB();                          // B 加入 T1（不另开）
    }
}

@Service
public class B {
    @Transactional                       // 默认 REQUIRED：已有 T1，直接加入
    public void doB() {
        dao.update2();
        throw new RuntimeException("炸了");  // B 抛异常 → T1 回滚 → update1 和 update2 都撤销
    }
}
```

`REQUIRED` 下，`doA` 和 `doB` **在同一个事务**，一个炸全回滚。

### 4.3 例子：REQUIRES_NEW（独立事务）

```java
@Service
public class A {
    @Transactional                       // 事务 T1
    public void doA() {
        dao.update1();
        b.doB();                          // B 另开 T2，T1 暂时挂起
        // doB 结束，T2 已提交（日志已存），T1 恢复继续
    }
}

@Service
public class B {
    @Transactional(propagation = Propagation.REQUIRES_NEW)   // 总是新开 T2
    public void doB() {                  // 即使 doA 后面失败回滚 T1，B 的操作（T2）已提交，不撤销
        logDao.insert("操作日志");
    }
}
```

**典型用途**：**日志/审计记录**——即使主业务失败回滚，日志也要保留。用 `REQUIRES_NEW` 让日志独立提交。

### 4.4 例子：NESTED（嵌套，部分回滚）

`doB` 内部失败可单独回滚（回到保存点），不影响外层 `doA`。较少用，了解即可。

---

## 第 5 章：事务失效的七大场景（面试+实战高频）

这是最常被坑的部分。**`@Transactional` 不生效，往往就是踩了下面某一条。**

### 失效 1：自调用（同类内部方法调用）——最高频！

```java
@Service
public class OrderService {

    public void outer() {
        this.inner();          // ❌ 直接 this 调用，绕过了代理！事务不生效
    }

    @Transactional
    public void inner() { ... }
}
```

**原因**：事务靠**代理对象**实现。`this.inner()` 调的是原始对象（不是代理），代理的"开事务/回滚"逻辑被跳过了。

**解决**：
- 把 `inner` 拆到**另一个类**，通过注入的 Bean 调用（走代理）。
- 或注入自己：`@Autowired OrderService self;` 然后 `self.inner()`。
- 或用 `AopContext.currentProxy()` 拿代理调（要开启 `@EnableAspectJAutoProxy(exposeProxy = true)`）。

### 失效 2：方法不是 public

`@Transactional` 默认**只对 public 方法生效**。`protected`/`private`/包级方法加注解无效（代理不拦截）。

### 失效 3：异常被 catch 吞掉了

```java
@Transactional
public void doSomething() {
    try {
        dao.update1();
        dao.update2();
    } catch (Exception e) {
        log.error("出错了", e);   // ❌ 异常被吞了，Spring 感知不到 → 不回滚！
    }
}
```

**原因**：Spring 靠方法**抛出**异常来触发回滚。你 catch 住没抛，它以为成功了。

**解决**：catch 后**重新 throw**，或手动 `TransactionAspectSupport.currentTransactionStatus().setRollbackOnly()`。

### 失效 4：异常类型不对（受检异常不回滚）

见第 3 章。**解决**：`@Transactional(rollbackFor = Exception.class)`。

### 失效 5：数据库引擎不支持事务

比如 MySQL 的 **MyISAM 引擎不支持事务**。必须用 **InnoDB** 才有事务。建表时确认引擎是 InnoDB。

### 失效 6：没被 Spring 管理

类上没加 `@Service`/`@Component`，或 `new` 出来的对象，不走代理，事务无效。

### 失效 7：传播行为配错

比如配了 `NOT_SUPPORTED`（以非事务方式执行），自然没事务。检查 `propagation` 参数。

---

## 第 6 章：隔离级别——并发事务的互相干扰

### 6.1 并发会产生什么问题

多个事务同时操作同一份数据，会出三种经典问题：

| 问题 | 含义 | 例子 |
|------|------|------|
| **脏读** | 读到了别的事务**未提交**的数据（对方回滚了，你读到的是脏的） | A 改了余额没提交，B 读到改后值，A 回滚——B 读到的是不存在的值 |
| **不可重复读** | 同一事务内**两次读同一行**结果不同（被别的事务**修改/删除**了） | 事务内第一次查余额 100，期间别人改成 50，第二次查变 50 |
| **幻读** | 同一事务内两次**范围查询**结果条数不同（被别的事务**插入**了新行） | 第一次查"所有余额>100的有3人"，期间别人插入1条，第二次变4人 |

### 6.2 四个隔离级别（从低到高）

| 隔离级别 | 脏读 | 不可重复读 | 幻读 | 性能 |
|---------|------|----------|------|------|
| READ UNCOMMITTED 读未提交 | ❌有 | ❌有 | ❌有 | 最高 |
| READ COMMITTED 读已提交 | ✅无 | ❌有 | ❌有 | 高 |
| **REPEATABLE READ 可重复读**（MySQL 默认） | ✅无 | ✅无 | ❌有* | 中 |
| SERIALIZABLE 串行化 | ✅无 | ✅无 | ✅无 | 最低 |

> *MySQL 的 InnoDB 在"可重复读"级别下，**靠 MVCC + 间隙锁基本也消除了幻读**，这是它比标准 SQL 强的地方。

### 6.3 Spring 里设置

```java
@Transactional(isolation = Isolation.READ_COMMITTED)
```

**多数情况用默认（数据库默认级别）即可**，别乱调——隔离级别越高，并发性能越低。

### 6.4 MVCC 是什么（顺带了解）

**MVCC（多版本并发控制）**：读操作不阻塞写、写不阻塞读。每行数据保留多个版本，事务根据自己的"快照"读对应版本。MySQL InnoDB 靠它实现高并发下"可重复读"而不用大量加锁。

---

## 第 7 章：事务 vs 分布式锁 vs 幂等（串起附录知识）

这是把几篇附录连起来理解的关键。

### 7.1 事务保证什么、不保证什么

- ✅ 保证：**单次操作**（一个事务内）的原子性——要么全成要么全败。
- ❌ 不保证：**多次操作之间**的并发问题（比如"同一用户连点两次下单"）。

### 7.2 例子：重复下单

```java
@Transactional
public void createOrder(Long userId) {
    // 检查是否已下单
    if (orderDao.exists(userId)) return;
    orderDao.insert(userId);     // 插入订单
}
```

用户狂点两次，**两个请求同时**进入方法，都查到"没下过单"，于是**插入了两条订单**。事务拦不住——因为这是两个独立事务，各自查的时候对方还没提交。

### 7.3 三层防护各管什么

| 防护手段 | 防的是什么 | 例子 |
|---------|-----------|------|
| **事务**（@Transactional） | 单次操作原子性 | 转账扣款+加款要一起成/败 |
| **数据库唯一约束** | 并发插入重复 | `UNIQUE(userId, orderNo)` 让重复插入直接报错 |
| **分布式锁** | 跨实例/跨节点的互斥 | 多台机器只有一个能执行临界区（见分布式锁附录） |
| **业务幂等** | 重复请求/重复消费不出错 | 同一消息被 Kafka 投递多次，只生效一次（见 Kafka 附录） |

> **回扣分布式锁附录**：那篇结尾说"分布式锁保证互斥，不保证业务正确，要靠业务幂等 + 存储层防护"。**事务 + 唯一约束就是"存储层防护"**。锁挡住了"同时进入"，但万一锁失效/重复投递，最后还得靠数据库唯一约束和幂等兜底。

---

## 第 8 章：常见坑总结

### 坑 1：自调用导致事务不生效

见失效 1。**最常见**。解决：跨 Bean 调用或注入自身。

### 坑 2：受检异常不回滚

见第 3 章。**解决**：`rollbackFor = Exception.class`。

### 坑 3：catch 吞异常导致不回滚

见失效 3。**解决**：catch 后 rethrow。

### 坑 4：长事务

一个事务跨了好几秒（里面调了慢的外部接口），**长时间占用数据库连接和锁**，拖垮并发。
**解决**：事务里**只放数据库操作**，把 RPC/HTTP 调用、耗时计算挪到事务外。事务"小而快"。

### 坑 5：事务方法里嵌套大量远程调用

同上。远程调用慢且可能失败，放进事务会拉长事务、增加回滚面。**解决**：远程调用放事务外，或用补偿/最终一致性方案。

### 坑 6：以为加 @Transactional 就一定一致了

事务只管单机单库的一次操作。**跨库、跨服务的一致性**要靠分布式事务（XA、Saga、本地消息表）或最终一致性——那是另一个大话题，多数业务用"本地事务 + 幂等 + 重试"足够。

---

## 总结

- **事务**：把一组操作打包成"全成或全败"的整体，靠 ACID 保障。
- **`@Transactional`**：Spring 自动管理事务，靠**动态代理**实现——这也是失效场景的根源。
- **回滚**：默认只回滚 RuntimeException；生产建议 `rollbackFor = Exception.class`。
- **传播行为**：默认 `REQUIRED`（加入现有）；日志要独立用 `REQUIRES_NEW`。
- **七大失效**：自调用、非 public、吞异常、异常类型、引擎、未托管、传播配置——**自调用和吞异常最高频**。
- **隔离级别**：MySQL 默认"可重复读"，靠 MVCC 兼顾并发与一致性。
- **和锁/幂等的关系**：事务管"单次原子性"，唯一约束管"并发重复"，锁管"跨节点互斥"，幂等管"重复消费"——各司其职，组合使用才稳。

回头看 [Redis分布式锁实战](./Redis分布式锁实战.md) 末尾"存储层防护"的说法，以及教程里各处"保证一致性"的要求，你就明白事务和唯一约束是那个兜底了。
