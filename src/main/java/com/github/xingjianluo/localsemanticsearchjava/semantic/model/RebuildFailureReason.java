package com.github.xingjianluo.localsemanticsearchjava.semantic.model;

/**
 * 可区分的重建失败原因。
 */
public enum RebuildFailureReason {
    /** 向量化失败（内容无法向量化） */
    VECTORIZATION_FAILED,
    /** 重建钩子注入的失败（模拟中途失败，如磁盘/外部资源故障） */
    HOOK_ABORTED,
    /** 重建期间被中断 */
    INTERRUPTED,
    /** 已有重建进行中 */
    ALREADY_RUNNING,
    /** 其他未分类错误 */
    INTERNAL_ERROR
}
