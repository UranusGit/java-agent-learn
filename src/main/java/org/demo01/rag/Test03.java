package org.demo01.rag;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;

public class Test03 {
    public interface Assistant {
        String chat(String message);
    }

    public static void main(String[] args) {
        // 对话模型
        OpenAiChatModel model = OpenAiChatModel.builder()
                .baseUrl("https://api.deepseek.com")
                .apiKey(System.getenv("API_KEY"))
                .modelName("deepseek-v4-flash")
                .temperature(0.7)
                .logRequests(true)
                .build();

        // 强制 HTTP/1.1，规避 JDK HTTP/2 与 LM Studio 握手后挂起的问题
        HttpClient.Builder httpBuilder = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(15));

        EmbeddingModel embeddingModel = OpenAiEmbeddingModel.builder()
                .httpClientBuilder(new JdkHttpClientBuilder().httpClientBuilder(httpBuilder))
                .baseUrl("http://127.0.0.1:1234/v1")
                .apiKey("lm-studio")
                .modelName("text-embedding-bge-large-zh-v1.5")
                .timeout(Duration.ofSeconds(60))
                .build();

        // 向量库：从 Test01 持久化的 JSON 加载
        EmbeddingStore<TextSegment> store = InMemoryEmbeddingStore.fromFile(Path.of("docs/data/embeddings.json"));


        ContentRetriever retriever = EmbeddingStoreContentRetriever.builder()
                .embeddingStore(store)
                .embeddingModel(embeddingModel)
                .maxResults(5)
                .minScore(0.7)
                .dynamicFilter(query ->
                        MetadataFilterBuilder.metadataKey("department").isEqualTo("运营"))
                .dynamicMaxResults(query -> query.text().length() > 30 ? 8 : 3)
                .build();

        Assistant agent = AiServices.builder(Assistant.class)
                .chatModel(model)
                .contentRetriever(retriever)
                .build();

        System.out.println(agent.chat("公司的退货政策是什么"));
    }
}
