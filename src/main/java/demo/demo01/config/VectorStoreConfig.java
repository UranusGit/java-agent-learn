package demo.demo01.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class VectorStoreConfig {
    @Bean
    public VectorStore vectorStore(JdbcTemplate template, EmbeddingModel embeddingModel) {
        return PgVectorStore.builder(template, embeddingModel).build();
    }

    @Bean
    public TokenTextSplitter tokenTextSplitter() {
        return TokenTextSplitter.builder()
                .withChunkSize(800) // 每个chunk的目标token数
                .withMinChunkSizeChars(100) // chunk 内段落最小字符数
                .withMinChunkLengthToEmbed(5) // 短于改长度的chunk 跳过嵌入
                .withMaxNumChunks(10000) // 单文档最大 chunk 数
                .withKeepSeparator(true) // 切认识保留分隔符
                .build();
    }
}
