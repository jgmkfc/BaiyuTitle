package net.baiyu.title.text;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link PlainTextExtractor} 剥标签单测（name_plain 列 / 长度校验用）。
 */
class PlainTextExtractorTest {

    /** 剥离成对与自闭合标签。 */
    @Test
    void stripsTags() {
        assertEquals("你好", PlainTextExtractor.extract("<white>你好</white>"));
        assertEquals("渐变文字", PlainTextExtractor.extract("<gradient:#ff0000:#00ff00>渐变文字</gradient>"));
        assertEquals("换行", PlainTextExtractor.extract("换<br/>行"));
        assertEquals("稗玉", PlainTextExtractor.extract("<#d9afd9>稗<#d2b4da>玉"));
    }

    /** 码点计数：中文剥色后 codePointCount 与可见字符数一致（长度校验口径）。 */
    @Test
    void codePointCount_chineseAndSupplementary() {
        String plain = PlainTextExtractor.extract("<red>称号名字</red>");
        assertEquals("称号名字", plain);
        assertEquals(4, plain.codePointCount(0, plain.length()));

        // 增补平面字符（emoji）按 1 码点计
        String withEmoji = PlainTextExtractor.extract("<gold>称号\uD83D\uDE00</gold>");
        assertEquals("称号\uD83D\uDE00", withEmoji);
        assertEquals(3, withEmoji.codePointCount(0, withEmoji.length()));
        assertEquals(4, withEmoji.length());
    }

    /** 反斜杠转义：\< 与 \\ 还原为字面字符，被转义的标签不剥离。 */
    @Test
    void escapes_restoredAsLiterals() {
        assertEquals("<red>不是标签", PlainTextExtractor.extract("\\<red>不是标签"));
        assertEquals("\\反斜杠", PlainTextExtractor.extract("\\\\反斜杠"));
        // 转义标签 + 真标签混排：只剥真标签
        assertEquals("<red>红", PlainTextExtractor.extract("\\<red><red>红</red>"));
    }

    /** 无法构成合法标签的 < 按字面保留。 */
    @Test
    void invalidTags_keptVerbatim() {
        assertEquals("a < b", PlainTextExtractor.extract("a < b"));
        assertEquals("1<2 且 3>2", PlainTextExtractor.extract("1<2 且 3>2"));
        assertEquals("尾部<", PlainTextExtractor.extract("尾部<"));
    }

    /** 标签参数引号内允许出现 >（按引号配对跳过）。 */
    @Test
    void quotedTagArguments_skippedWholly() {
        assertEquals("文字", PlainTextExtractor.extract("<hover:show_text:'悬浮<提示>'>文字</hover>"));
        assertEquals("文字", PlainTextExtractor.extract("<hover:show_text:\"悬浮>提示\">文字</hover>"));
    }

    /** 空输入安全。 */
    @Test
    void nullAndEmpty_returnEmpty() {
        assertEquals("", PlainTextExtractor.extract(null));
        assertEquals("", PlainTextExtractor.extract(""));
    }
}
