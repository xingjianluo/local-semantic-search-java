package com.github.xingjianluo.localsemanticsearchjava.semantic.api;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * HTTP 端到端冒烟：写入 -> 查询 -> 可见性过滤 -> 删除 -> 重建。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SemanticSearchControllerTest {

    private static final Logger log = LoggerFactory.getLogger(SemanticSearchControllerTest.class);

    @Autowired
    private MockMvc mvc;

    @Test
    void fullHttpFlow() throws Exception {
        String upsert = """
                {"id":"doc1","content":"offline local semantic search engine","scopes":["public"]}
                """;
        mvc.perform(post("/api/semantic/documents").contentType(MediaType.APPLICATION_JSON).content(upsert))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed").value(true));

        // 重复写入幂等
        mvc.perform(post("/api/semantic/documents").contentType(MediaType.APPLICATION_JSON).content(upsert))
                .andExpect(jsonPath("$.changed").value(false));
        log.info("[HTTP] 重复写入 changed=false 幂等成立");

        String search = """
                {"query":"local semantic search offline","topK":5,"scopes":["public"]}
                """;
        mvc.perform(post("/api/semantic/search").contentType(MediaType.APPLICATION_JSON).content(search))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hits[0].documentId").value("doc1"))
                .andExpect(jsonPath("$.totalHits").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));
        log.info("[HTTP] public 查询命中 doc1");

        // 越界范围：secret 调用方看不到 public 文档，计数也为 0
        String forbidden = """
                {"query":"local semantic search offline","topK":5,"scopes":["secret"]}
                """;
        String forbiddenBody = mvc.perform(
                        post("/api/semantic/search").contentType(MediaType.APPLICATION_JSON).content(forbidden))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalHits").value(0))
                .andReturn().getResponse().getContentAsString();
        log.info("[HTTP] 越界查询返回体={}（不得包含 doc1）", forbiddenBody);
        assertTrue(!forbiddenBody.contains("doc1"), "返回信息中不得出现越界文档 id");

        // 删除
        mvc.perform(delete("/api/semantic/documents/doc1")).andExpect(jsonPath("$.deleted").value(true));
        // 重建
        mvc.perform(post("/api/semantic/rebuild"))
                .andExpect(jsonPath("$.status").value("SUCCESS"));
        log.info("[HTTP] 删除后重建成功");

        String health = mvc.perform(get("/api/semantic/health")).andReturn().getResponse().getContentAsString();
        log.info("[HTTP] health={}", health);
        assertTrue(health.contains("\"indexedDocuments\":0"));
    }
}
