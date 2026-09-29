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
        // 自包含数据：独特 query 与 id，避免共享上下文中其它用例的文档被召回。
        String q = "scopetightlegacyx05";
        upsert("stg-a1", q + " item alpha", "sta");
        upsert("stg-a2", q + " item beta", "sta");
        upsert("stg-b1", q + " item gamma", "stb");

        // pageSize=1：先 a+b 开启，随后每页收紧为 team-a。
        MvcResult firstResult = mvc.perform(post("/api/scroll").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"" + q + "\",\"visibleScopes\":"
                                + "[\"sta\",\"stb\"],\"pageSize\":1}"))
                .andExpect(status().isOk()).andReturn();
        JsonNode firstPageNode = MAPPER.readTree(firstResult.getResponse().getContentAsString());
        // 第一页按初始范围(a+b)给出，先把其中的 team-a 文档计入整轮覆盖统计。
        List<String> aAcrossPages = new ArrayList<>();
        firstPageNode.get("hits").forEach(h -> {
            if ("sta".equals(h.get("scope").asText())) {
                aAcrossPages.add(h.get("id").asText());
            }
        });
        Set<String> afterTightening = new HashSet<>();
        String scrollId = textOrNull(firstPageNode, "nextScrollId");

        while (scrollId != null) {
            // 每个页凭据都重发一次：验证收紧语义下幂等重放也不越界、不重复。
            MvcResult r = mvc.perform(post("/api/scroll/" + scrollId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"visibleScopes\":[\"sta\"]}"))
                    .andExpect(status().isOk()).andReturn();
            JsonNode nextNode = MAPPER.readTree(r.getResponse().getContentAsString());
            JsonNode replay = MAPPER.readTree(mvc.perform(post("/api/scroll/" + scrollId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"visibleScopes\":[\"sta\"]}"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            assertEquals(nextNode.toString(), replay.toString());
            assertEquals(2, nextNode.get("totalHits").asInt(),
                    "收紧后数量按 sta 计算");
            nextNode.get("hits").forEach(h -> {
                assertEquals("sta", h.get("scope").asText(), "收紧后不得再出现 stb");
                afterTightening.add(h.get("id").asText());
                aAcrossPages.add(h.get("id").asText());
            });
            scrollId = textOrNull(nextNode, "nextScrollId");
        }
        assertFalse(afterTightening.contains("stg-b1"), "收紧后 stb 文档不得继续被带出");
        assertEquals(2, aAcrossPages.size(), "sta 恰好两篇且不重复（不重不漏）");
        assertEquals(Set.of("stg-a1", "stg-a2"), new HashSet<>(aAcrossPages),
                "sta 文档不重不漏（收紧前首页带出也算覆盖）");
        log.info("输入: 遍历中把范围从 sta+stb 收紧为 sta；收紧后翻到 id={}，整轮 sta={}；"
                + "判定依据: 收紧后越界文档不再出现，仍可见文档不重不漏，数量随收紧更新",
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

    @Test
    void repeatedPageRequestOverHttpReturnsSamePageAndDoesNotSkip() throws Exception {
        // 独特 query，避免与共享 Spring 上下文中其它用例的数据碰撞。
        String q = "retryidemx01";
        for (int i = 0; i < 15; i++) {
            upsert("rep-" + i, q + " item " + i, i % 2 == 0 ? "rpa" : "rpb");
        }
        String scopes = "[\"rpa\",\"rpb\"]";

        JsonNode first = startScroll(q, scopes, 4, null);
        List<String> all = new ArrayList<>();
        first.get("hits").forEach(h -> all.add(h.get("id").asText()));
        String token = textOrNull(first, "nextScrollId");

        while (token != null) {
            JsonNode page1 = nextPage(token, "{}");
            JsonNode page2 = nextPage(token, "{}"); // 超时重发同一请求
            JsonNode page3 = nextPage(token, "{}"); // 再重发一次
            // 三次响应逐字段等价：命中、下一页凭据、数量、版本、累计返回全部一致。
            assertEquals(page1.toString(), page2.toString());
            assertEquals(page1.toString(), page3.toString());
            assertEquals(
                    page1.get("totalHits").asInt(), page2.get("totalHits").asInt());
            page1.get("hits").forEach(h -> all.add(h.get("id").asText()));
            token = textOrNull(page1, "nextScrollId");
        }

        // 与一次性整体检索对照。
        List<String> expected = searchAllIds(q, scopes);
        log.info("输入: 15 篇，HTTP 上每个翻页凭据都重发两次；整体 {} 条，翻页 {} 条；"
                + "判定依据: 重发同一请求永远返回同一页不跳页，整轮不重不漏且顺序一致",
                expected.size(), all.size());
        assertEquals(expected, all);
        assertEquals(expected.size(), new HashSet<>(all).size());
    }

    @Test
    void lastPageTokenReplayableOverHttpAndFinishedRoundRejectsFuturePages() throws Exception {
        String q = "lastreplayx02";
        for (int i = 0; i < 5; i++) {
            upsert("last-" + i, q + " item " + i, "lpa");
        }
        JsonNode first = startScroll(q, "[\"lpa\"]", 2, null);
        String token = textOrNull(first, "nextScrollId");
        String lastToken = null;
        JsonNode last = first;
        while (token != null) {
            last = nextPage(token, "{}");
            lastToken = token;
            token = textOrNull(last, "nextScrollId");
        }
        assertTrue(lastToken != null && last.get("hits").size() > 0);
        assertTrue(textOrNull(last, "nextScrollId") == null, "末页无下一页凭据");

        // 末页凭据可反复重放，响应一致。
        JsonNode replay1 = nextPage(lastToken, "{}");
        JsonNode replay2 = nextPage(lastToken, "{}");
        assertEquals(last.toString(), replay1.toString());
        assertEquals(last.toString(), replay2.toString());

        // 同一页重发时改变范围 → 400。
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/scroll/" + lastToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"visibleScopes\":[\"lpb\"]}"))
                .andExpect(status().isBadRequest());
        log.info("输入: HTTP 末页凭据重放 + 重放时改范围；判定依据: 末页稳定可重放，"
                + "重放改范围被拒绝");
    }

    @Test
    void tighteningOverHttpUpdatesCountAndStaysStickyOnOmittedScopes() throws Exception {
        String q = "stickytightenx03";
        for (int i = 0; i < 8; i++) {
            upsert("tk-" + i, q + " item " + i, "tsa");
        }
        for (int i = 0; i < 4; i++) {
            upsert("tk-b" + i, q + " item b" + i, "tsb");
        }
        String both = "[\"tsa\",\"tsb\"]";

        JsonNode first = startScroll(q, both, 3, null);
        assertEquals(12, first.get("totalHits").asInt());
        List<String> aIds = new ArrayList<>();
        first.get("hits").forEach(h -> {
            if ("tsa".equals(h.get("scope").asText())) {
                aIds.add(h.get("id").asText());
            }
        });

        String token = textOrNull(first, "nextScrollId");
        JsonNode tightened = nextPage(token, "{\"visibleScopes\":[\"tsa\"]}");
        assertEquals(8, tightened.get("totalHits").asInt(),
                "收紧后数量必须立即等于 tsa 的真实条数");
        boolean firstAfter = true;
        while (token != null) {
            JsonNode page = firstAfter
                    ? tightened
                    : nextPage(token, "{}"); // 后续不再声明范围：粘性，不得复活 tsb
            firstAfter = false;
            assertEquals(8, page.get("totalHits").asInt(), "数量始终跟当前生效范围一致");
            page.get("hits").forEach(h -> {
                assertEquals("tsa", h.get("scope").asText(), "越界内容不得复活");
                aIds.add(h.get("id").asText());
            });
            token = textOrNull(page, "nextScrollId");
        }
        Set<String> uniqueA = new HashSet<>(aIds);
        assertEquals(8, aIds.size(), "team-a 不重不漏");
        assertEquals(8, uniqueA.size());
        log.info("输入: 12 篇（tsa=8,tsb=4），第二页收紧为 tsa 后省略范围；totalHits 12->8 且保持；"
                + "收得 tsa {} 条；判定依据: 数量随收紧立即更新并保持粘性，越界永不复活",
                aIds.size());
    }

    @Test
    void emptyPageAfterTighteningToNoScopeIsConsistent() throws Exception {
        String q = "emptyscopex04";
        upsert("ep-1", q + " item alpha", "esa");
        upsert("ep-2", q + " item beta", "esb");

        JsonNode first = startScroll(q, "[\"esa\",\"esb\"]", 1, null);
        assertEquals(2, first.get("totalHits").asInt());
        String token = textOrNull(first, "nextScrollId");
        JsonNode page = nextPage(token, "{\"visibleScopes\":[]}");
        assertEquals(0, page.get("totalHits").asInt());
        while (textOrNull(page, "nextScrollId") != null) {
            page = nextPage(textOrNull(page, "nextScrollId"), "{}");
            assertEquals(0, page.get("totalHits").asInt());
            assertEquals(0, page.get("hits").size(), "空范围下不得再带出任何内容");
        }
        // 开启时范围即空 → 空页、无凭据。
        JsonNode emptyStart = startScroll(q, "[]", 10, null);
        assertEquals(0, emptyStart.get("hits").size());
        assertTrue(textOrNull(emptyStart, "nextScrollId") == null);
        log.info("输入: 翻页中收紧为空范围、开启即空范围；判定依据: 数量为 0、无内容、空页链路自洽");
    }

    private JsonNode startScroll(String query, String scopesJson, Integer pageSize,
                                 Double minScore) throws Exception {
        String body = "{\"query\":\"" + query + "\",\"visibleScopes\":" + scopesJson
                + ",\"pageSize\":" + pageSize
                + (minScore == null ? "" : ",\"minScore\":" + minScore) + "}";
        MvcResult r = mvc.perform(post("/api/scroll").contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk()).andReturn();
        return MAPPER.readTree(r.getResponse().getContentAsString());
    }

    private JsonNode nextPage(String token, String requestBody) throws Exception {
        MvcResult r = mvc.perform(post("/api/scroll/" + token)
                        .contentType(MediaType.APPLICATION_JSON).content(requestBody))
                .andExpect(status().isOk()).andReturn();
        return MAPPER.readTree(r.getResponse().getContentAsString());
    }

    private static String textOrNull(JsonNode node, String field) {
        return node.has(field) && !node.get(field).isNull() ? node.get(field).asText() : null;
    }

    private List<String> searchAllIds(String query, String scopesJson) throws Exception {
        MvcResult r = mvc.perform(post("/api/search").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"" + query + "\",\"visibleScopes\":" + scopesJson
                                + ",\"topK\":1000}"))
                .andExpect(status().isOk()).andReturn();
        JsonNode node = MAPPER.readTree(r.getResponse().getContentAsString());
        List<String> ids = new ArrayList<>();
        node.get("hits").forEach(h -> ids.add(h.get("id").asText()));
        return ids;
    }
}
