package net.baiyu.title.gui.render;

import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 测试夹具：把随包发布的真实 {@code lang/zh_CN.yml} 扁平化为
 * {@link LoreTemplateSource}——快照测试因此直接验证<b>实际交付的模板文案</b>
 * 与设计文档 §1.3.2.4 逐字对照，而非另写一份测试专用模板。
 * <p>缺键直接抛 {@link AssertionError}，抓模板键拼写错误。</p>
 */
final class ZhLoreTemplateFixture {

    private ZhLoreTemplateFixture() {
    }

    /** 从 classpath 加载 zh_CN.yml 并构建模板源。 */
    static LoreTemplateSource zhTemplates() {
        Map<String, String> flat = loadFlatStrings();
        return key -> {
            String value = flat.get(key);
            if (value == null) {
                throw new AssertionError("zh_CN.yml 缺少 lore 模板键: " + key);
            }
            return value;
        };
    }

    /** 递归展平 YAML 为点号键 → 字符串值映射（列表等非字符串叶子跳过）。 */
    private static Map<String, String> loadFlatStrings() {
        InputStream in = ZhLoreTemplateFixture.class.getResourceAsStream("/lang/zh_CN.yml");
        if (in == null) {
            throw new AssertionError("测试类路径上找不到 /lang/zh_CN.yml");
        }
        try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            Object root = new Yaml().load(reader);
            Map<String, String> flat = new HashMap<>();
            collect(asMap(root), "", flat);
            return flat;
        } catch (Exception e) {
            throw new AssertionError("解析 zh_CN.yml 失败", e);
        }
    }

    /** 递归收集字符串叶子。 */
    private static void collect(Map<?, ?> node, String prefix, Map<String, String> flat) {
        for (Map.Entry<?, ?> entry : node.entrySet()) {
            String path = prefix.isEmpty() ? String.valueOf(entry.getKey())
                    : prefix + "." + entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> nested) {
                collect(nested, path, flat);
            } else if (value instanceof String string) {
                flat.put(path, string);
            }
        }
    }

    private static Map<?, ?> asMap(Object root) {
        if (root instanceof Map<?, ?> map) {
            return map;
        }
        throw new AssertionError("zh_CN.yml 根节点不是对象");
    }
}
