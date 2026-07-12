package org.demo01.rag;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;

public class Test02 {
    public interface Assistant {
        String chat(String message);
    }

    public static void main(String[] args) throws Exception {
        // 对话模型
        OpenAiChatModel model = OpenAiChatModel.builder()
                .baseUrl("https://api.deepseek.com")
                .apiKey(System.getenv("API_KEY"))
                .modelName("deepseek-v4-flash")
                .temperature(0.7)
                .build();

        // 向量化：强制 HTTP/1.1，规避 JDK HTTP/2 与 LM Studio 的兼容性问题
        EmbeddingModel embeddingModel = OpenAiEmbeddingModel.builder()
                .baseUrl("http://127.0.0.1:1234/v1")
                .apiKey("lm-studio")
                .modelName("text-embedding-bge-large-zh-v1.5")
                .httpClientBuilder(new JdkHttpClientBuilder()
                        .httpClientBuilder(HttpClient.newBuilder()
                                .version(HttpClient.Version.HTTP_1_1))
                        .readTimeout(Duration.ofSeconds(60)))
                .build();

        // 向量库：从 Test01 持久化的 JSON 加载
        EmbeddingStore<TextSegment> store = InMemoryEmbeddingStore.fromFile(Path.of("docs/embeddings.json"));

        // 检索器
        EmbeddingStoreContentRetriever retriever = EmbeddingStoreContentRetriever.builder()
                .embeddingStore(store)
                .embeddingModel(embeddingModel)
                .maxResults(5)
                .minScore(0.5)
                .build();

        // AiService 整合 RAG
        Assistant agent = AiServices.builder(Assistant.class)
                .chatModel(model)
                .contentRetriever(retriever)
                .chatMemory(MessageWindowChatMemory.withMaxMessages(20))
                .build();

        System.out.println(agent.chat("你们的退货政策是什么"));
    }
}
