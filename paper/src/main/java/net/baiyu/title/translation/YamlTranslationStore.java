package net.baiyu.title.translation;

import org.slf4j.Logger;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** YAML 语言文件提取、解析与点号键扁平化。 */
final class YamlTranslationStore {

    private static final String FALLBACK_LANGUAGE = "en_US";
    private static final List<String> BUNDLED_LANGUAGES = List.of("en_US", "zh_CN");

    private YamlTranslationStore() {
    }

    /**
     * 确保内置语言文件存在，随后载入当前语言和回退语言。
     */
    static Snapshot load(Path languageDirectory, String activeLanguage, Logger logger) {
        Objects.requireNonNull(languageDirectory, "语言目录不能为空");
        Objects.requireNonNull(logger, "日志器不能为空");
        final String selected = activeLanguage == null || activeLanguage.isBlank()
                ? FALLBACK_LANGUAGE : activeLanguage;
        try {
            Files.createDirectories(languageDirectory);
            for (String language : BUNDLED_LANGUAGES) {
                extractIfMissing(languageDirectory, language, logger);
            }

            final Map<String, Object> fallback = read(
                    languageDirectory.resolve(FALLBACK_LANGUAGE + ".yml"));
            final Map<String, Object> active = selected.equals(FALLBACK_LANGUAGE)
                    ? fallback : read(languageDirectory.resolve(selected + ".yml"));
            return new Snapshot(active, fallback);
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException(
                    "加载语言文件失败: lang/" + selected + ".yml", e);
        }
    }

    private static void extractIfMissing(Path languageDirectory, String language, Logger logger)
            throws IOException {
        final Path target = languageDirectory.resolve(language + ".yml");
        if (Files.isRegularFile(target)) {
            return;
        }
        final Path legacyJson = languageDirectory.resolve(language + ".json");
        if (Files.isRegularFile(legacyJson)) {
            try (Reader reader = Files.newBufferedReader(legacyJson, StandardCharsets.UTF_8);
                 Writer writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
                final Object legacy = new Yaml().load(reader);
                if (!(legacy instanceof Map<?, ?>)) {
                    throw new IOException("旧语言文件根节点必须是对象: " + legacyJson);
                }
                new Yaml().dump(legacy, writer);
                logger.info("已将旧语言文件 {} 转换为 {}；旧文件保留为备份。",
                        legacyJson.getFileName(), target.getFileName());
                return;
            } catch (IOException | RuntimeException e) {
                Files.deleteIfExists(target);
                throw new IOException("转换旧语言文件失败: " + legacyJson, e);
            }
        }
        final String resource = "/lang/" + language + ".yml";
        try (InputStream input = YamlTranslationStore.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("jar 内缺少语言资源: " + resource);
            }
            Files.copy(input, target);
        }
    }

    private static Map<String, Object> read(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IOException("语言文件不存在: " + file);
        }
        final Object root;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            root = new Yaml().load(reader);
        }
        if (!(root instanceof Map<?, ?> map)) {
            throw new IOException("语言文件根节点必须是对象: " + file);
        }
        final Map<String, Object> flattened = new LinkedHashMap<>();
        flatten("", map, flattened);
        return Map.copyOf(flattened);
    }

    private static void flatten(String prefix, Map<?, ?> source, Map<String, Object> target) {
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            final String part = String.valueOf(entry.getKey());
            final String key = prefix.isEmpty() ? part : prefix + "." + part;
            final Object value = entry.getValue();
            if (value instanceof Map<?, ?> nested) {
                flatten(key, nested, target);
            } else if (value instanceof List<?> list) {
                target.put(key, list.stream().map(String::valueOf).toList());
            } else if (value != null) {
                target.put(key, String.valueOf(value));
            }
        }
    }

    /** 当前语言 + en_US 回退语言的不可变扁平快照。 */
    record Snapshot(Map<String, Object> active, Map<String, Object> fallback) {

        Snapshot {
            active = Map.copyOf(active);
            fallback = Map.copyOf(fallback);
        }

        static Snapshot empty() {
            return new Snapshot(Map.of(), Map.of());
        }

        String raw(String key) {
            final Object activeValue = active.get(key);
            if (activeValue instanceof String value) {
                return value;
            }
            final Object fallbackValue = fallback.get(key);
            return fallbackValue instanceof String value ? value : key;
        }

        @SuppressWarnings("unchecked")
        List<String> list(String key) {
            final Object activeValue = active.get(key);
            if (activeValue instanceof List<?>) {
                return (List<String>) activeValue;
            }
            final Object fallbackValue = fallback.get(key);
            return fallbackValue instanceof List<?> ? (List<String>) fallbackValue : List.of();
        }
    }
}
