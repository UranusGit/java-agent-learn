# Kafka 可视化工具推荐

> **这份文档是什么**：Kafka 里的消息是字节流，肉眼看不见、也摸不着——Topic 建没建成、分区分布如何、消费者组积压了多少消息，全靠命令行 `kafka-topics` / `kafka-consumer-groups` 一个一个敲。可视化工具把这些全部变成「网页或桌面界面上的图形」，让你用鼠标就能看 Topic、分区、offset、消费组、消息内容，是开发排障和运维观察的利器。
>
> **适合谁**：刚开始接触 Kafka、想找一款图形界面工具来「看见」消息的新手。
>
> **前置知识**：建议先了解 Topic / 分区 / offset / 消费组 这几个基本概念（见 [Kafka 消息队列实战专题/01](../Kafka消息队列实战专题/01-Kafka消息队列从入门到架构师.md) 的第 2 章），否则工具界面上的词会看不懂。本文只讲「选哪个工具、怎么跑起来」，不重复讲概念。

---

## 📋 目录

- [工具对比总表](#工具对比总表)
- [怎么选](#怎么选)
- [工具 1：Redpanda Console（Web 颜值天花板）](#工具-1redpanda-consoleweb-颜值天花板)
- [工具 2：Kafka UI（功能最全的开源首选）](#工具-2kafka-uiprovectus--kafka-ui功能最全的开源首选)
- [工具 3：Conduktor（桌面 + Web 双形态）](#工具-3conduktor桌面--web-双形态)
- [工具 4：Kafdrop（最轻量）](#工具-4kafdrop最轻量)
- [工具 5：AKHQ（原 KafkaHQ）](#工具-5akhq原-kafkahq)
- [工具 6：Offset Explorer（原 Kafka Tool）](#工具-6offset-explorer原-kafka-tool)
- [工具 7：Kafka-King（中文现代桌面客户端）](#工具-7kafka-king中文现代桌面客户端)
- [工具 8：kafka-map（中文 Web 面板）](#工具-8kafka-map中文-web-面板)
- [工具 9：kafka-console-ui（国内轻量 Web 款）](#工具-9kafka-console-ui国内轻量-web-款)
- [工具 10：KafkaLens（极简美学，收费）](#工具-10kafkalens极简美学收费)
- [工具 11：Kafka Assistant（国产可视化）](#工具-11kafka-assistant国产可视化)
- [工具 12：Confluent Control Center（企业级）](#工具-12confluent-control-center企业级)
- [工具 13：IntelliJ IDEA 的 Kafka 插件](#工具-13intellij-idea-的-kafka-插件)
- [总结](#总结)
- [常见坑](#常见坑)
- [延伸阅读](#延伸阅读)

---

## 工具对比总表

> **说明**：以下 13 个工具按「类型」分组列全，**没时间细看可以直接跳到「怎么选」**。

| 工具名 | 类型 | 界面风格 | 功能亮点 | 适合谁 | 上手难度 | 免费 / 许可 |
|--------|------|---------|---------|--------|:-------:|-------------|
| **Redpanda Console**（原 Kowl） | Web | 极简现代、深色主题惊艳 | 消息 JSON 语法高亮、Topic/分区/消费组延迟可视化、数据流拓扑 | 追求颜值、想最快看到效果 | ⭐ 最简单 | 个人免费（source-available） |
| **Kafka UI**（provectus / kafka-ui） | Web | 现代简洁、深浅主题一键切换 | 功能最全：Topic 增删改查、消息 SQL 查询、Kafka Connect、Schema Registry、ACL | 综合需求、团队/生产 | ⭐ 简单 | **Apache 2.0 纯开源** |
| **Conduktor** | 桌面 + Web | 精致、接近专业 IDE 质感 | 消息编辑/重发、拖拽式数据流拓扑、SQL 查询消息、消费组 lag 监控 | 桌面党 + 颜值党 | ⭐⭐ 简单 | 免费 Community（限 1 用户） |
| **Kafdrop** | Web | 简洁、朴素 | 最轻量，纯消息浏览 | 快速临时查看消息 | ⭐ 最简单 | Apache 2.0 |
| **AKHQ**（原 KafkaHQ） | Web | 清爽工具风 | 消息搜索、SQL 查询、Schema、审计日志 | 功能向用户 | ⭐⭐ 简单 | 开源免费 |
| **Offset Explorer**（原 Kafka Tool） | 桌面 | 老式树形 + 表格 | 桌面端功能最全：手动设置 offset、消费测试、Topic 配置修改 | 桌面党、功能向、能忍朴素界面 | ⭐⭐ 简单 | 免费版 + 付费 Pro |
| **Kafka-King** | 桌面 | 现代 + **中文界面** | 跨平台 GUI 客户端，中文友好 | 想要中文桌面 App | ⭐⭐ 简单 | 开源免费 |
| **kafka-map** | Web | 美观简洁 + **中文界面** | 中文 Web 管理 Topic/消费组 | 想要中文 Web | ⭐ 简单 | 开源免费 |
| **kafka-console-ui** | Web | 简洁轻量 | 国内开发者维护，快速上手 | 轻量中文 Web | ⭐⭐ 简单 | 开源免费 |
| **KafkaLens** | 桌面 | 极简美学、大量留白 | 数据流拓扑拖拽可视化 | 纯美学党、预算充足 | ⭐⭐ 简单 | 商业收费（按节点） |
| **Kafka Assistant** | 桌面 | 规整 + **中文界面** | 国产可视化 + 监控 | 想要中文桌面 + 监控 | ⭐⭐ 简单 | 免费版 |
| **Confluent Control Center** | Web（企业） | 精致商务深蓝 | 吞吐量、延迟实时监控仪表盘 | 企业 Confluent Platform 用户 | ⭐⭐⭐ 中等 | 需付费 License |
| **IntelliJ 的 Kafka 插件** | IDE 插件 | 与 IDE 完全统一 | 侧边栏直接浏览 Topic/消息/消费组 | 用 JetBrains 系 IDE 的开发者 | ⭐ 最简单 | 免费 |

---

## 怎么选

> **提示**：按你的使用场景对号入座，比一个个研究快得多。

### 场景一：本地调试 / 开发排障（最常用）

看消息内容、查消费组 offset、临时验证 Topic 是否建成——**Web 版最合适，两条 Docker 命令就能起**：

- **首选 Kafka UI**：功能最全，连查带管都够，Apache 2.0 纯开源无顾虑。
- **想要更漂亮**：选 Redpanda Console，颜值最高。
- **只想快速瞄一眼消息**：Kafdrop，最轻量。

### 场景二：生产运维 / 团队使用

要求功能全、稳定、可能要管多个集群和权限：

- **开源首选 Kafka UI**：多集群、ACL、Schema Registry、Connect 全覆盖。
- **企业级高颜值**：Conduktor（付费版支持多用户/RBAC）或 Confluent Control Center（仅限 Confluent Platform）。

### 场景三：学习入门

第一次接触 Kafka，最想要「所见即所得」：

- **Redpanda Console**：一条命令起，界面漂亮，能直观看到 Topic / 分区 / 消费组，最不容易劝退。
- **Kafka UI**：功能全，学完之后继续当日常工具用。
- 学习时对照 [实战专题/01](../Kafka消息队列实战专题/01-Kafka消息队列从入门到架构师.md) 里的概念看界面，事半功倍。

### 场景四：桌面党 / 不习惯开浏览器

- **Conduktor 桌面版**：颜值 + 功能兼顾，免费 Community 版够个人用。
- **Kafka-King**：中文界面、现代，下载即用。
- **Offset Explorer**：桌面端功能最全，但界面朴素。

### 场景五：必须要中文界面

> **注意**：主流 Kafka 工具**基本都没有官方中文版**（详见 [常见坑：坑 6](#坑-6没有官方中文界面)）。

- 想要中文：**kafka-map**（Web）、**Kafka-King** / **Kafka Assistant**（桌面）。
- 用 Web 工具 + 浏览器整页翻译：Redpanda Console、Kafka UI 都能「翻译成中文」用。

### 场景六：要 100% 纯开源、零商业顾虑

选 **Kafka UI** 或 **Kafdrop**——都是 Apache 2.0 许可证，个人/商用都免费。

---

## 工具 1：Redpanda Console（Web 颜值天花板）

> **一句话定位**：界面公认最好看的 Kafka Web 面板，前身叫 Kowl，很多开发者纯粹因为它漂亮而选它。完全兼容标准 Kafka 3.x。
>
> **免费情况**：**免费**（个人日常使用；企业功能收费），Web 版无官方中文但可用浏览器翻译。

### 功能亮点

- 深色主题做得极好，仪表盘带指标卡片、实时图表，信息密度高但不杂乱。
- 消息浏览支持 **JSON 语法高亮 + 格式化折叠**。
- Topic 列表、分区分布、消费组延迟全部可视化。
- 支持浏览/搜索/发送消息、Topic 管理、消费组 lag、Schema Registry，日常开发完全够用。

### 怎么装 / 起

前提：装好 Docker Desktop（[docker.com](https://www.docker.com) 免费下载）。

```bash
docker run -d --name redpanda-console -p 8080:8080 \
  -e KAFKA_BROKERS=localhost:9092 \
  docker.redpanda.com/redpandadata/console:latest
```

- `KAFKA_BROKERS` 换成你实际的 broker 地址（多个用逗号分隔，如 `broker1:9092,broker2:9092`）。
- 如果你本地还没起 Kafka，它也可以连一个内置的 Redpanda（Kafka 兼容）一起跑，方便立刻体验。
- 不想用 Docker？GitHub Releases 有编译好的二进制：`https://github.com/redpanda-data/console/releases`，M 芯片 Mac 下载 `redpanda-console_*_macOS_arm64.tar.gz`，解压后 `./redpanda-console` 直接跑。

### 界面长什么样

打开 `http://localhost:8080`，你会看到：左侧菜单 + 顶部分区导航，Dashboard 有指标卡片，消息页有 JSON 高亮，整体是「为现代开发者设计」的高级感。想先看效果不用本地装，Redpanda 官网博客里有界面截图。

### 验证

```bash
# 1. 容器起来了
docker ps | grep redpanda-console

# 2. 浏览器打开（看到界面 = 成功）
open http://localhost:8080
```

界面里能看到 Kafka 集群的 Topic 列表，点进一个 Topic 能看到分区和消息，就说明连上了。

### 许可与收费（容易误解，重点说清楚）

| 说法 | 实际情况 |
|------|---------|
| 有单独的「免费版安装包」吗？ | 没有，**下载到的就是完整版**，企业功能需付费激活 |
| 个人日常用要钱吗？ | 免费（浏览/搜索/发送消息、Topic 管理、消费组 lag、Schema 都免费） |
| 收费的是什么？ | 企业功能：SSO 单点登录、RBAC 权限、审计日志、多环境管理 |
| 许可证 | 前身 Kowl 是 Apache 2.0；**2024 年起改为 source-available**（源码可见、非纯开源），免费使用不受影响 |

> **提示**：如果你要求「100% 纯 Apache 2.0、毫无顾虑」，请直接看下一个 [工具 2：Kafka UI](#工具-2kafka-uiprovectus--kafka-ui功能最全的开源首选)。

---

## 工具 2：Kafka UI（provectus / kafka-ui）功能最全的开源首选

> **一句话定位**：社区最活跃的开源 Kafka Web 面板，功能覆盖最全，**Apache 2.0 纯开源、个人商用都免费**，是「综合推荐度最高」的选择。
>
> **说明**：Web 版，无官方中文，可用浏览器翻译。

### 功能亮点

- 界面基于 React + Ant Design，干净现代，支持浅色/深色主题一键切换。
- 消息查询有可视化分区游标、JSON 树形展示。
- 自带 **Kafka Connect 管理页**：查看 Connector 状态、任务日志。
- 消费组有 offset 延迟可视化图表。
- 支持创建/编辑 Topic、管理 ACL、Schema Registry、查看生产/消费速率。
- 支持消息 **SQL 查询**、多集群管理、审计日志。
- 缺点：功能多导致页面略重，但整体体验好。

### 怎么装 / 起

```bash
docker pull provectuslabs/kafka-ui:latest

docker run -p 8080:8080 \
  -e DYNAMIC_CONFIG_ENABLED=true \
  provectuslabs/kafka-ui:latest
```

> **`DYNAMIC_CONFIG_ENABLED=true` 是什么**：开启后，**第一次打开界面会直接弹出一个表单，让你填 Kafka broker 地址**（如 `localhost:9092`），填完点保存就连上了，非常省事，适合本地开发。

### 界面长什么样 / 想先体验

打开 `http://localhost:8080` 即可。**想先看界面再装**，直接用官方在线演示站：

```
https://demo.kafka-ui.provectus.io/
```

里面有预置的演示数据（Topics、Consumer Groups、Messages），可以直接感受功能和操作逻辑。

### 验证

```bash
# 1. 打开界面
open http://localhost:8080

# 2. 填 broker 地址后，能看到 Topic 列表 / 集群信息 = 连接成功
# 3. 也可以进集群详情看版本、broker 数
```

### 官方地址汇总

- GitHub 源码（最权威）：`https://github.com/provectus/kafka-ui`
- Docker Hub 镜像：`https://hub.docker.com/r/provectuslabs/kafka-ui`
- 官方文档：`https://docs.kafka-ui.provectus.io/`
- 在线 Demo：`https://demo.kafka-ui.provectus.io/`

---

## 工具 3：Conduktor（桌面 + Web 双形态）

> **一句话定位**：颜值和 Redpanda Console 同梯队，但多一个「原生桌面 App」的手感；既有桌面版也有 Web 版（Console），免费 Community 版对个人开发完全够用。
>
> **说明**：免费 Community 版限 **1 个用户**；无中文界面。**详细的独立容器部署 + 纳管 Kafka 教程见 [02-Conduktor 纳管 Kafka 部署手册](./02-Conduktor纳管Kafka部署手册.md)**，本文只讲「怎么把它跑起来」。

### 功能亮点

- 消息浏览支持 JSON/Avro/Protobuf 智能格式化、字段高亮、按 key/header/timestamp 搜索，消息还能直接**编辑和重发**。
- Topic 列表、消费组 lag 监控、Schema Registry、Kafka Connect 都有漂亮的可视化面板。
- 自带一个**拖拽式数据流拓扑图**，非常惊艳。
- Web 版（Console）是「浏览器打开的高颜值管理平台」，支持 SQL 查询消息。

### 桌面版：怎么装 / 起

Mac 上两种方式任选：

```bash
# 方式一：Homebrew 一键安装（推荐）
brew install --cask conduktor

# 方式二：官网下载 .dmg
# 打开 https://www.conduktor.io/download 选 macOS 版本（Apple Silicon 选 arm64）
```

打开后填 broker 地址（如 `localhost:9092`）即可连接。有 Apple Silicon 原生版（arm64）和 Intel 版（x64），macOS 12+ 支持。

### Web 版（Console）：最简单的启动命令

30 秒看到界面，一条命令：

```bash
docker run -d --name conduktor -p 8080:8080 conduktor/conduktor-console:latest
```

然后三步：

1. 浏览器打开 `http://localhost:8080`（第一次等它初始化几秒到十几秒）。
2. 登录：默认账号 `admin` / 密码 `admin`。
3. 首次进入会提示 License（许可证），选 **Community Edition** → 填个邮箱拿免费 key → 填入即可。如果没弹，去左边 **Settings → License** 里选。

> **注意**：这条最简命令**没带 Postgres**，你配置的集群、设置重启容器后会丢（不影响看 Kafka 里的数据，Topic 本身存在 Kafka 里）。想长期用，接个 Postgres 持久化，见下方。

### Web 版：带 Postgres 持久化（官方推荐姿势）

建一个 `docker-compose.yml`：

```yaml
services:
  postgres:
    image: postgres:14
    environment:
      POSTGRES_DB: conduktor-console
      POSTGRES_USER: conduktor
      POSTGRES_PASSWORD: change_me
    volumes:
      - postgres_data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U conduktor"]
      interval: 10s
      timeout: 5s
      retries: 5

  conduktor-console:
    image: conduktor/conduktor-console:latest
    depends_on:
      postgres:
        condition: service_healthy
    ports:
      - "8080:8080"
    environment:
      CDK_DATABASE_URL: postgresql://conduktor:change_me@postgres:5432/conduktor-console
      CDK_ENTERPRISE: "false"   # 关键：false = 免费社区版
    volumes:
      - conduktor_data:/var/conduktor
      - /var/run/docker.sock:/var/run/docker.sock

volumes:
  postgres_data: {}
  conduktor_data: {}
```

启动：

```bash
docker compose up -d
```

### 免费版到底能用什么（Community Edition）

| 维度 | 免费版（Community Edition） |
|------|---------------------------|
| 费用 | **永久免费**，无试用期/时间限制 |
| 用户数 | 限 **1 个用户**（个人使用完全够） |
| 核心 UI 功能 | 全部可用：Topics、消息浏览、Consumer Groups、Schema Registry、SQL 查询 |
| 连接 Kafka 集群 | 可用 |

**付费（Enterprise）才有**：SSO 统一登录、多用户/团队协作、RBAC 权限、审计日志、Gateway（Kafka 代理网关）。——基本都是团队/企业场景的东西，个人开发用不上。

> **提醒**：免费版具体配额（如可管理集群数）Conduktor 会不定期调整，**以官方页面为准**：`https://www.conduktor.io/community`。

### 验证

```bash
# Web 版：容器起来 + 页面能打开
docker ps | grep conduktor
open http://localhost:8080
```

看到这三个画面就说明连上了：
1. 登录页/仪表盘正常显示（界面很精致）。
2. 添加集群后，连接状态显示 **Connected / 绿色**。
3. 能看到你 Kafka 里已有的 Topic 列表。

> **连接 Docker 里的 Kafka 是最容易踩坑的地方**：Conduktor 也在容器里，它访问不到宿主机上的 `localhost:9092`，要用 `host.docker.internal:9092`（宿主机别名）；或者让两个容器同网络、用容器名 `kafka:29092` 连。详见 [常见坑：坑 2](#坑-2docker-容器里localhost-不是宿主机) 和 [02 手册](./02-Conduktor纳管Kafka部署手册.md)。

---

## 工具 4：Kafdrop（最轻量）

> **一句话定位**：最轻量的 Kafka Web 面板，界面简单干净、启动快，**Apache 2.0 纯开源**。适合快速部署、临时查看消息。

### 功能亮点

- 界面简洁轻量，加载快，主打消息浏览。
- 能看到 Topic 列表、分区、消息内容。

### 怎么装 / 起

```bash
docker run -p 9000:9000 \
  -e KAFKA_BROKERCONNECT=localhost:9092 \
  obsidiandynamics/kafdrop:latest
```

注意端口是 **9000**（不是 8080）。

### 验证

```bash
open http://localhost:9000
```

打开能看到 Topic 列表，点进 Topic 能看到消息，即连接成功。

### 定位

不适合作为长期管理面板（不支持创建/编辑 Topic 等管理功能），定位是「**轻量够用**」。

---

## 工具 5：AKHQ（原 KafkaHQ）

> **一句话定位**：Vue 构建的开源 Kafka 面板，界面清爽偏「工具风」，功能比 Kafdrop 强不少。

### 功能亮点

- 支持 Topic 数据搜索、消息 **SQL 查询**。
- Schema Registry 查看。
- 审计日志功能。
- 界面偏「管理后台」风格，深色主题还行，但没有 Redpanda 那么惊艳。

### 怎么装 / 起

AKHQ 有 Docker 镜像，一条命令即可：

```bash
docker run -p 8080:8080 \
  -e KAFKA_BOOTSTRAP_SERVERS=localhost:9092 \
  tchiotludo/akhq:latest
```

> **注意**：AKHQ 开源免费版即可用，另有付费企业版；具体启动参数以官方文档为准。

### 验证

```bash
open http://localhost:8080
```

能看到 Topic 列表并可搜索消息，即连接成功。

---

## 工具 6：Offset Explorer（原 Kafka Tool）桌面功能之王

> **一句话定位**：桌面端**功能最全**的工具（前身叫 Kafka Tool），Windows/Mac/Linux 都有（Java 桌面程序），免费版 + 付费 Pro。**缺点是颜值硬伤**——传统树形导航 + 表格布局，老式桌面软件风格。

### 功能亮点

- 浏览/搜索/编辑消息。
- **手动设置 offset**（这是它的一大特色，其他工具少见）。
- 查看 Topic 与分区配置、消费组管理。
- 创建/删除 Topic、生成测试消息。
- 消费测试。

### 怎么装 / 起

- 官网下载：搜索「Offset Explorer 官网」，按平台下载安装包（Mac 是 `.dmg`）。
- 打开后填 broker 地址（如 `localhost:9092`），即可连接。

### 验证

打开应用 → 填 broker → 连接成功后在左侧树形目录里能看到集群、Topic、消费组，能点开看消息，即成功。

### 定位

**如果你在意界面美观，它可能不合胃口**；但如果你想要桌面端最全的功能、能忍朴素界面，它胜在免费 + 稳定。

---

## 工具 7：Kafka-King（中文现代桌面客户端）

> **一句话定位**：现代化、跨平台（Mac/Win/Linux）的 Kafka GUI 客户端，**界面现代 + 有中文**，是「想要原生桌面 App 手感又想要中文」的选择。开源免费。

### 功能亮点

- 专为「不用命令行、不用开浏览器」的管理需求设计。
- 现代桌面应用界面，比传统工具好看很多。
- 中文界面，对国内开发者友好。

### 怎么装 / 起（Mac）

1. 打开 Releases 页面：`https://github.com/Bronya0/Kafka-King/releases`
2. 找最新版，下载 **macOS arm64** 的安装包（认准文件名带 `arm64` 或 `darwin-arm64`、后缀 `.dmg`；Intel Mac 选 `amd64`）。
3. 双击 `.dmg` 拖进 Applications 安装。
4. **首次打开提示**：macOS 会拦截未签名 App，去 **系统设置 → 隐私与安全性**，点「仍要打开」，再输密码解锁。

### 验证

打开 App → 填 broker 地址 → 连接成功后能看到 Topic 列表，可浏览消息。

---

## 工具 8：kafka-map（中文 Web 面板）

> **一句话定位**：Gitee 上非常活跃的中文项目，定位「美观简洁且强大」，**中文界面**，适合想要中文 Web 面板的用户。开源免费。

### 功能亮点

- 界面清爽，主打简洁。
- 连完集群直接在 Web 上管理 Topic、消费组。
- **中文文档友好**。

### 怎么装 / 起

Gitee 仓库：`https://gitee.com/dushixiang/kafka-map`。克隆后按 README 用 Docker 部署：

```bash
# 具体命令以仓库 README 为准，大致如下：
docker run -d -p 8080:8080 \
  -e KAFKA_BOOTSTRAP_SERVERS=localhost:9092 \
  dushixiang/kafka-map
```

### 验证

```bash
open http://localhost:8080
```

打开能看到中文界面的 Topic / 消费组管理页，即成功。

---

## 工具 9：kafka-console-ui（国内轻量 Web 款）

> **一句话定位**：国内开发者维护的轻量级 Web 工具，界面简洁、主打快速上手，颜值中等。开源免费。

### 功能亮点

- 轻量、简洁，上手快。
- 中文友好（国内项目）。

### 怎么装 / 起

GitHub 仓库：`https://github.com/xxd763795151/kafka-console-ui`。按 README 用 Docker 部署，或下载发布包运行。

### 验证

打开界面 → 能看到 Topic 列表 / 消息浏览，即成功。

---

## 工具 10：KafkaLens（极简美学，收费）

> **一句话定位**：所有 Kafka 工具里**设计最讲究**的一个——极简 + 大量留白，微软 Fluent / 苹果风混合的设计感。**商业软件，按集群节点收费，较贵。**

### 功能亮点

- 极简高级的界面，在 Mac 上视觉效果一流。
- 有非常惊艳的**数据流拓扑可视化**，支持拖拽式查看消息流经的 Topic/分区。
- 消息浏览界面像精致的阅读器，支持分页游标、JSON 树形展示、过滤查询。

### 怎么装 / 起

去官网下载 Mac 试用版即可体验颜值。**社区版功能受限**，适合尝鲜不适合长期免费使用。

### 验证

打开 App → 填 broker → 能看到数据流拓扑和消息浏览界面，即成功。

---

## 工具 11：Kafka Assistant（国产可视化）

> **一句话定位**：Redisant 出品的国产桌面工具，主打「可视化管理与监控」，**中文界面**，界面在国产工具里做得比较规整。免费版可用。

### 功能亮点

- 中文界面，Windows/Mac 桌面客户端。
- 覆盖 Topic、消费、监控。

### 怎么装 / 起

官网下载：`https://www.redisant.cn/ka/download`，下载对应平台安装包，打开后填 broker 连接。

### 验证

打开 App → 填 broker → 能看到 Topic / 消费组 / 监控页，即成功。

---

## 工具 12：Confluent Control Center（企业级）

> **一句话定位**：Confluent 官方出品，UI 精致专业（深蓝色商务风格），有实时的吞吐量、延迟监控仪表盘，图表渲染精美。**只对 Confluent Platform 商业版开放，需要付费 License**，不适合开源 Kafka 用户。

### 功能亮点

- 实时吞吐量、延迟监控仪表盘。
- 图表渲染精美、专业。
- 与 Confluent Platform 生态深度集成。

### 怎么装 / 起

需要购买 Confluent Platform 商业版授权，随平台一起部署（通常在 `confluent local` 或企业集群里）。不适合个人免费使用，**本文不展开**。

### 验证

企业环境部署后通过平台入口访问，能看到各 broker 的监控指标。

### 适合谁

企业正式环境、使用 Confluent Platform、预算充足、需要专业监控面板的团队。

---

## 工具 13：IntelliJ IDEA 的 Kafka 插件（开发者的隐藏福利）

> **一句话定位**：直接在 JetBrains IDE（IDEA / PyCharm / GoLand）侧边栏里浏览 Topic、消息、消费组，风格和 IDE 完全统一，**对写代码的人最顺手**，免费。

### 功能亮点

- 不用单独开客户端，IDE 内直接看 Kafka。
- 风格与 IDE 统一，零学习成本。
- IDEA 本身装官方中文语言包后，IDE 是中文的（插件内部仍是英文）。

### 怎么装 / 起

1. 打开 IDE → Settings → Plugins → 插件市场搜 **"Kafka"**。
2. 安装后重启，在右侧/底部工具窗口里配置 broker 地址（`localhost:9092`）。
3. 即可在侧边栏浏览 Topic、消息、消费组。

### 验证

IDE 工具窗口里能看到 Kafka 集群和 Topic 列表，能点开消息查看，即成功。

---

## 总结

### 一句话决策

| 你的核心诉求 | 直接选 |
|-------------|--------|
| 颜值最高 + 最快出效果 | **Redpanda Console**（Web，1 条命令） |
| 功能最全 + 100% 纯开源 | **Kafka UI**（Web，Apache 2.0） |
| 桌面 App + 颜值 | **Conduktor 桌面版**（免费 Community 版够用） |
| 桌面 App + 中文 | **Kafka-King** |
| Web + 中文 | **kafka-map** |
| 最轻量、临时看消息 | **Kafdrop** |
| 桌面功能最全（忍得了朴素） | **Offset Explorer** |
| 开发时顺手看 | **IntelliJ Kafka 插件** |

### 三个核心结论

1. **Web 版是性价比之王**：Redpanda Console 和 Kafka UI 都是免费开源、一条 Docker 命令起、浏览器访问，个人学习和日常排障完全够用。
2. **「好看」和「免费」可以兼得**：Redpanda Console（Web）、Conduktor 免费版（桌面/Web）、Kafka-King（桌面中文）都满足。
3. **中文界面是整个生态的稀缺资源**：主流工具都没有官方中文，要么选国产工具（kafka-map / Kafka-King / Kafka Assistant），要么用浏览器整页翻译对付 Web 版。

---

## 常见坑

> **提示**：下面是新手用 Kafka 可视化工具时最高频的坑，按发生概率排序。**排查任何「连不上」问题，先记住一条主线：先确认 broker 在不在、端口通不通，再怀疑工具配置。**

### 坑 1：连不上 broker（Connection refused）

**现象**：工具里点连接报 `ConnectException: Connection refused` 或「服务器无法访问」。

**排查三步**：

```bash
# ① Kafka 容器/进程到底起没起？（很多情况是它根本没起来）
docker ps | grep kafka          # 容器应该是 Up，如果 Exited 看坑 4
docker logs kafka | grep "Kafka Server started"   # 有这行才算完全就绪

# ② 端口通不通（在宿主机终端测）
nc -vz 127.0.0.1 9092           # 显示 succeeded! = 端口通

# ③ 地址写对没有
# 本地 Kafka → localhost:9092；Docker 里的工具连宿主机 → host.docker.internal:9092
```

> **判断**：`nc` 通了但工具还连不上，说明问题在工具侧（协议、端口号、认证方式）；`nc` 都不通，问题在 broker 侧（没起来、端口没映射、被防火墙挡）。

### 坑 2：Docker 容器里 `localhost` 不是宿主机

**现象**：Kafka 和可视化工具都跑在 Docker 里，工具里填 `localhost:9092` 或 `127.0.0.1:9092` 永远连不上。

**原因**：容器里的 `localhost` 指向**容器自己**，不是你的电脑，更不是另一个容器。

**解决**（两种，选一）：

```bash
# 方案 A：用宿主机别名（Mac/Windows Docker Desktop 支持）
# 工具里填：host.docker.internal:9092

# 方案 B：让两个容器同网络，用容器名连（更干净）
docker network create kafka-net
docker network connect kafka-net kafka
docker network connect kafka-net conduktor
# 工具里填：kafka:29092  （29092 是 Kafka 容器内部 PLAINTEXT 端口）
```

> **注意**：填了 `host.docker.internal:9092` 第一次握手可能成功，但 Kafka 返回的 metadata 地址是 `localhost:9092`，工具再去连容器自己的 `localhost`，照样失败。最稳的是**方案 B（同网络 + 容器名）**。详细原理和步骤见 [02-Conduktor 纳管 Kafka 部署手册](./02-Conduktor纳管Kafka部署手册.md)。

### 坑 3：消息显示乱码 / 全是二进制

**现象**：消息内容显示一堆乱码或看不懂的字节，而非可读文本。

**原因**：Kafka 里存的是**字节数组**，界面只是按某种方式「解释」它。如果生产端用的序列化是 Avro/Protobuf/自定义，而工具没配置对应的反序列化器或 Schema Registry，就显示乱码。

**解决**：

- 生产端用的是纯文本/JSON：工具里把消息的格式选成 JSON/String 即可。
- 用的是 Avro/Protobuf：在工具里配置 **Schema Registry 地址**（如 `http://localhost:8081`），或安装工具的 Schema 插件。
- 就是一段原始字节：工具一般提供 Raw/Hex 视图，先确认它本身是不是可读文本。

> **注意**：这不是工具坏了，是「序列化格式」和「显示格式」没对齐。**先确认生产端用什么序列化，再决定工具怎么配。**

### 坑 4：Kafka 容器启动失败（Exited 1）

**现象**：`docker ps` 显示 Kafka 容器 `Exited (1)`，工具自然连不上。

**看日志定因**：

```bash
docker logs kafka 2>&1 | tail -50
```

| 日志关键字 | 原因 | 解决 |
|-----------|------|------|
| `Inconsistent cluster ID` / `Cluster ID mismatch` | 之前用不同 CLUSTER_ID 在同一个数据目录跑过 | 清空数据目录重来 |
| `AccessDeniedException` / `Permission denied` | 数据目录无权限 | `chmod -R 777 <数据目录>` |
| `Address already in use`（9092） | 端口被占用 | `lsof -i :9092` 找到占用进程，杀掉或换端口 |

> **提示**：Mac 上最常见的组合拳：删容器 → 清数据目录 → 授权 → 重新 `docker run`。详见原文档中的「清干净重来」命令（或参考 [02 手册](./02-Conduktor纳管Kafka部署手册.md) 的排查章节）。

### 坑 5：Kafka 没完全启动就点连接

**现象**：工具连一次失败，但过一会儿又好了。

**原因**：Kafka 启动需要时间（首次格式化存储目录约 20~60 秒），在 `Kafka Server started` 出现之前，端口虽然可能已经监听，但集群还没就绪。

**解决**：

```bash
# 等日志出现这一行再连
docker logs kafka | grep "Kafka Server started"

# 或等 docker compose 里 Kafka 状态变 healthy
docker compose ps
```

### 坑 6：没有官方中文界面

**现象**：打开工具全是英文。

**事实**：主流 Kafka 工具（Redpanda Console、Kafka UI、Conduktor、Offset Explorer、KafkaLens）**目前都只有英文界面**，基本没有官方中文版。

**应对**：

| 工具类型 | 变中文的办法 |
|---------|-------------|
| Web 版（Redpanda Console、Kafka UI 等） | 浏览器**整页翻译**：Chrome/Edge 地址栏点翻译图标 → 翻译为中文；Chrome 可勾选「始终翻译此网站」 |
| 桌面版（Conduktor、Offset Explorer 等） | 无法用浏览器翻译，只能选带中文的国产工具（kafka-map / Kafka-King / Kafka Assistant） |
| IntelliJ 插件 | IDEA 装官方中文语言包，IDE 是中文，但 Kafka 插件内部仍为英文 |

> **注意**：浏览器翻译会把界面文字翻成中文，但 **Topic / Partition / Consumer Group / Offset / Schema 等专业术语大概率保留英文**——这其实是好事，中文技术圈本来就习惯用英文。翻译后浏览器每次刷新可能恢复英文，Chrome 里勾选「始终翻译此网站」可避免。

### 坑 7：Conduktor 配置重启就丢

**现象**：Conduktor Console 重启后，之前在界面上配置的集群/设置没了。

**原因**：最简 `docker run` 启动时用的是**内存数据库**（默认不持久化），容器重启数据清零。

**解决**：改用带 Postgres 的 docker-compose（见 [工具 3 的 Web 版部署](#web-版带-postgres-持久化官方推荐姿势)），配置才能持久保存。

> **注意**：丢的只是 **Conduktor 自己的配置**，Kafka 里的 Topic 和消息不受影响（它们存在 Kafka 的数据目录里）。

### 坑 8：`9092` 还是 `29092`？端口搞混

**现象**：Docker 版工具连 `kafka:9092` 失败，但宿主机连 `localhost:9092` 成功。

**原因**：Kafka 容器通常配两个 listener：

- `PLAINTEXT://kafka:29092` → 给 **Docker 网络内**的其他容器连（容器名访问）。
- `PLAINTEXT_HOST://0.0.0.0:9092` → 映射到**宿主机**，给宿主机上的工具/程序连。

**解决**：容器内的工具填 `kafka:29092`（或 `kafka1:29092,kafka2:29092`），宿主机上的工具填 `localhost:9092`。多 broker 集群时，**每个 broker 地址都不能是 `localhost`**，否则 metadata 里返回的地址工具连不上。

---

## 延伸阅读

- **[02-Conduktor 纳管 Kafka 部署手册](./02-Conduktor纳管Kafka部署手册.md)**：Conduktor（双容器独立部署）从部署、纳管到监控、排查的完整手册，比本文的工具介绍更深。
- **[Kafka 消息队列实战专题/01](../Kafka消息队列实战专题/01-Kafka消息队列从入门到架构师.md)**：Topic / 分区 / offset / 消费组等核心概念 + Spring Kafka 收发消息实战。
- 官方在线 Demo（想先看界面再装）：
  - Kafka UI：`https://demo.kafka-ui.provectus.io/`
  - Redpanda Console 官网博客：`https://www.redpanda.com/blog/web-user-interface-tools-kafka`
