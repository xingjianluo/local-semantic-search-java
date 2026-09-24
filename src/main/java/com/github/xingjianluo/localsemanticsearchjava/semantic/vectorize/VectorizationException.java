package com.github.xingjianluo.localsemanticsearchjava.semantic.vectorize;

/**
 * 向量化失败异常。
 */
public class VectorizationException extends RuntimeException {
    public VectorizationException(String message) {
        super(message);
    }

    public VectorizationException(String message, Throwable cause) {
        super(message, cause);
    }
}
