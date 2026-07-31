# Conduktor 纳管 Kafka 部署手册（双容器独立部署）

> **适用场景**：在 Mac（Apple Silicon M4）上，分别用**两个互相独立**的 Docker 容器部署 **Kafka** 和 **Conduktor Console**，然后把 Kafka 纳管到 Conduktor 中做可视化管理和监控。
>
> **核心设计理念**：**两个容器彼此独立**——各自一份 `docker-compose.yml`、各自的生命周期、各自的数据卷。Kafka 单独跑、单独给你的业务服务用；Conduktor 单独跑、随时可以停掉不影响 Kafka。两者通过一个**共享的 Docker 外部网络** `kafka-net` 关联，Conduktor 用容器名 `kafka:29092` 纳管 Kafka。
>
> **难度假设**：你已经装好 Docker Desktop、会基本的 `docker ps`，本地已有一个 PostgreSQL（账号 `postgres/postgres`，已有 `rag` 等业务库；Conduktor 会用一个**独立的 `conduktor` 库**，与业务库隔离）。

---

## 第 0 章：为什么是"两个独立容器"，而不是一份 compose 全包

你可能见过那种"把 Kafka + Zookeeper + Conduktor + Postgres 全塞进一份 `docker-compose.yml`"的写法。那种写法**适合一次性体验 Demo，但不适合长期使用**，原因：

| 问题 | 全塞一份 compose | 本手册的双容器独立部署 ✅ |
|------|----------------|------------------------|
| Kafka 要给业务服务用 | 一停 Conduktor，整份 compose 可能受牵连 | Kafka 独立，业务不受影响 |
| Conduktor 升级 / 重启 | 会带着 Kafka 一起动 | 互不干扰 |
| 复用已有的本地 Postgres | 要么再起一个 Postgres，要么改一大堆 | Conduktor 用独立的 `conduktor` 库，与业务库隔离 |
| 数据隔离 | 全混在一个 project 里 | 各自的卷，清晰 |
| 排查问题 | 一坨日志 | 各看各的日志 |

**一句话**：独立部署 = 你随时能单独停掉 Conduktor（比如不想占内存时），而 Kafka 一直在跑、业务不中断。这才是生产/长期开发该有的姿势。

---

## 第 0.5 章：为什么不能用 `localhost` / `127.0.0.1`（必读原理）

这是整个方案最该先搞懂的一件事，否则后面处处踩坑。

### 原因：容器里的 `localhost` 指向"容器自己"，不是你的 Mac

```
你的 Mac（宿主机）
├── localhost:9092  ✅ 能连到 Kafka（因为 Kafka 把 9092 映射到了宿主机）
│
└── Conduktor 容器（一个隔离的小房间）
    └── localhost:9092  ❌ 在这个房间里，localhost 指向"房间自己"，房间里没 Kafka
```

所以当 Conduktor 容器去连 `localhost:9092` 时，它在**自己肚子里**找 9092，自然找不到 → `Connection refused`。`127.0.0.1` 是 `localhost` 的 IP 写法，同理一样不通。

### 结论：容器之间通信只能走两条路

| 方式 | 地址 | 前提 |
|------|------|------|
| **容器名** | `kafka:29092` | 两个容器在**同一个 Docker 网络**里 |
| **宿主机别名** | `host.docker.internal:9092` | Docker Desktop 提供，指回宿主机 |

### 那为什么 Conduktor 不直接填 `host.docker.internal:9092` 简单了事？

技术上能连上，但有**一个隐藏大坑——Kafka 的 `advertised.listeners` 机制**：

> 客户端连上 Kafka 后，Kafka 会回一句话："以后连我请去这个地址。" 这个地址就是 `advertised.listeners`。

如果让 Conduktor 走 `host.docker.internal:9092`，那么：
1. Kafka 的 `advertised.listeners` 必须配成 `host.docker.internal:9092`；
2. 这会**绑架你宿主机上所有业务代码**——它们也只能用 `host.docker.internal:9092`，不能用习惯的 `localhost:9092`；
3. 一旦换网络环境或上 Linux（Linux 上 `host.docker.internal` 默认不存在），全线崩。

### 本手册的解法：双 listener，各管一边（工业界标准）

| 端口 | 给谁用 | advertised 地址 |
|------|--------|----------------|
| `9092`（PLAINTEXT_HOST） | **宿主机跑的代码**（你直接 `java -jar` 起的 Spring Boot） | `host.docker.internal:9092`，代码里写 `localhost:9092` |
| `29092`（PLAINTEXT） | **容器之间**（Conduktor、容器化的业务服务） | `kafka:29092` |

这样你的代码用最自然的 `localhost:9092` 就能连，Conduktor 用 `kafka:29092` 纳管，互不干扰。**这也是后续第 9 章代码能直连的关键。**

### 疑问解答：为什么 Postgres 不用建网络，Kafka 却要？

你可能会问：Conduktor 连 Postgres 用的是 `host.docker.internal:5432`（走端口映射，不需要任何 Docker 网络），那为什么连 Kafka 不能也这样、非要建 `kafka-net`？

**根本原因：Postgres 连上就完事；Kafka 连上后会"回传一个地址"。**

| | Postgres | Kafka |
|---|---|---|
| 客户端连上后 | 直接开始通信 | 会回一句："以后连我请去 **X 地址**"（这个 X 就是 `advertised.listeners`） |
| 每个客户端能各走各的吗 | ✅ 能，各走各的端口映射 | ❌ 不能，advertised 是全局唯一的，所有客户端都得用它 |

- **Postgres**：每个客户端走自己的端口映射就行，互不影响 → Conduktor 用 `host.docker.internal:5432`、你的代码用 `localhost:5432`，各走各的，**不需要共享网络**。
- **Kafka**：advertised 地址是**全局唯一**的，必须选一个所有客户端都认的地址。
  - 选 `kafka:29092`（容器网络）→ 容器用它、你的代码用 `localhost:9092`，互不绑架 ✅
  - 选 `host.docker.internal:9092` → 你的代码也被迫用 `host.docker.internal:9092`（不能用 `localhost`），且 Linux 上默认没这个域名 ❌

**一句话**：`kafka-net` 不是为 Postgres 建的（Postgres 确实不需要），是为 **Conduktor 纳管 Kafka** 建的——因为 Kafka 的 advertised 机制要求一个所有客户端都通用的地址，用容器网络 + 双 listener 是最干净的解法。

> 💡 如果你不介意业务代码用 `host.docker.internal:9092`（而不是 `localhost:9092`），也可以不建网络——但本手册推荐建网络，因为业务代码用 `localhost` 才是长期最舒服的姿势。

---

## 第 1 章：前置条件确认

### 1.1 环境

| 项 | 要求 | 验证命令 |
|----|------|---------|
| Docker Desktop | 已安装并运行（Mac 自动用 ARM 镜像） | `docker --version` |
| Docker Compose | v2（`docker compose` 子命令） | `docker compose version` |
| 本地 PostgreSQL | 在跑，账号 `postgres/postgres` | `psql -U postgres -d postgres -c "select 1"` |
| 可用端口 | 9092（Kafka 对外）、8080（Conduktor）、8081（Schema Registry，可选） | 见下文 |

> ⚠️ 如果你之前在某个库里留了一堆 `cdk` 开头的 schema（旧版 Conduktor 残留）想清掉重来，连到对应库执行（**会删数据，确认后再跑**）：
> ```sql
> DO $$ DECLARE s RECORD; BEGIN FOR s IN SELECT schema_name FROM information_schema.schemata WHERE schema_name LIKE 'cdk%' LOOP EXECUTE format('DROP SCHEMA IF EXISTS %I CASCADE', s.schema_name); END LOOP; END $$;
> ```

### 1.2 创建共享的外部网络（关键，第 2、3 章都依赖它）

两个独立容器要能互相用容器名通信，必须挂到**同一个 Docker 网络**上。我们建一个**外部网络**（不被任何单个 compose 拥有）：

```bash
docker network create kafka-net
```

验证：

```bash
docker network ls | grep kafka-net
```

> 💡 为什么用"外部网络（external: true）"？因为两个 compose 文件各自独立，谁都不应该"拥有"这个网络。声明成 external 后，两个 compose 只是**加入**它，删任何一个 compose 都不会删掉这个网络，关联关系稳定。

---

## 第 2 章：部署 Kafka（独立容器 ①）

### 2.1 准备目录

```bash
mkdir -p /Volumes/data/software/docker/containers/kafka/data
cd /Volumes/data/software/docker/containers/kafka
```

### 2.2 创建 `docker-compose.yml`

在 `/Volumes/data/software/docker/containers/kafka/` 下新建文件 `docker-compose.yml`：

```yaml
# Kafka 独立部署（KRaft 单节点，无 Zookeeper）
services:
  kafka:
    image: confluentinc/cp-kafka:7.7.1
    container_name: kafka
    hostname: kafka
    restart: unless-stopped
    ports:
      - "9092:9092"     # 对宿主机/业务服务暴露（PLAINTEXT_HOST）
      - "29092:29092"   # 容器间通信（PLAINTEXT），Conduktor 走这个
      - "9999:9999"     # JMX，给 Conduktor 监控采集用
    environment:
      # ===== KRaft 模式（无 Zookeeper）=====
      KAFKA_NODE_ID: 1
      KAFKA_PROCESS_ROLES: "broker,controller"
      KAFKA_CONTROLLER_QUORUM_VOTERS: "1@kafka:29093"
      KAFKA_CONTROLLER_LISTENER_NAMES: "CONTROLLER"

      # ===== 监听器（三个，分工明确）=====
      # PLAINTEXT      → 容器间通信用（29092），Conduktor 用这个
      # PLAINTEXT_HOST → 对宿主机/业务服务暴露（9092）
      # CONTROLLER    → KRaft 内部用（29093）
      KAFKA_LISTENERS: "PLAINTEXT://0.0.0.0:29092,CONTROLLER://0.0.0.0:29093,PLAINTEXT_HOST://0.0.0.0:9092"
      KAFKA_ADVERTISED_LISTENERS: "PLAINTEXT://kafka:29092,PLAINTEXT_HOST://host.docker.internal:9092"
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: "CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT,PLAINTEXT_HOST:PLAINTEXT"
      KAFKA_INTER_BROKER_LISTENER_NAME: "PLAINTEXT"

      # ===== 单节点必备（副本因子=1）=====
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
      KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR: 1
      KAFKA_TRANSACTION_STATE_LOG_MIN_ISR: 1
      KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS: 0
      KAFKA_AUTO_CREATE_TOPICS_ENABLE: "true"

      # ===== 监控（JMX）=====
      KAFKA_JMX_PORT: 9999
      KAFKA_JMX_HOSTNAME: kafka

      # ===== 集群 ID（KRaft 格式化用，22 位 base64）=====
      CLUSTER_ID: "MkU3OEVBNTcwNTJENDM2Qk"

    volumes:
      # Kafka 数据目录挂载到宿主机指定路径（绑定挂载，数据持久化、可直接查看）
      - "/Volumes/data/software/docker/containers/kafka/data:/var/lib/kafka/data"
    networks:
      - kafka-net
    healthcheck:
      test: ["CMD-SHELL", "kafka-topics --bootstrap-server localhost:29092 --list >/dev/null 2>&1"]
      interval: 10s
      timeout: 10s
      retries: 12

networks:
  kafka-net:
    external: true   # 使用第 1.2 步创建的外部网络
```

#### 📌 这份配置的三个关键点（务必看懂）

**① 为什么要有两个 listener（9092 和 29092）？**

这是整个手册最容易踩坑的地方，也是对话记录里反复出问题的地方：

| 端口 | listener 名 | 谁用 | advertised 地址 |
|------|------------|------|----------------|
| `9092` | `PLAINTEXT_HOST` | **你的业务服务、宿主机终端** | `host.docker.internal:9092` |
| `29092` | `PLAINTEXT` | **Conduktor 等容器**（同一 Docker 网络） | `kafka:29092` |

Kafka 的 `advertised.listeners` 决定了"客户端连上来后，Kafka 告诉客户端接下来去哪连"。如果只配一个、且 advertised 成 `localhost`，就会出现：

- 宿主机能连，Conduktor 容器连不上（`localhost` 在容器里指向它自己）；
- 或者 Conduktor 连上了，但 metadata 返回 `localhost`，后续又断了。

**两个 listener 各管一边，互不干扰**——这是工业界标准做法。

**② 为什么 `PLAINTEXT_HOST` 的 advertised 用 `host.docker.internal`？**

`host.docker.internal` 是 Docker Desktop（Mac/Windows）提供的特殊域名，**指向宿主机**。这样你的业务服务（不管跑在宿主机还是别的容器）都能稳定通过 `host.docker.internal:9092` 连到 Kafka。如果写成 `localhost`，只有宿主机本地能连。

**③ `networks.kafka-net` 为什么是 `external: true`？**

声明成 external，意味着这个 compose **不负责创建** `kafka-net`，只是加入它（第 1.2 步手动建好的）。这样 Kafka 容器和 Conduktor 容器即使来自两份不同的 compose，也能通过容器名互通。

### 2.3 启动 Kafka

```bash
cd /Volumes/data/software/docker/containers/kafka
docker compose up -d
```

### 2.4 验证 Kafka 正常

```bash
# 看状态，等 STATUS 变成 (healthy)
docker compose ps

# 看 Kafka 日志（首次格式化存储约 20-40 秒）
docker compose logs -f kafka
```

**功能验证——列出 topic（首次应为空或仅 internal topics）：**

```bash
docker exec -it kafka kafka-topics --bootstrap-server localhost:29092 --list
```

**收发消息验证（开两个终端）：**

```bash
# 终端 A：生产
docker exec -it kafka kafka-console-producer \
  --bootstrap-server localhost:29092 --topic test-topic

# 终端 B：消费
docker exec -it kafka kafka-console-consumer \
  --bootstrap-server localhost:29092 --topic test-topic --from-beginning
```

在终端 A 输入几行回车，终端 B 能看到 → Kafka 部署成功 ✅

> 💡 至此 Kafka 完全独立可用。你的业务服务（Spring Boot 等）连接地址用 `host.docker.internal:9092`（宿主机跑的服务）或 `kafka:29092`（容器内跑的服务）。

---

## 第 3 章：部署 Conduktor Console（独立容器 ②）

### 3.1 准备目录

```bash
mkdir -p /Volumes/data/software/docker/containers/conduktor/data
cd /Volumes/data/software/docker/containers/conduktor
```

### 3.2 创建 `docker-compose.yml`

在 `/Volumes/data/software/docker/containers/conduktor/` 下新建文件 `docker-compose.yml`：

```yaml
# Conduktor Console 独立部署
# 复用本地 PostgreSQL，使用独立的 conduktor 库（与 rag 业务库隔离）
services:
  conduktor-console:
    image: conduktor/conduktor-console:latest
    container_name: conduktor-console
    restart: unless-stopped
    ports:
      - "8080:8080"
    environment:
      # ===== 连接本地 Postgres =====
      # host.docker.internal = 你的 Mac；账号 postgres/postgres
      # 库名 conduktor（独立库，需先建好，见下方说明）
      CDK_DATABASE_URL: "jdbc:postgresql://postgres:postgres@host.docker.internal:5432/conduktor"
      CDK_ENTERPRISE: "false"        # false = 免费 Community Edition
      CDK_DEMO_CLUSTER: "false"      # 禁用自带 demo 集群（它的 localhost:9092 在容器内连不通，会产生无害报错，见 6.0.5）
    volumes:
      # Conduktor 数据目录挂载到宿主机指定路径
      - "/Volumes/data/software/docker/containers/conduktor/data:/var/conduktor"
    networks:
      - kafka-net

networks:
  kafka-net:
    external: true   # 与 Kafka 共享同一网络，才能用 kafka:29092 纳管
```

#### 📌 关键点说明

**① 用独立的 `conduktor` 库（重要：先建库）**

Conduktor 用一个**独立的数据库 `conduktor`**，和你的 `rag` 业务库完全隔离——互不污染、排查清晰、想清空 Conduktor 数据时直接删库重建即可，不碰业务数据。

但 PostgreSQL 不会自动建库，所以**启动 Conduktor 前，必须先手动创建 `conduktor` 数据库**（一次性操作）：

```bash
# 宿主机有 psql：
psql -U postgres -d postgres -c "CREATE DATABASE conduktor;"

# 或用 Docker 里的 postgres 客户端（把 <pg容器名> 换成你的 Postgres 容器名）：
docker exec -it <pg容器名> psql -U postgres -d postgres -c "CREATE DATABASE conduktor;"
```

> 💡 验证库已建好：`psql -U postgres -d conduktor -c "select 1"`（或对应的 docker exec 版本）能通就行。

**② 关于 schema：不需要指定，用默认 `public` 即可**

既然 `conduktor` 是独立库，Conduktor 启动时会在该库的默认 `public` schema 里用 Flyway 自动建表，**URL 里不要加 `?currentSchema=...`**。

> ⚠️ 别画蛇添足加 `?currentSchema=conduktor`：那要求 schema 提前存在，反而可能导致启动失败。独立库 + 默认 schema 是最省心的组合。

**③ 为什么 Conduktor 也加入 `kafka-net`？**
因为 Conduktor 要用容器名 `kafka:29092` 访问 Kafka。两者在同一网络，才能解析对方的容器名。这是"纳管"能成功的前提。

**④ 数据持久化**
- Conduktor 的配置/用户/集群连接信息存在独立的 `conduktor` 库里（重启不丢）；
- `/var/conduktor` 挂载到你指定的宿主机目录，避免容器重建丢运行时数据。

### 3.3 启动 Conduktor

> 前提：第 3.2 说明 ① 的 `CREATE DATABASE conduktor;` 已经执行过。

```bash
cd /Volumes/data/software/docker/containers/conduktor
docker compose up -d
```

### 3.4 验证 Conduktor 启动

```bash
# 看日志，等待 "Listening on" / 就绪字样（约 20-40 秒）
docker compose logs -f conduktor-console
```

> ⚠️ 如果日志报 `database "conduktor" does not exist`：说明你还没建库——回到 3.2 说明 ① 执行 `CREATE DATABASE conduktor;`，再 `docker compose restart conduktor-console`。

> ⚠️ 如果日志报数据库连不上（`Connection refused`），多半是你本地 Postgres 只监听 `127.0.0.1`。排查见第 6 章。

浏览器访问：**http://localhost:8080**

- 首次登录：账号 **admin** / 密码 **admin**
- 按引导选择 **Community Edition**（免费），填邮箱拿免费 license key 填入即可。

---

## 第 4 章：从 Conduktor 界面纳管 Kafka（核心操作）⭐

两个容器都跑起来、且都在 `kafka-net` 网络里之后，开始纳管。这一章是**界面操作手把手**，照着点就行。

### 4.1 界面操作前的预检：确认两容器网络互通

界面里点"连接"之前，先用命令确认网络是通的，能省掉 90% 的来回试错：

```bash
# 1. 确认两个容器都在 kafka-net 里
docker network inspect kafka-net --format '{{range .Containers}}{{.Name}} {{end}}'
# 应输出包含：kafka conduktor-console

# 2. 从 Conduktor 容器内测试能否连到 Kafka 的 29092
docker exec conduktor-console nc -vz kafka 29092
# 应显示：succeeded!
```

> 🔑 **如果第 2 步不通，界面里一定连不上**。先回头检查：Kafka 容器是否 healthy、两容器是否都在 `kafka-net`（用 `docker network connect kafka-net kafka` / `... conduktor-console` 手动补挂）。

### 4.2 首次登录并激活免费版

1. 浏览器打开 **http://localhost:8080**。
2. 首次登录，账号 **admin** / 密码 **admin**。
3. 进入后会弹出 **License（许可证）** 引导，选择 **Community Edition**（社区版，免费）。
4. 按提示填一个邮箱，获取免费 license key，粘贴进去激活即可（不扣费，只是登记）。
   - 如果当时没弹，去左侧 **Settings → License** 里同样能选 Community。
5. 激活后进入主界面，左侧导航栏可见 **Clusters / Topics / Consumer Groups** 等菜单。

> ⚠️ Community 版限制 1 个用户，个人开发完全够用。

### 4.3 在界面里新建集群（纳管的核心步骤）

1. 左侧菜单点 **Clusters**（集群）。
2. 点右上角（或页面中央的） **+ Add a cluster** / **New Cluster**（新建集群）。
3. 选择集群类型 **Apache Kafka**。
4. 进入集群配置表单，按下表逐项填写：

| 字段 | 填什么 | 说明 |
|------|-------|------|
| **Cluster Name** | `Local Kafka` | 随便起，自己认得就行 |
| **Color / Icon** | 默认 | 给集群打个颜色标记，便于多集群时区分 |
| **Bootstrap Servers** | `kafka:29092` | ⚠️ **必须是这个**！不是 `localhost:9092`，不是 `127.0.0.1:9092`，也不是 `host.docker.internal:9092` |
| **Protocol** | `PLAINTEXT` | 本手册没开 SASL/SSL |
| **Schema Registry URL** | 留空 | 本手册未部署；需要见第 7 章 |
| **JMX URL / Port** | `kafka:9999`（可暂不填） | 开启监控用，详见第 5 章 |

> 🔑 **Bootstrap Servers 为什么是 `kafka:29092`？** 因为 Conduktor 在容器里，要走容器间的 PLAINTEXT 端口 29092，并用容器名 `kafka` 寻址（两者同在 `kafka-net` 才解析得了）。填 `localhost` 就是去 Conduktor 自己肚子里找，必失败。原理见第 0.5 章。

5. 填完后点页面下方的 **Test connection**（测试连接）：
   - ✅ 显示绿色 **Connected / Success** → 继续；
   - ❌ 显示红色 **Connection refused / Timed out** → 回到 4.1 重查网络，常见原因见第 6.1 节。
6. 测试通过后点 **Save** / **Create**（保存）。

> ⚠️ **保存后必须刷新页面（F5 / Cmd+R）。** 新建集群后 Conduktor 的 UI 状态不会自动同步，如果不刷新直接点 Topics，可能报 **"无法渲染此页面，因为 URL 中缺少参数：clusterId"**——这不是配置错了，是页面没拿到新集群的上下文。**刷新一下、再从集群卡片点进去就好了。**

### 4.4 验证纳管成功

保存并**刷新页面**后，回到 **Clusters** 列表页：

- 出现 **Local Kafka** 卡片，状态点为**绿色**，标注 **Connected**。

**点进 `Local Kafka` 这个集群卡片**（Topics 页面必须从某个具体集群进去，不能脱离集群独立打开），再切到各标签，依次确认能看到：

| 标签 | 应看到的内容 | 说明 |
|------|-------------|------|
| **Overview** | broker 数（1）、集群信息 | 集群摘要 |
| **Topics** | topic 列表（含第 2.4 步建的 `test-topic`，以及 internal topics 如 `__consumer_offsets`） | 建表/看分区 |
| **Messages** | 点进某 topic 可按 offset/时间浏览消息，JSON 自动格式化高亮 | 最常用 |
| **Consumer Groups** | 消费组列表及 lag（积压量） | 配合第 9 章代码看消费进度 |
| **Monitoring** | 吞吐量、broker 图表（需配 JMX，见第 5 章） | 监控 |

只要 **Topics** 里能看到你 Kafka 的 topic，就说明 **Kafka 已被 Conduktor 成功纳管** ✅

### 4.5 纳管之后能做什么（常用操作速览）

| 想做的事 | 在 Conduktor 哪里 |
|---------|------------------|
| 新建 / 删除 topic | Clusters → Local Kafka → Topics → + Create |
| 手动发一条消息测试 | 进某 topic → **Produce** 按钮 |
| 浏览/搜索消息 | 进某 topic → **Messages** → 可按 offset、key、时间过滤 |
| 看消费组积压（lag） | Consumer Groups → 选消费组 |
| 修改 topic 配置（分区、保留时间） | 进 topic → **Settings / Config** |
| 重置消费位点 | Consumer Groups → 选组 → Reset offsets |

> 💡 纳管完成后，Conduktor 就是你 Kafka 的"可视化驾驶舱"。等第 9 章把代码接上后，代码负责收发消息，Conduktor 负责让你**看见**这些消息在流动、消费到哪了、有没有积压——两者读的是同一个 Kafka，数据完全一致。

---

## 第 5 章：开启监控（Monitoring）

### 5.1 前提：JMX 已开

第 2.2 的 Kafka compose 已经配了：

```yaml
KAFKA_JMX_PORT: 9999
KAFKA_JMX_HOSTNAME: kafka
```

JMX 暴露在 `kafka:9999`（容器间）和 `9999`（已映射到宿主机）。

### 5.2 在 Conduktor 配置监控采集

纳管集群时（或编辑现有集群）：

- 找到 **JMX** / **Monitoring** 相关字段，填 `kafka:9999`
- 保存后进入 **Clusters → Local Kafka → Monitoring** 标签

能看到：

- **Brokers**：broker 在线状态、分区分布、ISR
- **Throughput**：messages in/out、bytes in/out
- **Topics**：每个 topic 的速率
- **Consumer Groups**：消费组 lag（积压量）—— 生产最重要指标之一

> ⚠️ 如果 Monitoring 页空白：最常见是 JMX 地址没填对（容器内必须用 `kafka:9999`，不是 `localhost`）。确认 `docker exec conduktor-console nc -vz kafka 9999` 通。

---

## 第 6 章：常见问题排查

### ❓ 6.0 启动报 "The container name ... is already in use"

**典型报错：**

```
Error response from daemon: Conflict. The container name "/kafka" is already in use
by container "0d615066...". You have to remove (or rename) that container to be
able to reuse that name.
```

**原因**：你之前已经起过一个同名容器（`kafka` 或 `conduktor-console`），现在改了 compose 配置再 `up -d`，Docker 不会自动重建同名容器，于是冲突。**改了配置但容器名没变时最容易踩这个坑。**

**解决**：先删掉旧容器，再启动。

```bash
# 1. 看一眼旧容器是谁（确认是旧的、不要了的）
docker ps -a --filter "name=kafka" --format "table {{.Names}}\t{{.Image}}\t{{.Status}}\t{{.CreatedAt}}"

# 2. 删掉旧容器，再用新配置启动
docker rm -f kafka
docker compose up -d
```

（如果是 `conduktor-console` 冲突，把上面命令里的 `kafka` 换成 `conduktor-console` 即可。）

> 💡 **更省心的做法**：以后每次改完 compose 配置，养成"先 down 再 up"的习惯，就不会冲突：
> ```bash
> docker compose down     # 停掉并删除旧容器（保留数据卷）
> docker compose up -d    # 用新配置重建
> ```
> 详见第 8 章"改配置后的标准流程"。

### ❓ 6.0.5 启动日志报 "Failed to create admin client for 'localhost:9092'"

**典型报错（Conduktor 启动日志里）：**

```
WARN  i.c.d.a.c.a.KafkaClientFactoryLive - Failed to create admin client for 'localhost:9092'
java.net.ConnectException: Connection refused
...
ERROR i.c.m.indexer.core.TopicIndexingTask - Topic indexing failed for cluster 'billowing-protector': could not create admin client
```

**原因**：Conduktor 启动时自带了一个**演示集群**（名叫 `billowing-protector` 之类），它的 Bootstrap Servers 写死成 `localhost:9092`。而 Conduktor 自己在容器里，`localhost` 指向它自己，连不到 Kafka → 报 Connection refused。

**重要**：这个报错**不影响 Conduktor 本身启动和使用**——数据库正常、Web UI 正常（能打开 `http://localhost:8080` 登录）。只是那个 demo 集群连不上、Topic 索引失败。你后面手动加自己的集群（第 4 章，填 `kafka:29092`）完全不受影响。

**消除报错（禁用 demo 集群）**：在 Conduktor 的 `docker-compose.yml` 里加一个环境变量：

```yaml
    environment:
      CDK_DATABASE_URL: "jdbc:postgresql://postgres:postgres@host.docker.internal:5432/conduktor"
      CDK_ENTERPRISE: "false"
      CDK_DEMO_CLUSTER: "false"    # ← 新增：禁用自带 demo 集群
```

改完重启（注意先 down 再 up，否则会触发 6.0 的容器名冲突）：

```bash
cd /Volumes/data/software/docker/containers/conduktor
docker compose down
docker compose up -d
```

> 💡 这个报错和 6.1 是同一个根因（容器内 `localhost` 不通），只不过 6.0.5 是 Conduktor **自带的 demo 集群**在作怪，6.1 是**你自己手动加的集群**填错了地址。原理都是第 0.5 章讲的"容器里 localhost 指向容器自己"。

### ❓ 6.1 Conduktor 报"Connection refused"连不上 Kafka

**这是对话记录里出现最多的问题。** 99% 是 Bootstrap Servers 填错或网络不通。

**排查顺序：**

```bash
# 1. 两容器是否在同一网络？
docker network inspect kafka-net --format '{{range .Containers}}{{.Name}} {{end}}'

# 2. 容器内能否解析并连通 kafka:29092？
docker exec conduktor-console nc -vz kafka 29092
```

- 如果命令 1 没看到 `kafka`：`docker network connect kafka-net kafka`
- 如果命令 1 没看到 `conduktor-console`：`docker network connect kafka-net conduktor-console`
- Bootstrap Servers **必须填 `kafka:29092`**，不要填 `localhost:9092` / `127.0.0.1:9092`

> **为什么 Conduktor 不能用 `localhost:9092`？** Conduktor 自己也是个容器，容器内的 `localhost` 指向**容器自己**，不是你的 Mac，自然连不到 Kafka。容器之间必须用容器名（`kafka`）+ 容器间端口（`29092`）。

### ❓ 6.1.5 Topics 页面报 "URL 中缺少参数：clusterId"

**典型报错：**

```
无法渲染此页面，因为 URL 中缺少参数：clusterId
```

**原因**：Topics 页面**必须依附于某个具体集群**，它的 URL 形如 `/cluster/<clusterId>/topics`。出现这个报错通常是两种情况之一：

1. **新建集群后没刷新页面**，UI 还没拿到新集群上下文，直接点了 Topics。
2. **你点的是那个连不上的 demo 集群**（`billowing-protector`，走 `localhost:9092`），它没有有效 clusterId；或当前没有任何可用的活跃集群。

**解决**：

```bash
# 1. 先确认你有可用的、连得上的集群（走 kafka:29092 的那个）
docker exec -it postgres psql -U postgres -d conduktor \
  -c "SELECT id, name FROM cdk_console.kafka_cluster;"
```

- 如果列表里**没有**你自己的 `Local Kafka` → 回到 4.3 先建集群（Bootstrap 填 `kafka:29092`）。
- 如果**有** → 按下面操作：

```
① 浏览器刷新页面（Cmd+R / F5）
② 左侧菜单点 Clusters
③ 点你自己的 Local Kafka 卡片（不要点 demo 集群 billowing-protector）
④ 进入集群详情后，再切到 Topics 标签
```

> 💡 顺手把没用的 demo 集群删掉：Clusters 列表里点 `billowing-protector` 的 **⋯ → Delete**，列表只剩你自己的集群，就不会误点。

### ❓ 6.2 Conduktor 启动报"Missing database configuration" 或 "database does not exist"

新版 Conduktor（1.44+）**必须配数据库**。确认 compose 里 `CDK_DATABASE_URL` 已正确指向独立的 `conduktor` 库，格式为：

```
jdbc:postgresql://postgres:postgres@host.docker.internal:5432/conduktor
```

如果报 `database "conduktor" does not exist`：说明你还没建库，执行一次（详见 3.2 说明 ①）：

```bash
psql -U postgres -d postgres -c "CREATE DATABASE conduktor;"
# 或 docker exec -it <pg容器名> psql -U postgres -d postgres -c "CREATE DATABASE conduktor;"
```

### ❓ 6.3 Conduktor 报数据库连不上

你的本地 Postgres 可能只监听 `127.0.0.1`，容器走 `host.docker.internal` 连不到。

**两种解法，任选其一：**

**解法 A（推荐，最省事）：给 Conduktor 单独起一个 Postgres 容器**

把第 3.2 的 compose 改成带独立 Postgres 的版本（不碰你的 `rag` 库）：

```yaml
services:
  postgres:
    image: postgres:16
    container_name: conduktor-postgres
    environment:
      POSTGRES_DB: conduktor
      POSTGRES_USER: conduktor
      POSTGRES_PASSWORD: conduktor
    volumes:
      - conduktor-pg-data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U conduktor"]
      interval: 10s
      timeout: 5s
      retries: 10
    networks:
      - kafka-net

  conduktor-console:
    image: conduktor/conduktor-console:latest
    container_name: conduktor-console
    restart: unless-stopped
    depends_on:
      postgres:
        condition: service_healthy
    ports:
      - "8080:8080"
    environment:
      CDK_DATABASE_URL: "jdbc:postgresql://conduktor:conduktor@conduktor-postgres:5432/conduktor"
      CDK_ENTERPRISE: "false"
    volumes:
      - "/Volumes/data/software/docker/containers/conduktor/data:/var/conduktor"
    networks:
      - kafka-net

volumes:
  conduktor-pg-data:

networks:
  kafka-net:
    external: true
```

**解法 B：让你的本地 Postgres 监听 `0.0.0.0`**

修改 `postgresql.conf`：`listen_addresses = '*'`，并在 `pg_hba.conf` 里允许 Docker 网段连接，重启 Postgres。改完 `host.docker.internal:5432` 就能通。（更复杂，不推荐新手用。）

### ❓ 6.4 你的业务服务怎么连这个 Kafka？

详见 **第 9 章**。一句话：宿主机跑的代码用 `localhost:9092`，容器里跑的代码用 `kafka:29092`（需加入 `kafka-net`）。

### ❓ 6.5 多 broker 集群怎么纳管？

如果你之后扩展成多 broker（`kafka-1`、`kafka-2`、`kafka-3`）：

1. 每个 broker 容器都加入 `kafka-net`；
2. 每个 broker 的 `advertised.listeners` 中 `PLAINTEXT` 的地址用各自的容器名（不能是 `localhost`）；
3. Conduktor 里 Bootstrap Servers 填全部：`kafka-1:29092,kafka-2:29092,kafka-3:29092`。

> **关键**：集群里任何一个 broker 的 advertised 地址都不能是 `127.0.0.1`/`localhost`，否则 metadata 返回后会连不上。

---

## 第 7 章：可选扩展——加上 Schema Registry

如果你的消息用 Avro/Protobuf，想要完整的 schema 管理体验，可以再加一个独立的 Schema Registry 容器（同样挂到 `kafka-net`）。

在 `/Volumes/data/software/docker/containers/kafka/` 下新增一个文件 `schema-registry.yml`（保持与 Kafka 独立）：

```yaml
services:
  schema-registry:
    image: confluentinc/cp-schema-registry:7.7.1
    container_name: schema-registry
    restart: unless-stopped
    ports:
      - "8081:8081"
    environment:
      SCHEMA_REGISTRY_KAFKASTORE_BOOTSTRAP_SERVERS: "kafka:29092"
      SCHEMA_REGISTRY_HOST_NAME: schema-registry
      SCHEMA_REGISTRY_LISTENERS: "http://0.0.0.0:8081"
    networks:
      - kafka-net

networks:
  kafka-net:
    external: true
```

启动：`docker compose -f schema-registry.yml up -d`

Conduktor 纳管集群时，Schema Registry URL 填 `http://schema-registry:8081`。

---

## 第 8 章：日常运维命令速查

> 注意：Kafka 和 Conduktor 在各自的目录里，命令要在对应目录下执行。

### Kafka（在 `/Volumes/data/software/docker/containers/kafka/`）

```bash
docker compose ps                  # 看状态
docker compose logs -f kafka       # 看日志
docker compose restart kafka       # 重启
docker compose down                # 停止（保留数据）
docker compose down -v             # 停止并清空数据（慎用）
docker compose up -d               # 启动
```

### Conduktor（在 `/Volumes/data/software/docker/containers/conduktor/`）

```bash
docker compose ps
docker compose logs -f conduktor-console
docker compose restart conduktor-console
docker compose down
docker compose up -d
```

### 改配置后的标准流程（避免容器名冲突）

改了 `docker-compose.yml` 之后，**直接 `up -d` 会因为容器名已存在而报错**（见 6.0）。正确做法是先 `down` 再 `up`：

```bash
docker compose down        # 停掉并删除旧容器（数据卷保留，不丢数据）
# —— 编辑 docker-compose.yml ——
docker compose up -d       # 用新配置重建容器
```

| 命令 | 容器 | 数据卷 | 网络 |
|------|------|--------|------|
| `docker compose down` | 删除 | **保留** | 保留 |
| `docker compose down -v` | 删除 | **删除（丢数据）** | 删除（compose 自建的） |
| `docker compose restart` | 重启（**不读新配置**） | 保留 | 保留 |

> ⚠️ `restart` 只是重启容器，**不会应用你改的 compose 配置**。想让配置生效必须 `down` + `up`。
> ⚠️ 注意 `kafka-net` 是 external 网络，`docker compose down` 不会删它（它是手动建的），所以不用担心网络被误删。

### 网络相关

```bash
docker network ls                                  # 列出网络
docker network inspect kafka-net                   # 看谁挂在这个网络
docker network connect kafka-net <容器名>          # 把某容器挂进网络
docker network rm kafka-net                        # 删网络（需先断开所有容器）
```

### 数据库验证（Conduktor 表是否正常建出来）

```bash
# 看 conduktor 库里的表（应有一堆 flyway / cdk 开头的表，Conduktor 自动建的）
psql -U postgres -d conduktor -c "\dt"

# 或用 Docker postgres 客户端：
docker exec -it <pg容器名> psql -U postgres -d conduktor -c "\dt"
```

---

## 第 9 章：代码如何连接这个 Kafka（编程必看，最后做）⭐

部署 Kafka 不是为了好看，是为了**你的代码能稳定地收发消息**。这一章讲清楚不同场景下代码该填什么地址、怎么配、怎么验证。

### 9.1 先对号入座：你的代码跑在哪？

| 你的业务代码跑在哪 | 连接地址 | 为什么 |
|------------------|---------|--------|
| **宿主机直接跑**（IDE 里起 Spring Boot、`java -jar`、本地 Node/Python） | `localhost:9092` | 代码在 Mac 上，走 Kafka 映射出来的 9092 端口 |
| **另一个 Docker 容器里跑**（你的服务也容器化了） | `kafka:29092` | 容器之间用容器名通信，前提：你的服务容器也加入了 `kafka-net` |

> 🔑 记住：**宿主机代码 → `localhost:9092`；容器代码 → `kafka:29092`。** 这是整个手册对你写代码最有用的一句话。

### 9.2 场景一：Spring Boot 代码跑在宿主机（最常见）

#### 依赖（`pom.xml`）

```xml
<dependency>
    <groupId>org.springframework.kafka</groupId>
    <artifactId>spring-kafka</artifactId>
    <version>3.2.x</version>
</dependency>
```

#### 配置（`application.yml`）

```yaml
spring:
  kafka:
    # 代码跑在宿主机 → 用 localhost:9092
    bootstrap-servers: localhost:9092
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.apache.kafka.common.serialization.StringSerializer
      acks: all                  # 生产建议：所有副本确认
      enable-idempotence: true   # 幂等，防重复
    consumer:
      group-id: my-service
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      enable-auto-commit: false  # 手动提交 offset
      auto-offset-reset: earliest
```

#### 生产者代码

```java
@Service
public class MyProducer {
    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    public void send(String topic, String message) {
        kafkaTemplate.send(topic, message)
            .whenComplete((result, ex) -> {
                if (ex == null) {
                    log.info("发送成功 topic={} partition={} offset={}",
                        result.getRecordMetadata().topic(),
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset());
                } else {
                    log.error("发送失败: {}", message, ex);
                }
            });
    }
}
```

#### 消费者代码

```java
@Service
public class MyConsumer {
    @KafkaListener(topics = "my-topic", groupId = "my-service")
    public void onMessage(String message, Acknowledgment ack) {
        try {
            process(message);   // 业务处理
            ack.acknowledge();  // 处理成功才提交 offset
        } catch (Exception e) {
            log.error("处理失败，不提交 offset，等待重试: {}", message, e);
        }
    }
}
```

#### 跑起来后怎么验证连上了？

1. 代码里发一条消息；
2. 打开 Conduktor（`http://localhost:8080`）→ 进 **Local Kafka** → **Topics** → 对应 topic → **Messages**，能实时看到刚发的消息；
3. 同理，在 Conduktor 里手动 produce 一条，代码的消费者也能收到。

> 💡 **Conduktor 在这里的作用**：它是你代码的"可视化调试台"——代码发了什么、消费到哪了、有没有积压，全在 Conduktor 里直观看到，不用盯日志。

### 9.3 场景二：业务代码也跑在 Docker 容器里

如果你的 Spring Boot 服务也容器化了（比如和 Kafka 一起部署），代码的 `bootstrap-servers` 要改成 `kafka:29092`，并且**你的服务容器必须加入 `kafka-net` 网络**。

在你的业务服务的 `docker-compose.yml` 里：

```yaml
services:
  my-app:
    image: my-app:latest
    environment:
      # 容器之间用容器名
      SPRING_KAFKA_BOOTSTRAP_SERVERS: "kafka:29092"
    networks:
      - kafka-net   # ← 关键：加入共享网络

networks:
  kafka-net:
    external: true   # 复用手册第 1.2 步建的网络
```

> ⚠️ 如果不把 `my-app` 挂到 `kafka-net`，它解析不了 `kafka` 这个容器名，照样 `Connection refused`。挂上之后用 `docker exec my-app nc -vz kafka 29092` 验证通了再起服务。

### 9.4 三个角色的连接地址总表（贴墙上的那种）

| 角色 | 跑在哪 | 连接地址 |
|------|--------|---------|
| **你的业务代码**（本地 IDE 起） | 宿主机 | `localhost:9092` |
| **你的业务代码**（容器化） | Docker 容器（已加入 kafka-net） | `kafka:29092` |
| **Conduktor Console**（纳管用） | Docker 容器（已加入 kafka-net） | `kafka:29092` |
| **宿主机终端 / kcat 等工具** | 宿主机 | `localhost:9092` |

只要记住"**宿主机用 9092，容器用 29092**"，任何场景都不会配错。


---

## 第 10 章：最终架构图

```
┌──────────────────────────────────────────────────────────────┐
│                     Docker 网络: kafka-net                    │
│                       (external, 共享)                        │
│                                                              │
│   ┌─────────────┐              ┌──────────────────────┐      │
│   │   kafka     │  29092 容器间  │  conduktor-console   │      │
│   │  (9092对外) │ ◄──────────► │   纳管 kafka:29092    │      │
│   │  (9999 JMX) │   9999 JMX   │   8080 Web UI         │      │
│   └─────────────┘              └──────────┬───────────┘      │
│          │                                │                  │
└──────────┼────────────────────────────────┼──────────────────┘
           │ host.docker.internal           │ host.docker.internal
           ▼                                ▼
   ┌──────────────┐                 ┌────────────────┐
   │ 业务服务/终端  │                 │ 本地 PostgreSQL │
   │ :9092 连 Kafka │                 │ 库 conduktor    │
   └──────────────┘                 │ (独立库, public)│
                                    └────────────────┘

  独立容器①: /Volumes/data/software/docker/containers/kafka/
  独立容器②: /Volumes/data/software/docker/containers/conduktor/
```

**特点回顾：**
- 两个容器各自独立 compose，互不依赖生命周期
- 共享 `kafka-net` 网络，用容器名通信
- Kafka 对外用 `host.docker.internal:9092`，业务服务直连
- Conduktor 复用本地 Postgres，用独立的 `conduktor` 库，数据挂载到指定目录
- 任一容器单独停启，不影响另一个

---

## 附录：文件清单

部署完成后，你的目录结构应该是：

```
/Volumes/data/software/docker/containers/
├── kafka/
│   └── docker-compose.yml          # Kafka（第 2.2）
├── conduktor/
│   ├── docker-compose.yml          # Conduktor（第 3.2）
│   └── data/                       # Conduktor 数据卷挂载点
└── (可选) schema-registry.yml       # Schema Registry（第 7 章）
```

加上一个共享网络 `kafka-net`，整个体系就齐活了。

---

> **一句话总结**：建网络 → 两份独立 compose 各自 up → Conduktor 里填 `kafka:29092` 纳管。三个动作，清晰解耦，长期可用。
