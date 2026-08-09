# Sprint 2 · 插件市场（发布/浏览/安装）

> P23 PluginHub · 第 2 周

---

## 目标

实现插件市场——开发者发布插件、用户浏览搜索、一键安装。

## 任务清单

- [ ] 插件发布接口（上传 JAR + 元数据）
- [ ] 插件目录（分类/搜索/排序）
- [ ] 一键安装（下载 JAR → 加载）
- [ ] 插件详情页（功能/权限/变更日志）
- [ ] 管理界面（已安装/启用/禁用/卸载）

## 核心 API

```java
@RestController
@RequestMapping("/api/plugins")
public class PluginMarketController {

    // 发布插件
    @PostMapping("/publish")
    public PublishResponse publish(@RequestParam MultipartFile jar,
                                    @RequestParam String metadata) {
        String pluginId = pluginService.upload(jar, metadata);
        return new PublishResponse(pluginId, "pending_review");
    }

    // 浏览目录
    @GetMapping("/catalog")
    public Page<PluginCard> catalog(@RequestParam(defaultValue = "popular") String sort,
                                     @RequestParam(required = false) String category,
                                     @RequestParam(defaultValue = "0") int page) {
        return pluginService.catalog(sort, category, page);
    }

    // 搜索
    @GetMapping("/search")
    public List<PluginCard> search(@RequestParam String q) {
        return pluginService.search(q);
    }

    // 安装
    @PostMapping("/{pluginId}/install")
    public InstallResponse install(@PathVariable String pluginId) {
        pluginManager.loadPlugin(pluginService.download(pluginId));
        return new InstallResponse("installed");
    }

    // 卸载
    @DeleteMapping("/{pluginId}")
    public void uninstall(@PathVariable String pluginId) {
        pluginManager.unloadPlugin(pluginId);
    }

    // 已安装列表
    @GetMapping("/installed")
    public List<InstalledPlugin> installed() {
        return pluginManager.listPlugins().stream()
                .map(this::toInstalledView).toList();
    }
}
```

## 验收

- [ ] 开发者能上传插件 JAR 并填写元数据
- [ ] 用户能浏览插件目录并搜索
- [ ] 一键安装后插件立即可用
- [ ] 能查看已安装插件列表
- [ ] 卸载后插件完全移除
