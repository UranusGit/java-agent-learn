# Sprint 1 · 插件 SPI 规范与加载引擎

> P23 PluginHub · 第 1 周

---

## 目标

定义 Agent 插件 SPI 规范，实现基于 ClassLoader 的动态加载引擎。

## 任务清单

- [ ] 定义 `AgentPlugin` 接口
- [ ] 定义插件元数据（PluginMetadata）
- [ ] 实现插件 ClassLoader（child-first）
- [ ] 实现插件管理器（发现/加载/初始化/卸载）
- [ ] 编写示例插件

## 核心代码

### SPI 接口

```java
public interface AgentPlugin {
    PluginMetadata metadata();
    List<ToolDefinition> tools();
    void initialize(PluginContext ctx) throws PluginException;
    void destroy();
}

record PluginMetadata(
    String id, String name, String version,
    String author, String description,
    String minAgentVersion, Permissions permissions
) {}
```

### ClassLoader

```java
public class PluginClassLoader extends URLClassLoader {
    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        Class<?> loaded = findLoadedClass(name);
        if (loaded != null) return loaded;
        try {
            Class<?> cls = findClass(name); // child-first
            if (resolve) resolveClass(cls);
            return cls;
        } catch (ClassNotFoundException e) {
            return super.loadClass(name, resolve);
        }
    }
}
```

### 加载引擎

```java
@Component
public class PluginManager {
    private final Map<String, AgentPlugin> plugins = new ConcurrentHashMap<>();

    public void loadPlugin(Path jarPath) {
        var cl = new PluginClassLoader(new URL[]{jarPath.toUri().toURL()}, getClass().getClassLoader());
        var loader = ServiceLoader.load(AgentPlugin.class, cl);
        AgentPlugin plugin = loader.iterator().next();
        plugin.initialize(new PluginContext(plugin.metadata().id()));
        plugins.put(plugin.metadata().id(), plugin);
    }

    public void unloadPlugin(String id) {
        AgentPlugin p = plugins.remove(id);
        if (p != null) p.destroy();
    }

    public List<ToolDefinition> getAllTools() {
        return plugins.values().stream().flatMap(p -> p.tools().stream()).toList();
    }
}
```

## 验收

- [ ] 插件 JAR 放入目录后能自动发现
- [ ] 插件的工具能注册到 Agent
- [ ] 卸载插件后工具不再可用
- [ ] 两个插件依赖不同版本的库不冲突
