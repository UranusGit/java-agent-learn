# Sprint 2 · 意图分类与话题聚类

> P25 ConversationBI · 第 2 周

---

## 目标

用 LLM 对对话进行意图分类，用嵌入向量做话题聚类。

## 任务清单

- [ ] 意图分类体系定义
- [ ] LLM 批量意图分类
- [ ] 嵌入向量生成
- [ ] K-Means 话题聚类
- [ ] 聚类标签自动生成

## 意图分类

```java
@Component
public class IntentClassifier {
    /**
     * 批量分类（一次调用处理多条，降低成本）
     */
    public Map<String, String> classifyBatch(List<String> messages) {
        String prompt = "请对以下消息分类意图，JSON 数组返回：\n";
        for (int i = 0; i < messages.size(); i++) {
            prompt += (i+1) + ". " + messages.get(i) + "\n";
        }
        String result = chatClient.prompt().user(prompt).call().content();
        return parse(result);
    }
}
```

## 话题聚类

```java
@Component
public class TopicClusterer {
    public List<TopicCluster> cluster(List<String> conversations, int k) {
        // 1. 批量嵌入
        List<float[]> embeddings = conversations.stream()
                .map(embeddingModel::embed).toList();

        // 2. K-Means
        var clusters = kmeans(embeddings, k);

        // 3. LLM 生成标签
        return clusters.stream()
                .map(c -> new TopicCluster(
                    llmLabel(c.samples()),
                    c.size(),
                    c.percentage()
                )).toList();
    }
}
```

## 验收

- [ ] 意图分类覆盖率 > 90%
- [ ] 话题聚类结果有意义（非随机）
- [ ] 每个聚类有人类可读的标签
- [ ] 能识别热门话题 Top 10
