package com.platform.search.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MarkdownTextExtractorTest {

    @Test
    void stripsMarkdownNoiseAndTruncates() {
        MarkdownTextExtractor extractor = new MarkdownTextExtractor(20);

        String text = extractor.extract("# 标题\n\n![图](oss://x)\n[链接](https://example.com)\n`code`\n正文内容很多很多");

        assertThat(text).doesNotContain("![");
        assertThat(text).doesNotContain("](");
        assertThat(text.length()).isLessThanOrEqualTo(20);
    }

    @Test
    void preservesLinkAnchorText() {
        MarkdownTextExtractor extractor = new MarkdownTextExtractor(1000);

        String text = extractor.extract("see [Java 并发实战](https://example.com/juc) for details");

        assertThat(text).contains("Java 并发实战");
        assertThat(text).doesNotContain("example.com");
    }

    @Test
    void returnsEmptyForBlankInput() {
        MarkdownTextExtractor extractor = new MarkdownTextExtractor(100);

        assertThat(extractor.extract(null)).isEmpty();
        assertThat(extractor.extract("   \n\t  ")).isEmpty();
    }
}
