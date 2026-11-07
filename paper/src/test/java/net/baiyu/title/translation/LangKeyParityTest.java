package net.baiyu.title.translation;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * lang 双语键对齐测试：zh_CN 与 en_US 的扁平化键集合必须完全相等，
 * 缺键 / 多键都算失败并输出差集定位。
 */
class LangKeyParityTest {

    /** zh_CN 与 en_US 的键集合完全相等。 */
    @Test
    void zhAndEnKeySetsAreIdentical() {
        Set<String> zh = flattenKeys("/lang/zh_CN.yml");
        Set<String> en = flattenKeys("/lang/en_US.yml");

        Set<String> onlyInZh = new TreeSet<>(zh);
        onlyInZh.removeAll(en);
        Set<String> onlyInEn = new TreeSet<>(en);
        onlyInEn.removeAll(zh);

        assertEquals(Set.of(), onlyInZh, "以下键只存在于 zh_CN，en_US 缺失: " + onlyInZh);
        assertEquals(Set.of(), onlyInEn, "以下键只存在于 en_US，zh_CN 缺失: " + onlyInEn);
    }

    /** 两侧都至少包含 M2 的核心键（防止两个文件同时为空的假绿）。 */
    @Test
    void coreKeysPresent() {
        Set<String> zh = flattenKeys("/lang/zh_CN.yml");
        for (String required : new String[]{
                "prefix", "command.help.lines", "command.reload.success",
                "command.error.internal", "chat.rarity-hover"}) {
            assertFalse(zh.stream().noneMatch(required::equals), "zh_CN 缺少核心键: " + required);
        }
    }

    /**
     * 从 classpath 读取 lang YAML 并递归展平为点号键集合
     * （与运行时语言快照的嵌套对象扁平化语义一致；列表与标量都是叶子）。
     */
    private static Set<String> flattenKeys(String resource) {
        InputStream in = LangKeyParityTest.class.getResourceAsStream(resource);
        assertNotNull(in, "测试类路径上找不到资源: " + resource);
        try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            Object root = new Yaml().load(reader);
            Set<String> keys = new TreeSet<>();
            collect(asMap(root, resource), "", keys);
            return keys;
        } catch (Exception e) {
            throw new IllegalStateException("解析 lang 文件失败: " + resource, e);
        }
    }

    /** 递归收集叶子键。 */
    private static void collect(Map<?, ?> node, String prefix, Set<String> keys) {
        for (Map.Entry<?, ?> entry : node.entrySet()) {
            String path = prefix.isEmpty() ? String.valueOf(entry.getKey())
                    : prefix + "." + entry.getKey();
            if (entry.getValue() instanceof Map<?, ?> nested) {
                collect(nested, path, keys);
            } else {
                keys.add(path);
            }
        }
    }

    private static Map<?, ?> asMap(Object root, String resource) {
        if (root instanceof Map<?, ?> map) {
            return map;
        }
        throw new IllegalStateException("lang 文件根节点不是对象: " + resource);
    }
}
