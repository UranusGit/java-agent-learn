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

## 常见坑

- ❌ **忘记 `@Service` / `@RestController` 注解** → Spring 不知道这个类的存在，注入失败
- ❌ **端口被占用** → `application.yml` 改 `server.port`
- ❌ **包名不对** → Spring 默认只扫描启动类所在包及子包，Controller/Service 必须在启动类同包或子包下
- ❌ **循环依赖** → A 依赖 B，B 依赖 A。用 `@Lazy` 或重构解决

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
