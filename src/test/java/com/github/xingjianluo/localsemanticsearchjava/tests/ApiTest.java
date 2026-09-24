package com.github.xingjianluo.localsemanticsearchjava.tests;

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

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * REST API 端到端：写入 -> 检索（含可见性）-> 重建 -> 删除。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiTest {

    private static final Logger log = LoggerFactory.getLogger(ApiTest.class);

    @Autowired
    private MockMvc mvc;

    @Test
    @Order(1)
    void upsertThenSearchRespectsVisibility() throws Exception {
        mvc.perform(post("/api/documents").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"api-1\",\"content\":\"本地 语义 检索 服务\",\"scope\":\"team-a\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(true));
        mvc.perform(post("/api/documents").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"api-2\",\"content\":\"本地 语义 检索 引擎\",\"scope\":\"team-b\"}"))
                .andExpect(status().isOk());

        MvcResult visible = mvc.perform(post("/api/search").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"语义 检索\",\"visibleScopes\":[\"team-a\"],\"topK\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.hits[0].id").value("api-1"))
                .andReturn();
        MvcResult emptyScope = mvc.perform(post("/api/search").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"语义 检索\",\"visibleScopes\":[],\"topK\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0))
                .andReturn();
        log.info("输入: 写入 team-a/team-b 各一篇后以 visibleScopes=[team-a] 与 [] 检索；"
                + "响应1={}；响应2={}；判定依据: 越界文档不得出现在结果与计数中，空范围返回空",
                visible.getResponse().getContentAsString(),
                emptyScope.getResponse().getContentAsString());
    }

    @Test
    @Order(2)
    void duplicateUpsertIsIdempotent() throws Exception {
        MvcResult second = mvc.perform(post("/api/documents").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"api-1\",\"content\":\"本地 语义 检索 服务\",\"scope\":\"team-a\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(false))
                .andReturn();
        log.info("输入: 重复写入与 api-1 完全相同的内容；响应={}；判定依据: applied 必须为 false",
                second.getResponse().getContentAsString());
    }

    @Test
    @Order(3)
    void rebuildSucceeds() throws Exception {
        MvcResult result = mvc.perform(post("/api/rebuild"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andReturn();
        log.info("输入: 触发索引重建；响应={}；判定依据: 状态 SUCCESS 且文档数不小于已写入的 2 篇",
                result.getResponse().getContentAsString());
    }

    @Test
    @Order(4)
    void deleteStopsRecall() throws Exception {
        mvc.perform(delete("/api/documents/api-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(true));
        MvcResult after = mvc.perform(post("/api/search").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"语义 检索\",\"visibleScopes\":[\"team-a\"],\"topK\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0))
                .andReturn();
        log.info("输入: 删除 api-1 后以原查询词在 team-a 范围检索；响应={}；判定依据: 已删除文档不得再被召回",
                after.getResponse().getContentAsString());
    }
}
