# Sprint 4 · 版本管理与评分推荐

> P23 PluginHub · 第 4 周

---

## 目标

实现插件版本管理、用户评分评价、个性化推荐。

## 任务清单

- [ ] 插件版本管理（多版本共存 + 自动升级）
- [ ] 用户评分（1-5 星）
- [ ] 用户评价（文字评论）
- [ ] 下载量统计
- [ ] 热门推荐

## 版本管理

```java
@Entity
public class PluginVersion {
    @Id private String versionId;     // pluginId + ":" + version
    private String pluginId;
    private String version;           // 1.0.0
    private String jarUrl;
    private String releaseNotes;
    private Instant createdAt;
    private int downloadCount;
    private boolean isLatest;
}

@PostMapping("/{pluginId}/versions")
public PluginVersion publishVersion(@PathVariable String pluginId,
                                    @RequestParam MultipartFile jar,
                                    @RequestParam String version,
                                    @RequestParam String notes) {
    // 标记旧版本 isLatest=false
    pluginVersionRepo.clearLatest(pluginId);
    // 保存新版本
    return pluginVersionRepo.save(new PluginVersion(...));
}

@PostMapping("/{pluginId}/upgrade")
public void upgrade(@PathVariable String pluginId, @RequestParam String toVersion) {
    pluginManager.unloadPlugin(pluginId);
    Path jar = pluginService.download(pluginId, toVersion);
    pluginManager.loadPlugin(jar);
}
```

## 评分评价

```java
@PostMapping("/{pluginId}/rate")
public void rate(@PathVariable String pluginId,
                 @RequestParam int stars,
                 @RequestParam(required = false) String comment) {
    ratingRepo.save(new Rating(pluginId, currentUser(), stars, comment, Instant.now()));
    // 更新插件平均分
    pluginService.updateRating(pluginId);
}

@GetMapping("/{pluginId}/reviews")
public Page<Review> reviews(@PathVariable String pluginId,
                            @RequestParam(defaultValue = "0") int page) {
    return ratingRepo.findByPluginId(pluginId, PageRequest.of(page, 10));
}
```

## 推荐逻辑

```java
public List<PluginCard> recommend(String userId) {
    // 1. 基于已安装插件推荐同类
    List<String> categories = getInstalledCategories(userId);
    // 2. 按下载量 + 评分排序
    return pluginService.topRatedByCategories(categories, 10);
}
```

## 验收

- [ ] 插件支持多版本发布
- [ ] 用户可升级到新版本
- [ ] 用户能评分和写评价
- [ ] 插件详情页显示平均分和评价
- [ ] 首页展示热门推荐
