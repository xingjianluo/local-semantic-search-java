package com.github.xingjianluo.localsemanticsearchjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 分页遍历 REST 端到端：完整翻页、门槛叠加、范围收紧、
 * 翻页期间写入返回 409 STALE、损坏令牌返回 400。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PagingApiTest {

    private static final Logger log = LoggerFactory.getLogger(PagingApiTest.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mvc;

    private MvcResult postPage(String body) throws Exception {
        return mvc.perform(post("/api/search/page")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();
    }

    @Test
    @Order(1)
    void pagingOverHttpCoversAllAndDetectsStaleAndInvalidToken() throws Exception {
        // 写入 55 篇 a 范围、55 篇 b 范围文档
        for (int i = 0; i < 55; i++) {
            upsert("pa-" + i, "分页 接口 文档 甲 " + i, "page-a");
            upsert("pb-" + i, "分页 接口 文档 乙 " + i, "page-b");
        }

        // 一次性全量（a 范围）作为对照
        JsonNode full = searchFull("分页 接口 文档", "[\"page-a\"]", null);
        int fullCount = full.path("count").asInt();
        assertEquals(55, fullCount);

        // 分页翻完 a 范围，pageSize=20 => 3 页
        List<String> pagedIds = new ArrayList<>();
        List<String> fullIds = new ArrayList<>();
        for (JsonNode h : full.path("hits")) {
            fullIds.add(h.path("id").asText());
        }
        String pageToken = null;
        long anchorVersion = -1;
        int pages = 0;
        while (true) {
            String body = pageBody("分页 接口 文档", "[\"page-a\"]", null, 20, pageToken);
            MvcResult result = postPage(body);
            assertEquals(200, result.getResponse().getStatus());
            JsonNode json = MAPPER.readTree(result.getResponse().getContentAsString());
            assertEquals("OK", json.path("status").asText());
            assertEquals(fullCount, json.path("totalCount").asInt());
            if (anchorVersion == -1) {
                anchorVersion = json.path("indexVersion").asLong();
            } else {
                assertEquals(anchorVersion, json.path("indexVersion").asLong());
            }
            for (JsonNode h : json.path("hits")) {
                pagedIds.add(h.path("id").asText());
                assertEquals("page-a", h.path("scope").asText());
            }
            pages++;
            pageToken = json.path("nextPageToken").isNull() ? null
                    : json.path("nextPageToken").asText();
            if (pageToken == null) {
                assertFalse(json.path("hasMore").asBoolean());
                break;
            }
            assertTrue(json.path("hasMore").asBoolean());
        }
        assertEquals(fullIds, pagedIds);
        assertEquals(fullIds.size(), new LinkedHashSet<>(pagedIds).size());
        log.info("输入: HTTP 分页（55 条 page-a，pageSize=20）；页数={}，拼接 {} 条，totalCount={}；"
                + "判定依据: 逐页拼接与全量检索顺序一致、不重不漏，totalCount 各页恒定",
                pages, pagedIds.size(), fullCount);

        // 门槛：取全量第 5 名得分做门槛，返回的每条都不低于它
        double threshold = full.path("hits").get(4).path("score").asDouble();
        MvcResult cutResult = postPage(pageBody("分页 接口 文档", "[\"page-a\"]", threshold, 100, null));
        assertEquals(200, cutResult.getResponse().getStatus());
        JsonNode cut = MAPPER.readTree(cutResult.getResponse().getContentAsString());
        assertTrue(cut.path("totalCount").asInt() >= 5);
        for (JsonNode h : cut.path("hits")) {
            assertTrue(h.path("score").asDouble() >= threshold, "低于门槛的内容不得出现");
        }

        // 翻页途中写入：409 + STALE
        JsonNode firstPage = MAPPER.readTree(postPage(
                pageBody("分页 接口 文档", "[\"page-a\",\"page-b\"]", null, 30, null))
                .getResponse().getContentAsString());
        String cursor = firstPage.path("nextPageToken").asText();
        upsert("pa-extra", "分页 接口 文档 甲 新增", "page-a");
        MvcResult staleResult = postPage(
                pageBody("分页 接口 文档", "[\"page-a\",\"page-b\"]", null, 30, cursor));
        assertEquals(409, staleResult.getResponse().getStatus());
        JsonNode stale = MAPPER.readTree(staleResult.getResponse().getContentAsString());
        assertEquals("STALE", stale.path("status").asText());
        assertEquals(0, stale.path("hits").size());
        log.info("输入: 翻页途中新增文档后续翻；HTTP {} status={}；判定依据: 必须明确告知本轮作废",
                staleResult.getResponse().getStatus(), stale.path("status").asText());

        // 损坏令牌：400 + INVALID_TOKEN
        MvcResult bad = postPage(pageBody("分页 接口 文档", "[\"page-a\"]", null, 20, "%%%not-a-token%%%"));
        assertEquals(400, bad.getResponse().getStatus());
        JsonNode badJson = MAPPER.readTree(bad.getResponse().getContentAsString());
        assertEquals("INVALID_TOKEN", badJson.path("status").asText());

        // 范围收紧中途：从 [a,b] 起，第二页起只给 [a]，不得越界、不重复
        List<String> ids = new ArrayList<>();
        String token = null;
        for (int p = 0; p < 20; p++) {
            String scopes = p == 0 ? "[\"page-a\",\"page-b\"]" : "[\"page-a\"]";
            JsonNode page = MAPPER.readTree(postPage(pageBody("分页 接口 文档", scopes, null, 25, token))
                    .getResponse().getContentAsString());
            assertEquals("OK", page.path("status").asText());
            for (JsonNode h : page.path("hits")) {
                String scope = h.path("scope").asText();
                assertTrue(List.of("page-a", "page-b").contains(scope));
                if (p > 0) {
                    assertEquals("page-a", scope, "收紧后不得再带出 b 范围内容");
                }
                assertTrue(ids.add(h.path("id").asText()), "同一内容不得翻到两次");
            }
            token = page.path("nextPageToken").isNull() ? null : page.path("nextPageToken").asText();
            if (token == null) {
                break;
            }
        }
        long aSeen = ids.stream().filter(id -> id.startsWith("pa-")).count();
        assertEquals(56, aSeen, "a 范围（含新增）必须完整出现，无整段跳过");
        log.info("输入: HTTP 翻页首页可见 [a,b]，之后收紧为 [a]；a 文档 {} 条，b 文档仅可能出现在首页，"
                + "无重复；判定依据: 收紧后越界内容不再出现，a 范围未翻过的部分完整补齐", aSeen);
    }

    private void upsert(String id, String content, String scope) throws Exception {
        String body = "{\"id\":\"" + id + "\",\"content\":\"" + content + "\",\"scope\":\"" + scope + "\"}";
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/documents").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    private JsonNode searchFull(String query, String scopesJson, Double minScore) throws Exception {
        String body = "{\"query\":\"" + query + "\",\"visibleScopes\":" + scopesJson
                + (minScore == null ? "" : ",\"minScore\":" + minScore) + ",\"topK\":100000}";
        MvcResult result = mvc.perform(post("/api/search").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        return MAPPER.readTree(result.getResponse().getContentAsString());
    }

    private String pageBody(String query, String scopesJson, Double minScore,
                            Integer pageSize, String pageToken) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"query\":\"").append(query).append("\",\"visibleScopes\":").append(scopesJson);
        if (minScore != null) {
            sb.append(",\"minScore\":").append(minScore);
        }
        if (pageSize != null) {
            sb.append(",\"pageSize\":").append(pageSize);
        }
        if (pageToken != null) {
            sb.append(",\"pageToken\":\"").append(pageToken).append("\"");
        }
        sb.append("}");
        return sb.toString();
    }
}
