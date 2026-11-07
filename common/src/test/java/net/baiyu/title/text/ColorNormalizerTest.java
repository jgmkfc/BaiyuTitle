package net.baiyu.title.text;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link ColorNormalizer} 三格式归一化单测。
 */
class ColorNormalizerTest {

    /** &#RRGGBB 十六进制码转 MiniMessage 标签。 */
    @Test
    void ampersandHex_convertedToMiniMessage() {
        assertEquals("<#ff0000>红", ColorNormalizer.normalize("&#ff0000红"));
        assertEquals("<#AABB99>x<#00ff00>y", ColorNormalizer.normalize("&#AABB99x&#00ff00y"));
    }

    /** 裸 #RRGGBB 十六进制码转 MiniMessage 标签。 */
    @Test
    void bareHex_convertedToMiniMessage() {
        assertEquals("<#00ff00>绿", ColorNormalizer.normalize("#00ff00绿"));
        // 不足 6 位十六进制不是颜色码，保持原样
        assertEquals("#12345 abc", ColorNormalizer.normalize("#12345 abc"));
    }

    /** 传统 & 与 § 颜色/格式码转对应 MiniMessage 标签。 */
    @Test
    void legacyCodes_convertedToMiniMessage() {
        assertEquals("<red>红<bold>粗", ColorNormalizer.normalize("&c红&l粗"));
        assertEquals("<yellow>黄<italic>斜<reset>回", ColorNormalizer.normalize("§e黄§o斜§r回"));
        assertEquals("<white>白<obfuscated>乱", ColorNormalizer.normalize("&f白&k乱"));
    }

    /** MiniMessage 文本透传不破坏（含渐变参数与十六进制标签）。 */
    @Test
    void miniMessage_passthroughUntouched() {
        String gradient = "<gradient:#ff0000:#00ff00>渐变文字</gradient>";
        assertEquals(gradient, ColorNormalizer.normalize(gradient));
        String hexTag = "<#aabbcc>文字</#aabbcc>";
        assertEquals(hexTag, ColorNormalizer.normalize(hexTag));
        String named = "<red><bold>加粗红</bold></red>";
        assertEquals(named, ColorNormalizer.normalize(named));
    }

    /** normalize 幂等：对输出再次归一化结果不变。 */
    @Test
    void normalize_isIdempotent() {
        String[] inputs = {"&c红&l粗", "&#d9afd9稗玉", "#00ff00绿", "<gradient:#ff0000:#00ff00>渐变</gradient>"};
        for (String input : inputs) {
            String once = ColorNormalizer.normalize(input);
            assertEquals(once, ColorNormalizer.normalize(once), "normalize 应幂等: " + input);
        }
    }

    /** normalizeName：首 token 非颜色标签时前置 <white>（§1.5.2.2 &f 规则）。 */
    @Test
    void normalizeName_prependsWhiteWhenNoLeadingColor() {
        assertEquals("<white>无色称号", ColorNormalizer.normalizeName("无色称号"));
        // 格式类标签（bold）不算颜色，仍需前置 <white>
        assertEquals("<white><bold>粗体开头", ColorNormalizer.normalizeName("&l粗体开头"));
        // 已带颜色开头不前置
        assertEquals("<red>红头", ColorNormalizer.normalizeName("&c红头"));
        assertEquals("<#aabbcc>hex 头", ColorNormalizer.normalizeName("&#aabbcchex 头"));
    }

    /** normalizeName 幂等：已前置过的结果不重复前置。 */
    @Test
    void normalizeName_isIdempotent() {
        String[] inputs = {"无色称号", "&l粗体开头", "&c红头", "&#d9afd9稗&#d2b4da玉"};
        for (String input : inputs) {
            String once = ColorNormalizer.normalizeName(input);
            assertEquals(once, ColorNormalizer.normalizeName(once), "normalizeName 应幂等: " + input);
        }
    }

    /** 设计文档 §1.2 示例一（原版 1.16+ 渐变码）金样断言。 */
    @Test
    void goldenSample_legacyHexGradient() {
        String raw = "&#d9afd9稗&#d2b4da玉&#cab8db之&#c3bddc家&#bcc2dd，"
                + "&#b4c6dd我&#adcbde们&#a6d0df喜&#9ed4e0欢&#97d9e1你";
        String expected = "<#d9afd9>稗<#d2b4da>玉<#cab8db>之<#c3bddc>家<#bcc2dd>，"
                + "<#b4c6dd>我<#adcbde>们<#a6d0df>喜<#9ed4e0>欢<#97d9e1>你";
        assertEquals(expected, ColorNormalizer.normalizeName(raw));
        // 首 token 已是颜色标签，不前置 <white>；再归一化不变
        assertEquals(expected, ColorNormalizer.normalizeName(expected));
    }

    /** 设计文档 §1.2 示例二（MiniMessage 格式）金样断言：透传不破坏。 */
    @Test
    void goldenSample_miniMessagePassthrough() {
        String raw = "<#ea8fea>v<#de99ea>e<#d2a3eb>r<#c6aeeb>y<#bab8eb>_"
                + "<#adc2eb>t<#a1cdec>e<#95d7ec>s<#89e1ec>t";
        assertEquals(raw, ColorNormalizer.normalizeName(raw));
    }

    /** 空输入安全：null 与空串均返回空串。 */
    @Test
    void nullAndEmpty_returnEmpty() {
        assertEquals("", ColorNormalizer.normalize(null));
        assertEquals("", ColorNormalizer.normalize(""));
        assertEquals("", ColorNormalizer.normalizeName(null));
        assertEquals("", ColorNormalizer.normalizeName(""));
    }
}
