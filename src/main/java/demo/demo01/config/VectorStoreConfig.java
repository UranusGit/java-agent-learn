package demo.demo01.config;

import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class VectorStoreConfig {
    @Bean
    public TokenTextSplitter tokenTextSplitter() {
        return TokenTextSplitter.builder()
                .withChunkSize(800) // 每个chunk的目标 token 数
                .withMinChunkSizeChars(100) // chunk 内段落最小字符数
                .withMinChunkLengthToEmbed(5) // 短于该长度的 chunk 跳过嵌入
                .withMaxNumChunks(10000) // 单文档最大 chunk 数
                .withKeepSeparator(true) // 切分时保留分隔符
                .build();
    }
}
