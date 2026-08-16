package demo.demo01.service;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class KnowledgeBaseLoader implements CommandLineRunner {
    @Autowired
    private VectorStore vectorStore;

    @Autowired
    private TokenTextSplitter splitter;

    @Override
    public void run(String... args) throws Exception {
        MarkdownDocumentReader reader = new MarkdownDocumentReader(
                new ClassPathResource("changelog.txt"),
                MarkdownDocumentReaderConfig.builder()
                        .withIncludeCodeBlock(false)
                        .withIncludeBlockquote(true) // 手册中的 【注意/警告】引用块是客服问答的高质量问题
                        .build()
        );
        List<Document> docs = reader.get();
        List<Document> chunks = splitter.apply(docs);
        vectorStore.add(chunks);
    }
}
