package net.baiyu.title.gui.admin;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 称号编辑按钮当前值的纯函数回归测试。 */
class EditorButtonsTest {

    /** namespaced 材质键应解析后交给物品名注册表，而非原样显示。 */
    @Test
    void iconMaterialUsesLocalizedItemName() {
        assertEquals(Component.text("生鳕鱼"), EditorButtons.iconComponent(
                "minecraft:cod", material -> material == Material.COD
                        ? Component.text("生鳕鱼") : Component.text("unexpected")));
    }

    /** 损坏的历史材质键保留原值，管理员仍能识别并重新录入。 */
    @Test
    void invalidIconMaterialKeepsRawValue() {
        assertEquals(Component.text("legacy:missing"), EditorButtons.iconComponent(
                "legacy:missing", Component::translatable));
    }
}
