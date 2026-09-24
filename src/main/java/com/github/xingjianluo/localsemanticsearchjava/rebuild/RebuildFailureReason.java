package com.github.xingjianluo.localsemanticsearchjava.rebuild;

/**
 * 可区分的重建失败原因。
 */
public enum RebuildFailureReason {
    /** 文档源读取失败 */
    SOURCE_UNREADABLE,
    /** 向量化过程失败 */
    EMBEDDING_FAILED,
    /** 新索引校验失败（如结果为空但源非空） */
    VALIDATION_FAILED,
    /** 其他未知原因 */
    UNKNOWN
}
