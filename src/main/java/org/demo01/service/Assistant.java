package org.demo01.service;

import dev.langchain4j.service.TokenStream;

public interface Assistant {
    TokenStream chat(String message);
}
