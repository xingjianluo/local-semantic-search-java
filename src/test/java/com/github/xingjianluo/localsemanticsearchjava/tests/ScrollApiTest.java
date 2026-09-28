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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 分页遍历 REST 端到端：完整翻完、顺序一致、门槛过滤、范围收紧、
 * 凭据失效 404、参数非法 400。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ScrollApiTest {

    private static final Logger log = LoggerFactory.getLogger(ScrollApiTest.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mvc;

    private void upsert(String id, String content, String scope) throws Exception {
        mvc.perform(post("/api/documents").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"" + id + "\",\"content\":\"" + content
                                + "\",\"scope\":\"" + scope + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void scrollDrainsAllAndMatchesSearchOrder() throws Exception {
        for (int i = 0; i < 25; i++) {
            upsert("scroll-" + i, "分页 遍历 语义 检索 文档 " + i, i % 2 == 0 ? "team-a" : "team-b");
        }
        String scopes = "[\"team-a\",\"team-b\"]";

        // 一次性整体（topK 足够大）作为期望顺序。
        MvcResult wholeResult = mvc.perform(post("/api/search").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"分页 遍历 语义 检索\",\"visibleScopes\":" + scopes
                                + ",\"topK\":100}"))
                .andExpect(status().isOk()).andReturn();
        JsonNode whole = MAPPER.readTree(wholeResult.getResponse().getContentAsString());
        List<String> expected = new ArrayList<>();
        whole.get("hits").forEach(h -> expected.add(h.get("id").asText()));

        List<String> paged = new ArrayList<>();
        long version = -1;
        int totalHits = -1;
        MvcResult firstResult = mvc.perform(post("/api/scroll").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"分页 遍历 语义 检索\",\"visibleScopes\":" + scopes
                                + ",\"pageSize\":7}"))
                .andExpect(status().isOk()).andReturn();
        JsonNode page = MAPPER.readTree(firstResult.getResponse().getContentAsString());
        totalHits = page.get("totalHits").asInt();
        version = page.get("indexVersion").asLong();
        page.get("hits").forEach(h -> paged.add(h.get("id").asText()));

        String scrollId = page.has("nextScrollId") && !page.get("nextScrollId").isNull()
                ? page.get("nextScrollId").asText() : null;
        while (scrollId != null) {
            MvcResult r = mvc.perform(post("/api/scroll/" + scrollId)
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isOk()).andReturn();
            page = MAPPER.readTree(r.getResponse().getContentAsString());
            assertEquals(version, page.get("indexVersion").asLong(), "整轮版本固定");
            assertEquals(totalHits, page.get("totalHits").asInt(), "整轮 totalHits 固定");
            page.get("hits").forEach(h -> paged.add(h.get("id").asText()));
            scrollId = page.has("nextScrollId") && !page.get("nextScrollId").isNull()
                    ? page.get("nextScrollId").asText() : null;
        }

        log.info("输入: 25 篇文档，页大小 7 翻完；整体顺序 {} 条，翻页 {} 条，totalHits={}；"
                + "判定依据: 跨页拼接顺序等于一次性检索，不重不漏", expected.size(), paged.size(), totalHits);
        assertEquals(expected, paged);
        assertEquals(expected.size(), totalHits);
        assertEquals(expected.size(), new HashSet<>(paged).size());
    }

    @Test
    void minScoreFiltersSearchAndScroll() throws Exception {
        // 一篇高度相关，一篇完全无关。
        upsert("rel", "语义 检索 语义 检索 引擎", "team-a");
        upsert("irr", "zzz qqq unrelated xyz", "team-a");

        // 先拿相关文档的真实得分作为门槛。
        MvcResult relResult = mvc.perform(post("/api/search").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"语义 检索 引擎\",\"visibleScopes\":[\"team-a\"],\"topK\":10}"))
                .andExpect(status().isOk()).andReturn();
        JsonNode relJson = MAPPER.readTree(relResult.getResponse().getContentAsString());
        double relScore = relJson.get("hits").get(0).get("score").asDouble();
        double threshold = relScore; // 含边界：相关文档必须保留

        // 普通 search 叠加门槛：只剩相关文档，count 不包含被过滤项。
        MvcResult filtered = mvc.perform(post("/api/search").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"语义 检索 引擎\",\"visibleScopes\":[\"team-a\"],"
                                + "\"topK\":10,\"minScore\":" + threshold + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.hits[0].id").value("rel"))
                .andReturn();

        // scroll 同样叠加门槛。
        MvcResult sc = mvc.perform(post("/api/scroll").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"语义 检索 引擎\",\"visibleScopes\":[\"team-a\"],"
                                + "\"pageSize\":10,\"minScore\":" + threshold + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalHits").value(1))
                .andExpect(jsonPath("$.hits[0].id").value("rel"))
                .andExpect(jsonPath("$.nextScrollId").doesNotExist())
                .andReturn();
        log.info("输入: 相关+无关各一篇，门槛={}；search 响应={}；scroll 响应={}；"
                + "判定依据: 低于门槛者既不返回也不计入，恰好等于门槛保留",
                threshold, filtered.getResponse().getContentAsString(),
                sc.getResponse().getContentAsString());
    }

    @Test
    void scopeTighteningDuringScrollExcludesOutOfScope() throws Exception {
        upsert("s-a1", "范围 收紧 语义 检索 甲", "team-a");
        upsert("s-a2", "范围 收紧 语义 检索 乙", "team-a");
        upsert("s-b1", "范围 收紧 语义 检索 丙", "team-b");

        // pageSize=1：先 a+b 开启，随后每页收紧为 team-a。
        MvcResult firstResult = mvc.perform(post("/api/scroll").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"范围 收紧 语义 检索\",\"visibleScopes\":"
                                + "[\"team-a\",\"team-b\"],\"pageSize\":1}"))
                .andExpect(status().isOk()).andReturn();
        JsonNode firstPageNode = MAPPER.readTree(firstResult.getResponse().getContentAsString());
        // 第一页按初始范围(a+b)给出，先把其中的 team-a 文档计入整轮覆盖统计。
        List<String> aAcrossPages = new ArrayList<>();
        firstPageNode.get("hits").forEach(h -> {
            if ("team-a".equals(h.get("scope").asText())) {
                aAcrossPages.add(h.get("id").asText());
            }
        });
        Set<String> afterTightening = new HashSet<>();
        String scrollId = firstPageNode.get("nextScrollId").asText();

        while (scrollId != null) {
            MvcResult r = mvc.perform(post("/api/scroll/" + scrollId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"visibleScopes\":[\"team-a\"]}"))
                    .andExpect(status().isOk()).andReturn();
            JsonNode nextNode = MAPPER.readTree(r.getResponse().getContentAsString());
            nextNode.get("hits").forEach(h -> {
                assertEquals("team-a", h.get("scope").asText(), "收紧后不得再出现 team-b");
                afterTightening.add(h.get("id").asText());
                aAcrossPages.add(h.get("id").asText());
            });
            scrollId = nextNode.has("nextScrollId") && !nextNode.get("nextScrollId").isNull()
                    ? nextNode.get("nextScrollId").asText() : null;
        }
        assertFalse(afterTightening.contains("s-b1"), "收紧后 team-b 文档不得继续被带出");
        assertEquals(2, aAcrossPages.size(), "team-a 恰好两篇且不重复（不重不漏）");
        assertEquals(Set.of("s-a1", "s-a2"), new HashSet<>(aAcrossPages),
                "team-a 文档不重不漏（收紧前首页带出也算覆盖）");
        log.info("输入: 遍历中把范围从 a+b 收紧为 a；收紧后翻到 id={}，整轮 team-a={}；"
                + "判定依据: 收紧后越界文档不再出现，仍可见文档不重不漏（首页按初始范围给出）",
                afterTightening, aAcrossPages);
    }

    @Test
    void invalidScrollIdAndWideningScopeAreRejected() throws Exception {
        upsert("err-1", "错误 处理 语义 检索", "team-a");
        // 未知凭据 → 404。
        mvc.perform(post("/api/scroll/does-not-exist")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("SCROLL_NOT_FOUND"));

        MvcResult firstResult = mvc.perform(post("/api/scroll").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"错误 处理 语义 检索\",\"visibleScopes\":[\"team-a\"],"
                                + "\"pageSize\":1}"))
                .andExpect(status().isOk()).andReturn();
        JsonNode page = MAPPER.readTree(firstResult.getResponse().getContentAsString());
        String scrollId = page.get("nextScrollId").asText();

        // 试图扩大范围 → 400。
        mvc.perform(post("/api/scroll/" + scrollId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"visibleScopes\":[\"team-a\",\"team-b\"]}"))
                .andExpect(status().isBadRequest());

        // 显式关闭 → 200；之后再翻 → 404。
        mvc.perform(delete("/api/scroll/" + scrollId)).andExpect(status().isOk());
        mvc.perform(post("/api/scroll/" + scrollId).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
        log.info("输入: 未知凭据、扩大范围、关闭后再翻；判定依据: 404/400/404 语义正确");
    }
}
