package com.github.xingjianluo.localsemanticsearchjava.tests;

import com.github.xingjianluo.localsemanticsearchjava.paging.PageToken;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 翻页令牌：往返一致、推进不变锚点、损坏/截断可识别、查询指纹确定。
 */
class PageTokenTest {

    private static final Logger log = LoggerFactory.getLogger(PageTokenTest.class);

    @Test
    void encodeDecodeRoundTripsAndAdvanceKeepsAnchor() {
        String hash = PageToken.hashQuery("某查询 query");
        PageToken start = PageToken.start(42L, hash);
        assertTrue(start.isAtStart());
        String encoded = start.encode();
        PageToken decoded = PageToken.decode(encoded);
        assertEquals(start, decoded);
        assertTrue(decoded.isAtStart());

        PageToken advanced = decoded.advance(java.util.List.of("doc-7"), 0.375, "doc-7");
        assertEquals(42L, advanced.indexVersion());
        assertEquals(hash, advanced.queryHash());
        assertEquals(0.375, advanced.afterScore());
        assertEquals("doc-7", advanced.afterId());
        assertEquals(java.util.List.of("doc-7"), advanced.returnedIds());
        assertEquals(advanced, PageToken.decode(advanced.encode()));
        log.info("输入: 首页令牌 v=42 编码后推进到 (0.375, doc-7)；编码={}；判定依据: 往返逐字段一致，"
                + "推进游标不改变锚定版本与查询指纹", encoded);
    }

    @Test
    void malformedOrTamperedTokensAreRejected() {
        PageToken token = PageToken.start(7, PageToken.hashQuery("q"))
                .advance(java.util.List.of("id-1"), 0.5, "id-1");
        String encoded = token.encode();
        assertThrows(IllegalArgumentException.class, () -> PageToken.decode(null));
        assertThrows(IllegalArgumentException.class, () -> PageToken.decode("  "));
        assertThrows(IllegalArgumentException.class, () -> PageToken.decode("!!!not-base64!!!"));
        // 截断
        assertThrows(IllegalArgumentException.class,
                () -> PageToken.decode(encoded.substring(0, encoded.length() - 4)));
        // 翻转首部（魔数区）字符：破坏魔数/格式，必然被识别
        char[] chars = encoded.toCharArray();
        chars[1] = chars[1] == 'A' ? 'B' : 'A';
        String tampered = new String(chars);
        assertThrows(IllegalArgumentException.class, () -> PageToken.decode(tampered));
        // 仅改载荷区而语法仍合法时，令牌会解出不同内容——它不是安全边界，
        // 服务端会以版本/查询指纹不匹配返回 STALE（由 PagingConcurrencyTest 覆盖）。
        log.info("输入: 空白/非 Base64/截断/魔数区篡改四类坏令牌；判定依据: 一律抛 "
                + "IllegalArgumentException，控制器据此返回 400 INVALID_TOKEN");
    }

    @Test
    void queryHashIsDeterministic() {
        assertEquals(PageToken.hashQuery("同样的查询"), PageToken.hashQuery("同样的查询"));
        assertNotEquals(PageToken.hashQuery("查询甲"), PageToken.hashQuery("查询乙"));
        assertEquals(64, PageToken.hashQuery("x").length(), "SHA-256 hex 长度应为 64");
    }
}
