package com.github.xingjianluo.localsemanticsearchjava.semantic.core;

import com.github.xingjianluo.localsemanticsearchjava.semantic.model.Document;

/**
 * 重建过程钩子，用于在重建中途注入检查点（测试失败注入）。
 */
@FunctionalInterface
public interface RebuildHook {

    /**
     * 每处理一个文档后调用；抛出异常即中止本次重建。
     */
    void onDocumentIndexed(Document document, int processed, int total) throws Exception;

    RebuildHook NOOP = (doc, processed, total) -> {
    };
}
