# 02 · Spring Boot 入门

> 阶段：0 地基 · 难度：⭐ · 预计：1 周（每天 2 小时）
> 前置：[01 Java 核心速成](01-Java核心速成.md)
> 产出：能用 Spring Boot 搭一个 REST API，理解依赖注入和自动配置

---

## 你将学会

- 用 Spring Boot 搭建一个 Web 应用（5 分钟跑起来）
- 写 REST API 接口（`@RestController` / `@GetMapping`）
- 依赖注入（`@Autowired` / `@Bean`）—— Spring AI 全靠它
- 配置文件（`application.yml`）怎么用
- Maven 项目结构（`pom.xml`）

---

## 为什么需要这个

后面的所有 AI 项目都是 Spring Boot 应用。Spring AI 就是 Spring Boot 的一个插件（starter）。你不懂 Spring Boot，就没法用 Spring AI。

**好消息**：Spring Boot 的"约定优于配置"设计让你只需要极少代码就能跑起来。

---

## 知识讲解

### 1. Spring Boot 是什么

一句话：**Spring Boot 是一个帮你快速搭建 Java Web 应用的框架**。它替你处理了 Tomcat 服务器、JSON 序列化、依赖管理等所有杂事，你只写业务逻辑。

### 2. Maven 项目结构

```
demo01/
├── pom.xml                    # 依赖管理（相当于 Python 的 requirements.txt）
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   └── demo/
│   │   │       └── Application.java   # 启动类
│   │   └── resources/
│   │       └── application.yml         # 配置文件
│   └── test/
│       └── java/
```

### 3. pom.xml 核心概念

```xml
<project>
    <!-- 坐标：这个项目叫什么 -->
    <groupId>com.demo</groupId>
    <artifactId>demo01</artifactId>
    <version>0.0.1-SNAPSHOT</version>

    <!-- 继承 Spring Boot 父项目 -->
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.5.10</version>
    </parent>

    <dependencies>
        <!-- Spring Boot Web：引入这个就有了一个 Web 服务器 -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
    </dependencies>
</project>
```

> 引入一个 `starter` = 引入一组依赖 + 自动配置。`spring-boot-starter-web` 自带 Tomcat + Spring MVC + JSON 序列化。

### 4. 依赖注入（Dependency Injection）

这是 Spring 的核心。不用手动 `new` 对象，让 Spring 容器帮你创建和注入：

```java
// ❌ 不用 Spring：手动创建依赖
public class ChatService {
    private DeepSeekLLM model = new DeepSeekLLM();  // 硬编码，换模型要改代码
}

// ✅ 用 Spring：声明依赖，容器帮你注入
@Service
public class ChatService {
    private final ChatModel model;  // 面向接口

    // 构造器注入：Spring 自动找到 ChatModel 的实现并注入
    public ChatService(ChatModel model) {
        this.model = model;
    }
}
```

> 在 Spring AI 中，`ChatModel` 是 Spring 自动创建的 Bean（因为你配置了 API Key），你只需要注入它。

### 5. REST API

```java
@RestController         // 告诉 Spring：这是一个 REST 控制器
@RequestMapping("/api")  // 所有接口的前缀
public class HelloController {

    @GetMapping("/hello")           // GET /api/hello?name=小明
    public String hello(@RequestParam(defaultValue = "世界") String name) {
        return "你好，" + name + "！";
    }

    @PostMapping("/chat")           // POST /api/chat
    public String chat(@RequestBody String userMessage) {
        return "你说的是：" + userMessage;
    }
}
```

### 6. 配置文件

```yaml
# application.yml
server:
  port: 8080         # 应用端口

app:
  name: 我的AI助手
  model: deepseek-chat
```

在代码中读取配置：

```java
@Value("${app.name}")        // 注入配置值
private String appName;

@Value("${app.model}")
private String modelName;
```

---

## 动手实践

### Step 1：创建项目

用 Spring Initializr 创建项目，或直接在本仓库的 `src/main/java/demo/demo01/` 下操作。

### Step 2：写启动类

```java
package demo.demo01;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication   // 这个注解 = 自动配置 + 组件扫描 + 启动入口
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
```

### Step 3：写一个 REST 接口

```java
package demo.demo01.controller;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class HelloController {

    @GetMapping("/hello")
    public String hello(@RequestParam(defaultValue = "世界") String name) {
        return "你好，" + name + "！";
    }

    // 返回 JSON（用 record）
    @GetMapping("/info")
    public AppInfo info() {
        return new AppInfo("AI Demo", "0.0.1", "running");
    }

    public record AppInfo(String name, String version, String status) {}
}
```

### Step 4：运行

```bash
# 在项目根目录
mvn spring-boot:run

# 测试接口
curl http://localhost:8080/api/hello?name=小明
# 输出：你好，小明！

curl http://localhost:8080/api/info
# 输出：{"name":"AI Demo","version":"0.0.1","status":"running"}
```

### Step 5：加一个依赖注入的例子

```java
package demo.demo01.service;

import org.springframework.stereotype.Service;

// 这是一个 Service Bean，Spring 会管理它
@Service
public class GreetingService {

    public String greet(String name) {
        return "来自 Service 的问候：你好 " + name;
    }
}
```

```java
// 在 Controller 中注入 Service
@RestController
@RequestMapping("/api")
public class HelloController {

    private final GreetingService greetingService;

    // 构造器注入
    public HelloController(GreetingService greetingService) {
        this.greetingService = greetingService;
    }

    @GetMapping("/greet")
    public String greet(@RequestParam String name) {
        return greetingService.greet(name);
    }
}
```

---

## Spring 核心注解速查表

| 注解 | 放在哪 | 作用 |
|------|--------|------|
| `@SpringBootApplication` | 启动类 | 自动配置 + 组件扫描 |
| `@RestController` | Controller 类 | 声明 REST 接口（= `@Controller` + `@ResponseBody`） |
| `@RequestMapping("/x")` | Controller 类/方法 | URL 路径前缀 |
| `@GetMapping("/y")` | 方法 | 处理 GET 请求 |
| `@PostMapping("/y")` | 方法 | 处理 POST 请求 |
| `@RequestParam` | 方法参数 | 从 URL query string 取值 |
| `@RequestBody` | 方法参数 | 从请求 body 取 JSON → 对象 |
| `@PathVariable` | 方法参数 | 从 URL 路径取值（`/user/{id}` → `id`） |
| `@Service` | Service 类 | 声明为 Spring Bean |
| `@Configuration` | 配置类 | 声明配置类 |
| `@Bean` | 配置类中的方法 | 手动创建 Bean（当第三方类不能加 `@Service` 时用） |
| `@Value("${key}")` | 字段 | 从 yml 注入配置值 |
| `@Autowired` | 字段/构造器 | 自动注入（构造器注入时可省略） |

> **构造器注入 vs `@Autowired` 字段注入**：推荐用构造器注入（字段加 `final`），因为不可变、可测试、Spring 官方推荐。

---

## 常见坑

- ❌ **忘记 `@Service` / `@RestController` 注解** → Spring 不知道这个类的存在，注入失败
- ❌ **端口被占用** → `application.yml` 改 `server.port`
- ❌ **包名不对** → Spring 默认只扫描启动类所在包及子包，Controller/Service 必须在启动类同包或子包下
- ❌ **循环依赖** → A 依赖 B，B 依赖 A。用 `@Lazy` 或重构解决
- ❌ **`@Bean` vs `@Service` 搞混** → `@Service` 用在你自己的类上；`@Bean` 用在 `@Configuration` 类的方法里，用来创建你无法修改源码的第三方类（如 `ChatClient`）
- ❌ **返回值不是 JSON** → `@RestController` 自动把返回值序列化为 JSON。返回 String 就是纯文本，返回 record/Map/对象就是 JSON

---

## Spring Boot 启动报错排查表

| 报错 | 原因 | 解决 |
|------|------|------|
| `Port 8080 was already in use` | 端口被占 | 换端口 `server.port: 8081`，或杀进程 |
| `NoSuchBeanDefinitionException` | Bean 没找到 | 检查类是否有 `@Service`/`@Component`，包名是否在扫描范围内 |
| `UnsatisfiedDependencyException` | 依赖注入失败 | 检查构造器参数是否都是 Spring 管理的 Bean |
| `Cannot resolve configuration property` | yml 配置项不对 | 检查拼写、缩进（yml 对缩进敏感） |

---

## 验收检查

- [ ] 能跑起一个 Spring Boot 应用（看到 "Started Application" 日志）
- [ ] 能写一个 GET 接口并返回字符串
- [ ] 能写一个返回 JSON 的接口
- [ ] 能注入一个 Service 并调用
- [ ] 能在 `application.yml` 中配置端口
- [ ] 理解 `@SpringBootApplication` / `@RestController` / `@Service` 三个注解的作用

---

## 下一步

→ 下一篇：[03 LLM 基础认知](03-LLM基础认知.md) —— 理解大语言模型是什么，用 curl 第一次调通 LLM
→ 概念卡壳？查 `理论字典/LLM基础.md`

---

## 随堂练习：简易 REST 计算器（45 分钟）

用 Spring Boot 搭一个 REST 计算器，练习 Controller + record + 依赖注入。

**接口设计**：
```
GET  /calc/add?a=1&b=2    → {"operation":"add","result":3}
GET  /calc/div?a=10&b=0   → {"error":"除数不能为零"}
```

**提示**：
```java
public record CalcResult(String operation, double result) {}
public record ErrorResponse(String error) {}

@GetMapping("/div")
public Object div(@RequestParam double a, @RequestParam double b) {
    if (b == 0) return new ErrorResponse("除数不能为零");
    return new CalcResult("div", a / b);
}
// 自己实现 add, sub, mul
```

**扩展**：加 `/calc/history` 返回历史记录；把计算逻辑抽到 `CalculatorService`（练习依赖注入）。
