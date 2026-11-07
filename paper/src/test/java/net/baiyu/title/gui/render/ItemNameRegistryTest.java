package net.baiyu.title.gui.render;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 物品名注册表单测：映射取词、三级回退链
 * （当前语言 → en_US → 枚举名驼峰化）、内置文件提取不覆盖、reload 可重载。
 * <p>临时目录扮演生产的 {@code <数据目录>/lang/items}；内置资源来自
 * classpath {@code /items/<lang>.json}（真实交付文件）。</p>
 */
class ItemNameRegistryTest {

    /** 测试日志（吞掉告警输出即可）。 */
    private static final Logger LOGGER = Logger.getLogger(ItemNameRegistryTest.class.getName());

    /** 临时物品名目录。 */
    @TempDir
    Path itemsDir;

    /** 首次 reload：内置双语文件被提取到数据目录，并按当前语言取词。 */
    @Test
    void reload_extractsBundledFilesAndReadsActiveLanguage() {
        ItemNameRegistry registry = new ItemNameRegistry(itemsDir, () -> "zh_CN", LOGGER);
        registry.reload();

        assertTrue(Files.isRegularFile(itemsDir.resolve("zh_CN.json")), "应提取 zh_CN.json");
        assertTrue(Files.isRegularFile(itemsDir.resolve("en_US.json")), "应提取 en_US.json");
        assertEquals("竹子", registry.displayName(Material.BAMBOO));
        assertEquals("命名牌", registry.displayName(Material.NAME_TAG));
        assertEquals("红石粉", registry.displayName(Material.REDSTONE));
    }

    /** 三级回退链：数据文件缺键 → en_US；两侧皆缺 → 枚举名驼峰化。 */
    @Test
    void displayName_fallsBackThroughEnUsToPrettifiedName() throws Exception {
        ItemNameRegistry registry = new ItemNameRegistry(itemsDir, () -> "zh_CN", LOGGER);
        registry.reload();
        // 收窄 zh_CN 数据文件为单键，制造缺键场景
        Files.writeString(itemsDir.resolve("zh_CN.json"), "{\"BAMBOO\":\"竹子\"}", StandardCharsets.UTF_8);
        registry.reload();

        assertEquals("竹子", registry.displayName(Material.BAMBOO));
        assertEquals("Gold Ingot", registry.displayName(Material.GOLD_INGOT),
                "zh 缺键应回退 en_US");
        assertEquals("Music Disc Cat", registry.displayName(Material.MUSIC_DISC_CAT),
                "双侧缺键应回退枚举名驼峰化");
    }

    /** 提取不覆盖：服主自定义的数据文件在 reload 后原样生效。 */
    @Test
    void reload_neverOverwritesExistingDataFiles() throws Exception {
        Files.writeString(itemsDir.resolve("zh_CN.json"), "{\"bamboo\":\"自定义竹\"}", StandardCharsets.UTF_8);
        ItemNameRegistry registry = new ItemNameRegistry(itemsDir, () -> "zh_CN", LOGGER);
        registry.reload();

        assertEquals("自定义竹", registry.displayName(Material.BAMBOO),
                "已存在的数据文件不得被内置资源覆盖（键容忍小写）");
    }

    /** 语言切换 + reload：新语言立即生效（reload 白名单流程）。 */
    @Test
    void reload_picksUpLanguageChange() {
        String[] language = {"zh_CN"};
        ItemNameRegistry registry = new ItemNameRegistry(itemsDir, () -> language[0], LOGGER);
        registry.reload();
        assertEquals("竹子", registry.displayName(Material.BAMBOO));

        language[0] = "en_US";
        registry.reload();
        assertEquals("Bamboo", registry.displayName(Material.BAMBOO));
    }

    /** 未内置语言：active 为空映射，整体走 en_US 回退。 */
    @Test
    void unknownLanguage_fallsBackToEnUs() {
        ItemNameRegistry registry = new ItemNameRegistry(itemsDir, () -> "ja_JP", LOGGER);
        registry.reload();

        assertEquals("Bamboo", registry.displayName(Material.BAMBOO));
        assertEquals("Redstone Dust", registry.displayName(Material.REDSTONE));
    }

    /** reload 前（快照为空）与损坏文件都安全降级到回退链，不抛出。 */
    @Test
    void degradesGracefullyBeforeReloadAndOnCorruptFile() throws Exception {
        ItemNameRegistry registry = new ItemNameRegistry(itemsDir, () -> "zh_CN", LOGGER);
        assertEquals("Redstone", registry.displayName(Material.REDSTONE),
                "reload 前应走枚举名驼峰化兜底");

        Files.writeString(itemsDir.resolve("zh_CN.json"), "{损坏的 JSON", StandardCharsets.UTF_8);
        registry.reload();
        assertEquals("Redstone Dust", registry.displayName(Material.REDSTONE),
                "损坏语言文件应降级为空映射并回退 en_US");
    }

    /** 驼峰化兜底规则。 */
    @Test
    void prettify_convertsEnumNames() {
        assertEquals("Gold Ingot", ItemNameRegistry.prettify("GOLD_INGOT"));
        assertEquals("Bamboo", ItemNameRegistry.prettify("BAMBOO"));
        assertEquals("Music Disc Cat", ItemNameRegistry.prettify("MUSIC_DISC_CAT"));
    }

    /** displayComponent：JSON 命中返回纯文本组件（与 displayName 同源取词）。 */
    @Test
    void displayComponent_returnsTextForJsonHit() {
        ItemNameRegistry registry = new ItemNameRegistry(itemsDir, () -> "zh_CN", LOGGER);
        registry.reload();
        assertEquals(Component.text("竹子"), registry.displayComponent(Material.BAMBOO));
        assertEquals(Component.text("命名牌"), registry.displayComponent(Material.NAME_TAG));
    }

    /** displayComponent：JSON 双侧缺键时，生产环境回退 translatable（客户端翻译）；
     *  纯单测环境 RegistryAccess 不可用，回退枚举名驼峰化文本（与 displayName 兜底一致）。 */
    @Test
    void displayComponent_fallsBackWhenJsonMisses() throws Exception {
        ItemNameRegistry registry = new ItemNameRegistry(itemsDir, () -> "zh_CN", LOGGER);
        registry.reload();
        // 收窄 zh_CN 数据文件为单键，制造缺键场景
        Files.writeString(itemsDir.resolve("zh_CN.json"), "{\"BAMBOO\":\"竹子\"}", StandardCharsets.UTF_8);
        registry.reload();

        // COD 不在 zh_CN / en_US 表中：
        //  - 生产服务器（RegistryAccess 可用）→ Component.translatable(COD)，中文客户端显示「生鳕鱼」
        //  - 纯单测（RegistryAccess 不可用）→ 回退 Component.text("Cod")
        // 单测环境断言降级文本；translatable 路径由集成测试（真实服务器）覆盖
        assertEquals(Component.text("Cod"), registry.displayComponent(Material.COD),
                "单测环境 RegistryAccess 不可用应回退驼峰化文本");
    }
}
