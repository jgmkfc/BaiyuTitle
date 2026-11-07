package net.baiyu.title.text;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 颜色格式归一化工具。
 * <p>支持三种输入格式统一转为 MiniMessage：
 * <ul>
 *   <li>{@code &#RRGGBB} 与裸 {@code #RRGGBB} 十六进制颜色码 → {@code <#RRGGBB>}；</li>
 *   <li>传统 {@code &} 与 {@code §} 十六色/格式码 → 对应 MiniMessage 标签；</li>
 *   <li>已是 MiniMessage 的文本透传不破坏（{@code <#hex>} 与渐变参数 {@code :#hex}
 *       中的十六进制不会被二次处理）。</li>
 * </ul>
 * <p>全部方法幂等：对已归一化的输出再次调用结果不变。
 */
public final class ColorNormalizer {

    /**
     * 匹配 {@code &#RRGGBB} 与裸 {@code #RRGGBB}。
     * <p>裸形式用否定后顾排除四种前导字符：{@code <}（MiniMessage 十六进制标签本体）、
     * {@code /}（闭合标签 {@code </#hex>}）、{@code :}（gradient 等标签的参数）、
     * {@code &}（已由第一分支消费）。
     */
    private static final Pattern HEX_CODE =
            Pattern.compile("&#([0-9a-fA-F]{6})|(?<![</:&])#([0-9a-fA-F]{6})");

    /** 匹配传统 {@code &} 与 {@code §} 颜色/格式码。 */
    private static final Pattern LEGACY_CODE = Pattern.compile("[&§]([0-9a-fk-orA-FK-OR])");

    /**
     * 判断文本是否以"颜色类"标签开头：十六进制颜色、color/colour/c 标签、
     * gradient/rainbow、reset 以及 16 个具名颜色（含 grey 系拼写别名）。
     * <p>{@code <reset>} 计入颜色类：若在其前再前置 {@code <white>} 会被 reset 抵消，无意义。
     */
    private static final Pattern COLOR_HEAD = Pattern.compile(
            "^<(#[0-9a-fA-F]{6}"
                    + "|(?:color|colour|c):[^>]+"
                    + "|gradient(?::[^>]+)?"
                    + "|rainbow(?::[^>]+)?"
                    + "|reset"
                    + "|black|dark_blue|dark_green|dark_aqua|dark_red|dark_purple"
                    + "|gold|gray|grey|dark_gray|dark_grey|blue|green|aqua|red"
                    + "|light_purple|yellow|white)>",
            Pattern.CASE_INSENSITIVE);

    private ColorNormalizer() {
    }

    /**
     * 将混合颜色码文本归一化为 MiniMessage（不做 {@code <white>} 前置）。
     *
     * @param raw 原始文本（可空；空输入返回空串）
     * @return 归一化后的 MiniMessage 文本
     */
    public static String normalize(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        return convertLegacy(convertHex(raw));
    }

    /**
     * 称号名归一化：先 {@link #normalize(String)}，若结果不以颜色类标签开头则前置
     * {@code <white>}。幂等：已前置过的
     * 结果以 {@code <white>} 开头，不会重复前置。
     *
     * @param raw 原始名称文本（可空；空输入返回空串）
     * @return 归一化并保证以颜色类标签开头的 MiniMessage 名称
     */
    public static String normalizeName(String raw) {
        String normalized = normalize(raw);
        if (normalized.isEmpty()) {
            return normalized;
        }
        return startsWithColorTag(normalized) ? normalized : "<white>" + normalized;
    }

    /**
     * 判断文本是否以颜色类标签开头。
     */
    private static boolean startsWithColorTag(String text) {
        return COLOR_HEAD.matcher(text).find();
    }

    /**
     * 将 {@code &#RRGGBB} 与裸 {@code #RRGGBB} 逐个替换为 {@code <#RRGGBB>}。
     * <p>已是 MiniMessage 的 {@code <#hex>} 与 {@code :#hex}（渐变参数）被否定后顾
     * 排除，保证透传与幂等。
     */
    private static String convertHex(String input) {
        Matcher matcher = HEX_CODE.matcher(input);
        StringBuilder result = new StringBuilder(input.length());
        int last = 0;
        while (matcher.find()) {
            result.append(input, last, matcher.start());
            String hex = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            result.append("<#").append(hex).append('>');
            last = matcher.end();
        }
        result.append(input, last, input.length());
        return result.toString();
    }

    /**
     * 将传统 {@code &} 与 {@code §} 码替换为对应 MiniMessage 标签。
     */
    private static String convertLegacy(String input) {
        Matcher matcher = LEGACY_CODE.matcher(input);
        StringBuilder result = new StringBuilder(input.length());
        int last = 0;
        while (matcher.find()) {
            result.append(input, last, matcher.start());
            result.append(legacyToMini(matcher.group(1).charAt(0)));
            last = matcher.end();
        }
        result.append(input, last, input.length());
        return result.toString();
    }

    /**
     * 传统单字符码到 MiniMessage 标签的映射。
     */
    private static String legacyToMini(char code) {
        return switch (Character.toLowerCase(code)) {
            case '0' -> "<black>";
            case '1' -> "<dark_blue>";
            case '2' -> "<dark_green>";
            case '3' -> "<dark_aqua>";
            case '4' -> "<dark_red>";
            case '5' -> "<dark_purple>";
            case '6' -> "<gold>";
            case '7' -> "<gray>";
            case '8' -> "<dark_gray>";
            case '9' -> "<blue>";
            case 'a' -> "<green>";
            case 'b' -> "<aqua>";
            case 'c' -> "<red>";
            case 'd' -> "<light_purple>";
            case 'e' -> "<yellow>";
            case 'f' -> "<white>";
            case 'k' -> "<obfuscated>";
            case 'l' -> "<bold>";
            case 'm' -> "<strikethrough>";
            case 'n' -> "<underlined>";
            case 'o' -> "<italic>";
            case 'r' -> "<reset>";
            default -> String.valueOf(code);
        };
    }
}
