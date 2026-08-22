package demo.demo01.runner;

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
    private VectorStore store;

    @Autowired
    private TokenTextSplitter splitter;

    @Override
    public void run(String... args) throws Exception {
        MarkdownDocumentReader reader = new MarkdownDocumentReader(
                new ClassPathResource("manual/产品手册.md"),
                MarkdownDocumentReaderConfig.builder()
                        .withIncludeCodeBlock(false)
                        .withIncludeBlockquote(true)
                        .build()
        );

        List<Document> docs = reader.get();
        List<Document> chunks = splitter.apply(docs);
        store.add(chunks);
    }
}
