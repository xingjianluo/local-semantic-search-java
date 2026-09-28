package com.github.xingjianluo.localsemanticsearchjava.scroll;

/**
 * 分页会话异常：凭据未知、已关闭或已过期时抛出。
 */
public class ScrollSessionException extends RuntimeException {

    public ScrollSessionException(String message) {
        super(message);
    }
}
