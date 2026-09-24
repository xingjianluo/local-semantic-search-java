package com.github.xingjianluo.localsemanticsearchjava.rebuild;

/**
 * 重建结果状态。
 */
public enum RebuildStatus {
    /** 新索引已原子生效 */
    SUCCESS,
    /** 重建失败，已回退到原有可用索引 */
    FAILED_ROLLED_BACK
}
