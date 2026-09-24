package com.github.xingjianluo.localsemanticsearchjava.rebuild;

import com.github.xingjianluo.localsemanticsearchjava.index.IndexSnapshot;
import com.github.xingjianluo.localsemanticsearchjava.index.IndexedDocument;

import java.util.List;

/**
 * 新索引提交前的校验器。校验不通过时抛出
 * {@link RebuildValidationException}，重建失败并回退。
 */
public interface IndexValidator {

    void validate(List<IndexedDocument> source, IndexSnapshot newIndex);
}
