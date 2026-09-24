package com.github.xingjianluo.localsemanticsearchjava.semantic;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.github.xingjianluo.localsemanticsearchjava.semantic.core.SemanticSearchService;
import com.github.xingjianluo.localsemanticsearchjava.semantic.store.DocumentStore;
import com.github.xingjianluo.localsemanticsearchjava.semantic.vectorize.HashingVectorizer;
import com.github.xingjianluo.localsemanticsearchjava.semantic.vectorize.TextVectorizer;

/**
 * 本地语义检索组件装配（骨架）。
 */
@Configuration
public class SemanticConfig {

    @Bean
    public TextVectorizer textVectorizer() {
        return new HashingVectorizer();
    }

    @Bean
    public DocumentStore documentStore() {
        return new DocumentStore();
    }

    @Bean
    public SemanticSearchService semanticSearchService(DocumentStore store, TextVectorizer vectorizer) {
        return new SemanticSearchService(store, vectorizer);
    }
}
