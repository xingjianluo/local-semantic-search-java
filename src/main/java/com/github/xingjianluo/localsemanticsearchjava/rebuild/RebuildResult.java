package com.github.xingjianluo.localsemanticsearchjava.rebuild;

/**
 * 重建结果。
 *
 * @param status        成功 / 失败已回退
 * @param failureReason 失败原因（成功时为 null）
 * @param message       人类可读的说明
 * @param documentCount 生效索引中的文档数
 * @param indexVersion  生效索引版本
 */
public record RebuildResult(RebuildStatus status,
                            RebuildFailureReason failureReason,
                            String message,
                            int documentCount,
                            long indexVersion) {

    public static RebuildResult success(int documentCount, long indexVersion) {
        return new RebuildResult(RebuildStatus.SUCCESS, null, "rebuild succeeded",
                documentCount, indexVersion);
    }

    public static RebuildResult failed(RebuildFailureReason reason, String message,
                                       int documentCount, long indexVersion) {
        return new RebuildResult(RebuildStatus.FAILED_ROLLED_BACK, reason, message,
                documentCount, indexVersion);
    }
}
