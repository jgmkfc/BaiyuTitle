package net.baiyu.title.translation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** YAML 语言存储的提取、回退与重载回归测试。 */
class YamlTranslationStoreTest {

    @TempDir
    Path languageDirectory;

    @Test
    void extractsYamlResourcesAndLoadsSelectedLanguage() {
        YamlTranslationStore.Snapshot snapshot = YamlTranslationStore.load(
                languageDirectory, "zh_CN", LoggerFactory.getLogger(getClass()));

        assertTrue(Files.isRegularFile(languageDirectory.resolve("zh_CN.yml")));
        assertTrue(Files.isRegularFile(languageDirectory.resolve("en_US.yml")));
        assertFalse(Files.exists(languageDirectory.resolve("zh_CN.json")));
        assertTrue(snapshot.raw("command.reload.success").contains("重载完成"));
        assertFalse(snapshot.list("command.help.lines").isEmpty());
    }

    @Test
    void reloadsEditedYamlAndFallsBackPerKey() throws Exception {
        YamlTranslationStore.load(languageDirectory, "zh_CN", LoggerFactory.getLogger(getClass()));
        Files.writeString(languageDirectory.resolve("zh_CN.yml"),
                "prefix: '<gold>[自定义]</gold> '", StandardCharsets.UTF_8);

        YamlTranslationStore.Snapshot reloaded = YamlTranslationStore.load(
                languageDirectory, "zh_CN", LoggerFactory.getLogger(getClass()));

        assertEquals("<gold>[自定义]</gold> ", reloaded.raw("prefix"));
        assertFalse(reloaded.list("command.help.lines").isEmpty(), "缺键应回退 en_US");
        assertEquals("missing.key", reloaded.raw("missing.key"));
    }

    @Test
    void migratesLegacyJsonWithoutDeletingBackup() throws Exception {
        Files.writeString(languageDirectory.resolve("zh_CN.json"),
                "{\"prefix\":\"<gold>[旧配置]</gold> \"}", StandardCharsets.UTF_8);

        YamlTranslationStore.Snapshot migrated = YamlTranslationStore.load(
                languageDirectory, "zh_CN", LoggerFactory.getLogger(getClass()));

        assertEquals("<gold>[旧配置]</gold> ", migrated.raw("prefix"));
        assertTrue(Files.isRegularFile(languageDirectory.resolve("zh_CN.yml")));
        assertTrue(Files.isRegularFile(languageDirectory.resolve("zh_CN.json")), "旧 JSON 应保留为备份");
        assertFalse(migrated.list("command.help.lines").isEmpty(), "迁移文件缺键应回退 en_US");
    }

    @Test
    void rejectsMalformedYamlWithUsefulCause() throws Exception {
        YamlTranslationStore.load(languageDirectory, "zh_CN", LoggerFactory.getLogger(getClass()));
        Files.writeString(languageDirectory.resolve("zh_CN.yml"), "broken: [", StandardCharsets.UTF_8);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> YamlTranslationStore.load(
                        languageDirectory, "zh_CN", LoggerFactory.getLogger(getClass())));
        assertTrue(failure.getMessage().contains("lang/zh_CN.yml"));
    }
}
