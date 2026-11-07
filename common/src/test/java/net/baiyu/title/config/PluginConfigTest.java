package net.baiyu.title.config;

import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.CommentedConfigurationNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 主配置默认值与版本迁移回归测试。 */
class PluginConfigTest {

    @Test
    void chatListenerDefaultsToFinalPriority() {
        assertEquals("MONITOR", new PluginConfig().chat.listenerPriority);
    }

    @Test
    void versionOneDefaultHighMigratesToMonitor() throws Exception {
        final CommentedConfigurationNode root = CommentedConfigurationNode.root();
        root.node("config-version").set(1);
        root.node("chat", "listener-priority").set("HIGH");

        final PluginConfig defaults = new PluginConfig();
        defaults.migrator().apply(root);

        assertEquals(3, defaults.migrator().version(root), "迁移应一路升到当前版本");
        assertEquals("MONITOR", root.node("chat", "listener-priority").getString());
    }

    /** v3 删除三处从不生效的配置项（更新检查器 / 自动佩戴 / 自定义长度）。 */
    @Test
    void versionTwoDropsInertKeys() throws Exception {
        final CommentedConfigurationNode root = CommentedConfigurationNode.root();
        root.node("config-version").set(2);
        root.node("update-checker", "enabled").set(true);
        root.node("title", "is-auto-use").set(false);
        root.node("title", "custom-length").set(16);
        root.node("title", "is-tab").set(true);

        final PluginConfig defaults = new PluginConfig();
        defaults.migrator().apply(root);

        assertEquals(3, defaults.migrator().version(root));
        assertTrue(root.node("update-checker").virtual(), "更新检查器整段应被删除");
        assertTrue(root.node("title", "is-auto-use").virtual(), "is-auto-use 应被删除");
        assertTrue(root.node("title", "custom-length").virtual(), "custom-length 应被删除");
        assertEquals(true, root.node("title", "is-tab").getBoolean(), "同段保留项不得误删");
    }

    /** 已删除的配置项不再出现在序列化结果里（避免重建 config.yml 时复活）。 */
    @Test
    void removedKeysAreAbsentFromSerializedDefaults() throws Exception {
        final CommentedConfigurationNode root = CommentedConfigurationNode.root();
        root.set(new PluginConfig());

        assertTrue(root.node("update-checker").virtual());
        assertTrue(root.node("title", "is-auto-use").virtual());
        assertTrue(root.node("title", "custom-length").virtual());
        assertEquals(3, root.node("config-version").getInt());
    }

    /** 序列化键名走 Configurate 默认 kebab-case（迁移 NodePath 必须与此一致）。 */
    @Test
    void serializedKeysUseKebabCase() throws Exception {
        final CommentedConfigurationNode root = CommentedConfigurationNode.root();
        root.set(new PluginConfig());

        assertFalse(root.node("title", "is-tab").virtual(), "isTab → is-tab");
        assertFalse(root.node("title", "toggles-cool-down").virtual(), "togglesCoolDown → toggles-cool-down");
        assertFalse(root.node("title", "is-chat-format").virtual(), "isChatFormat → is-chat-format");
        assertFalse(root.node("chat", "listener-priority").virtual(), "listenerPriority → listener-priority");
        assertFalse(root.node("menu", "main-menu-command").virtual(), "mainMenuCommand → main-menu-command");
    }
}
