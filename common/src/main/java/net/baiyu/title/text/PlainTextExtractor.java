package net.baiyu.title.text;

/**
 * MiniMessage 纯文本提取工具（长度校验/搜索/排序用，对应 name_plain 列）。
 * <p>手写标签剥离，不依赖 adventure 运行时解析：
 * <ul>
 *   <li>剥离形如 {@code <name>}、{@code </name>}、{@code <name:arg>}、
 *       {@code <#RRGGBB>}、{@code <br/>} 的成对或自闭合标签；</li>
 *   <li>标签参数的单/双引号内允许出现 {@code >}（如 hover 文本），按引号配对跳过；</li>
 *   <li>反斜杠转义（{@code \<} 与 {@code \\}）还原为字面字符；</li>
 *   <li>无法构成合法标签的 {@code <} 按字面字符保留。</li>
 * </ul>
 */
public final class PlainTextExtractor {

    private PlainTextExtractor() {
    }

    /**
     * 剥离全部 MiniMessage 标签，返回纯文本。
     *
     * @param miniMessage 归一化 MiniMessage 文本（可空；空输入返回空串）
     * @return 纯文本
     */
    public static String extract(String miniMessage) {
        if (miniMessage == null || miniMessage.isEmpty()) {
            return "";
        }
        StringBuilder plain = new StringBuilder(miniMessage.length());
        int i = 0;
        int length = miniMessage.length();
        while (i < length) {
            char ch = miniMessage.charAt(i);
            // 反斜杠转义：\< 与 \\ 还原为字面字符
            if (ch == '\\' && i + 1 < length) {
                char next = miniMessage.charAt(i + 1);
                if (next == '<' || next == '\\') {
                    plain.append(next);
                    i += 2;
                    continue;
                }
            }
            if (ch == '<') {
                int tagEnd = findTagEnd(miniMessage, i);
                if (tagEnd >= 0) {
                    // 整个标签跳过不输出
                    i = tagEnd + 1;
                    continue;
                }
            }
            plain.append(ch);
            i++;
        }
        return plain.toString();
    }

    /**
     * 从 {@code open} 处的 {@code <} 开始探测合法标签，返回配对 {@code >} 的下标。
     * <p>合法标签：可选前导 {@code /}；名称段限 MiniMessage 标签名字符集
     * （ASCII 字母/数字/{@code _ - # ! ? /}，中文、空格等一律判非标签——
     * {@code Character.isLetterOrDigit} 会放行 CJK，不可用）；遇 {@code :} 进入
     * 参数段，参数段跟踪单/双引号配对，引号内的 {@code >} 不作为结束符；
     * 标签内再次出现裸 {@code <} 视为非法。
     *
     * @return 配对 {@code >} 的下标；不是合法标签返回 -1
     */
    private static int findTagEnd(String text, int open) {
        int i = open + 1;
        int length = text.length();
        // 可选的闭合标签斜杠 </...>
        if (i < length && text.charAt(i) == '/') {
            i++;
        }
        if (i >= length || !isTagNameChar(text.charAt(i))) {
            return -1;
        }
        // 名称段：严格字符集，直到 > 结束、: 进入参数段，或遇非法字符判非标签
        while (i < length) {
            char ch = text.charAt(i);
            if (ch == '>') {
                return i;
            }
            if (ch == ':') {
                break;
            }
            if (!isTagNameChar(ch)) {
                return -1;
            }
            i++;
        }
        // 参数段：宽松扫描，仅跟踪引号配对
        char quote = 0;
        for (; i < length; i++) {
            char ch = text.charAt(i);
            if (quote != 0) {
                // 引号内：只寻找配对引号
                if (ch == quote) {
                    quote = 0;
                }
                continue;
            }
            if (ch == '\'' || ch == '"') {
                quote = ch;
                continue;
            }
            if (ch == '<') {
                return -1;
            }
            if (ch == '>') {
                return i;
            }
        }
        return -1;
    }

    /**
     * 判断字符是否属于 MiniMessage 标签名字符集：ASCII 字母/数字与
     * {@code _ - # ! ? /}（{@code #} 十六进制颜色、{@code !} 取反装饰方言、
     * {@code /} 自闭合 {@code <br/>}）。
     */
    private static boolean isTagNameChar(char ch) {
        return (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9')
                || ch == '_' || ch == '-' || ch == '#' || ch == '!' || ch == '?' || ch == '/';
    }
}
