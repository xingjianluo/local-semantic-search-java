package com.github.xingjianluo.localsemanticsearchjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 翻页重试语义的 REST 端到端：同一请求重发不跳页、收紧后数量一致且粘滞。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ScrollRetryApiTest {

    private static final Logger log = LoggerFactory.getLogger(ScrollRetryApiTest.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mvc;

    private void upsert(String id, String content, String scope) throws Exception {
        mvc.perform(post("/api/documents").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"" + id + "\",\"content\":\"" + content
                                + "\",\"scope\":\"" + scope + "\"}"))
                .andExpect(status().isOk());
    }

    private JsonNode scrollPage(String token, String body) throws Exception {
        MvcResult r = mvc.perform(post("/api/scroll/" + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body == null ? "{}" : body))
                .andExpect(status().isOk()).andReturn();
        return MAPPER.readTree(r.getResponse().getContentAsString());
    }

    private static List<String> hitIds(JsonNode page) {
        List<String> ids = new ArrayList<>();
        page.get("hits").forEach(h -> ids.add(h.get("id").asText()));
        return ids;
    }

    @Test
    void resendingSameHttpRequestReturnsSamePageAndDoesNotSkip() throws Exception {
        for (int i = 0; i < 15; i++) {
            upsert("retry-" + i, "重试 语义 检索 翻页 文档 " + i,
                    i % 2 == 0 ? "retry-a" : "retry-b");
        }
        String scopes = "[\"retry-a\",\"retry-b\"]";
        MvcResult firstResult = mvc.perform(post("/api/scroll").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"重试 语义 检索 翻页\",\"visibleScopes\":" + scopes
                                + ",\"pageSize\":5}"))
                .andExpect(status().isOk()).andReturn();
        JsonNode first = MAPPER.readTree(firstResult.getResponse().getContentAsString());
        String page1Token = first.get("nextScrollId").asText();

        // 客户端超时，把同一个翻页 HTTP 请求原样重发两次：必须仍是同一页。
        JsonNode retry1 = scrollPage(page1Token, "{}");
        JsonNode retry2 = scrollPage(page1Token, "{}");
        assertEquals(hitIds(retry1), hitIds(retry2), "同一请求重发必须返回同一页");
        assertEquals(retry1.get("nextScrollId").asText(), retry2.get("nextScrollId").asText());
        assertEquals(retry1.get("returned").asInt(), retry2.get("returned").asInt());

        // 修复前的 bug：重发会推进游标，第二次重发拿到的其实是第 3 页，第 2 页永久丢失。
        JsonNode page2 = scrollPage(retry1.get("nextScrollId").asText(), "{}");
        assertNotEquals(hitIds(retry1), hitIds(page2), "新凭据必须推进到下一页");
        log.info("同一翻页 HTTP 请求重发 2 次均返回 {}（第 2 页），新凭据才推进到 {}（第 3 页）",
                hitIds(retry1), hitIds(page2));

        // 用新凭据一路翻完，与一次性整体检索逐位一致。
        MvcResult wholeResult = mvc.perform(post("/api/search").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"重试 语义 检索 翻页\",\"visibleScopes\":" + scopes
                                + ",\"topK\":100}"))
                .andExpect(status().isOk()).andReturn();
        List<String> expected = new ArrayList<>();
        MAPPER.readTree(wholeResult.getResponse().getContentAsString())
                .get("hits").forEach(h -> expected.add(h.get("id").asText()));

        List<String> paged = new ArrayList<>(hitIds(first));
        paged.addAll(hitIds(retry1));
        JsonNode node = page2;
        while (true) {
            paged.addAll(hitIds(node));
            if (node.get("nextScrollId").isNull()) {
                break;
            }
            node = scrollPage(node.get("nextScrollId").asText(), "{}");
        }
        assertEquals(expected, paged, "重发后整轮仍不重不漏、顺序与整体检索一致");
    }

    @Test
    void totalHitsTracksTightenedScopeAndSticksOverHttp() throws Exception {
        // 使用独立 scope 标签，与同类其他测试方法隔离（@SpringBootTest 共享应用上下文与数据）。
        upsert("ta1", "收紧 数量 语义 检索 甲", "tight-a");
        upsert("ta2", "收紧 数量 语义 检索 乙", "tight-a");
        upsert("tb1", "收紧 数量 语义 检索 丙", "tight-b");
        upsert("tb2", "收紧 数量 语义 检索 丁", "tight-b");

        MvcResult firstResult = mvc.perform(post("/api/scroll").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"收紧 数量 语义 检索\",\"visibleScopes\":"
                                + "[\"tight-a\",\"tight-b\"],\"pageSize\":1}"))
                .andExpect(status().isOk()).andReturn();
        JsonNode first = MAPPER.readTree(firstResult.getResponse().getContentAsString());
        assertEquals(4, first.get("totalHits").asInt(), "初始范围下总数为 4");
        String token = first.get("nextScrollId").asText();

        // 收紧为 tight-a：数量立即变为 2。
        JsonNode tightened = scrollPage(token, "{\"visibleScopes\":[\"tight-a\"]}");
        assertEquals(2, tightened.get("totalHits").asInt(), "收紧后 totalHits 必须为 2");
        assertEquals("tight-a", tightened.get("hits").get(0).get("scope").asText());

        // 之后省略范围：粘滞在 tight-a，tight-b 不回弹，数量仍为 2。
        String token2 = tightened.get("nextScrollId").asText();
        JsonNode sticky = scrollPage(token2, "{}");
        assertEquals(2, sticky.get("totalHits").asInt(), "省略范围时数量不得回弹为 4");
        sticky.get("hits").forEach(h ->
                assertEquals("tight-a", h.get("scope").asText(), "越界内容不得再出现"));

        // 即便把 tight-b 又带回来（仍是初始子集），数量依旧是 2。
        if (!sticky.get("nextScrollId").isNull()) {
            JsonNode cannotWiden = scrollPage(sticky.get("nextScrollId").asText(),
                    "{\"visibleScopes\":[\"tight-a\",\"tight-b\"]}");
            assertEquals(2, cannotWiden.get("totalHits").asInt(), "收紧不可放松");
            cannotWiden.get("hits").forEach(h ->
                    assertEquals("tight-a", h.get("scope").asText()));
        }
        log.info("HTTP 层收紧后 totalHits 4 -> 2，省略范围/再带 b 均粘滞不回弹");
    }
}
