package org.demo04.service;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class SimpleIngestionService {
    @Autowired
    private VectorStore vectorStore;

    public int ingestPdf(Resource pdf) {
        // 1. 读 PDF（按页）
        PagePdfDocumentReader reader = new PagePdfDocumentReader(pdf);
        List<Document> pages = reader.get();

        // 2. 切分（用框架自带的 TokenTextSplitter，先不管参数）
        List<Document> chunks = TokenTextSplitter.builder().build().split(pages);
        chunks.stream()
                .forEach(document -> System.out.println(document.getText()));

        // 3. 向量化 + 入库（VectorStore 内部会调 EmbeddingModel）
        vectorStore.add(chunks);
        return chunks.size();
    }
}
