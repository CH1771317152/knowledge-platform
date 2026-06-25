package com.platform.search.application;

/**
 * Strips markdown noise (image syntax, link targets, inline code, emphasis/list markers) from a body
 * and truncates the result to {@code maxChars} for the Elasticsearch {@code body_text} field.
 *
 * <p>The goal is a plain-text blob suitable for full-text indexing — not faithful rendering. Link
 * anchor text is preserved (it is high-signal), everything else (URLs, code, image alt) is dropped
 * as search noise. Whitespace is collapsed before truncation so the limit lands on rendered chars.
 */
public class MarkdownTextExtractor {

    private final int maxChars;

    public MarkdownTextExtractor(int maxChars) {
        this.maxChars = maxChars;
    }

    public String extract(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return "";
        }
        String text = markdown
                .replaceAll("!\\[[^]]*]\\([^)]*\\)", " ")
                .replaceAll("\\[([^]]+)]\\([^)]*\\)", "$1")
                .replaceAll("`{1,3}[^`]*`{1,3}", " ")
                .replaceAll("[>#*_\\-]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return text.length() <= maxChars ? text : text.substring(0, maxChars);
    }
}
