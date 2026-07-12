package org.demo01.rag;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.document.parser.TextDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;

import java.net.http.HttpClient;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

public class Test01 {
    public static void main(String[] args) throws Exception {
        // 加载文件
        TextDocumentParser parser = new TextDocumentParser(Charset.defaultCharset());
        Document doc = FileSystemDocumentLoader
                .loadDocument(Path.of("docs/产品手册.txt"), parser);

        // 分块
        DocumentSplitter splitter = DocumentSplitters
                .recursive(300, 30);
        List<TextSegment> segments = splitter.split(doc);
        segments.forEach(seg -> {
            seg.metadata().put("department", "运营");
        });
        System.out.println("分块数：" + segments.size());

        // 向量化：强制 HTTP/1.1，规避 JDK HTTP/2 与 LM Studio 的兼容性问题
        EmbeddingModel embedModel = OpenAiEmbeddingModel.builder()
                .baseUrl("http://127.0.0.1:1234/v1")
                .apiKey("lm-studio")
                .modelName("text-embedding-bge-large-zh-v1.5")
                .httpClientBuilder(new JdkHttpClientBuilder()
                        .httpClientBuilder(HttpClient.newBuilder()
                                .version(HttpClient.Version.HTTP_1_1))
                        .readTimeout(Duration.ofSeconds(60)))
                .build();

        List<Embedding> embeddings = embedModel.embedAll(segments).content();

        // 入库（InMemoryEmbeddingStore + 持久化到 JSON 文件）
        InMemoryEmbeddingStore<TextSegment> store = new InMemoryEmbeddingStore<>();
        store.addAll(embeddings, segments);

        // 持久化到文件，供 Test02 加载
        Path indexFile = Path.of("docs/embeddings.json");
        Files.writeString(indexFile, store.serializeToJson());

        System.out.println("索引完成，共 " + embeddings.size() + " 个向量，已写入 " + indexFile);
    }
}
