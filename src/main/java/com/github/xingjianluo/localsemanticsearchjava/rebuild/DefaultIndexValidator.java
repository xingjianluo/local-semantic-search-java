package com.github.xingjianluo.localsemanticsearchjava.rebuild;

import com.github.xingjianluo.localsemanticsearchjava.index.IndexSnapshot;
import com.github.xingjianluo.localsemanticsearchjava.index.IndexedDocument;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 默认校验：新索引文档数必须与源一致（源非空时新索引不得为空）。
 */
@Component
public class DefaultIndexValidator implements IndexValidator {

    @Override
    public void validate(List<IndexedDocument> source, IndexSnapshot newIndex) {
        if (newIndex.size() != source.size()) {
            throw new RebuildValidationException(
                    "index size " + newIndex.size() + " != source size " + source.size());
        }
        if (!source.isEmpty() && newIndex.size() == 0) {
            throw new RebuildValidationException("source is non-empty but new index is empty");
        }
    }
}
