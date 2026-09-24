package com.github.xingjianluo.localsemanticsearchjava.rebuild;

/**
 * 新索引校验失败。
 */
public class RebuildValidationException extends RuntimeException {

    public RebuildValidationException(String message) {
        super(message);
    }
}
