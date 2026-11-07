package net.baiyu.title.gui.render;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 物品名本地化注册表：
 * lore 中出现的 Minecraft 物品名（如物品价 {@code 竹子 × 32}）按语言取词。
 *
 * <p>数据为 {@code <数据目录>/lang/items/<lang>.json} 的扁平映射
 * （{@code "BAMBOO": "竹子"}）；内置默认文件首次启用时从 jar 提取，
 * 已存在的文件不覆盖（服主可自定义）。两条取词回退链：
 * <ul>
 *   <li>{@link #displayComponent(Material)}（GUI / 聊天主路径）：当前语言 → en_US →
 *       {@link Component#translatable(Material)} 客户端按自身语言翻译；</li>
 *   <li>{@link #displayName(Material)}（纯文本遗留接口）：当前语言 → en_US →
 *       Material 枚举名驼峰化（{@code GOLD_INGOT → Gold Ingot}）。</li>
 * </ul>
 * translatable 兜底使插件无需维护完整 Mojang 翻译表即可正确显示任意物品本地化名；
 * JSON 表仅用于服主显式覆盖特定物品名（覆盖优先于客户端翻译）。</p>
 *
 * <p>jar 内资源位于 {@code items/}，运行时提取到
 * {@code lang/items/&lt;language&gt;.json}；主语言文本使用
 * {@code lang/&lt;language&gt;.yml}，两类资源由各自加载器管理。</p>
 *
 * <p>线程模型：{@link #reload()} 在主线程调用（onEnable / reload 白名单流程），
 * 快照 volatile 发布；{@link #displayName(Material)} / {@link #displayComponent(Material)}
 * 任意线程只读。内置资源仅含 GUI 图标材质子集，常规物品名经 translatable 兜底
 * 由客户端翻译，无需补齐完整 Mojang 转换表。</p>
 */
public final class ItemNameRegistry {

    /** 随插件打包的物品名语言（首次启用时提取到数据目录）。 */
    private static final List<String> BUNDLED_LANGUAGES = List.of("zh_CN", "en_US");

    /** 回退语言（缺键时先回退到它，再回退枚举名驼峰化）。 */
    private static final String FALLBACK_LANGUAGE = "en_US";

    /** jar 内物品名资源根。 */
    private static final String RESOURCE_ROOT = "/items/";

    /** 物品名文件目录（生产为 {@code <数据目录>/lang/items}）。 */
    private final Path itemsDirectory;

    /** 当前语言提供器（生产传 {@code () -> configHandler.getConfig().language}）。 */
    private final Supplier<String> languageSupplier;

    /** 日志（提取 / 解析失败降级告警，不中断 reload）。 */
    private final Logger logger;

    /** 当前映射快照（volatile 发布；reload 前为空映射，走驼峰化兜底）。 */
    private volatile Snapshot snapshot = new Snapshot(Map.of(), Map.of());

    /**
     * 构造物品名注册表（构造零 IO；首次装载由 onEnable 流程调用 {@link #reload()}）。
     *
     * @param itemsDirectory   物品名文件目录（{@code <数据目录>/lang/items}）
     * @param languageSupplier 当前语言提供器（随 config reload 取最新值）
     * @param logger           插件日志
     */
    public ItemNameRegistry(Path itemsDirectory, Supplier<String> languageSupplier, Logger logger) {
        this.itemsDirectory = Objects.requireNonNull(itemsDirectory, "物品名文件目录不能为空");
        this.languageSupplier = Objects.requireNonNull(languageSupplier, "语言提供器不能为空");
        this.logger = Objects.requireNonNull(logger, "日志不能为空");
    }

    /**
     * 重载：提取缺失的内置文件（不覆盖已有）→ 按当前语言重读映射并原子发布快照。
     * <p>onEnable 首次装载与 /byt reload 白名单流程共用；失败降级为告警 + 空映射
     * （回退链兜底），不抛出。</p>
     */
    public void reload() {
        extractBundledDefaults();
        String language = languageSupplier.get();
        Map<String, String> active = readLanguage(language);
        Map<String, String> fallback = FALLBACK_LANGUAGE.equals(language)
                ? active
                : readLanguage(FALLBACK_LANGUAGE);
        this.snapshot = new Snapshot(active, fallback);
    }

    /**
     * 取物品本地化名：当前语言 → en_US → Material 枚举名驼峰化。
     *
     * @param material 物品材质
     * @return 本地化物品名（永不为 null）
     */
    public String displayName(Material material) {
        Objects.requireNonNull(material, "物品材质不能为空");
        Snapshot current = this.snapshot;
        String key = material.name();
        String name = current.active().get(key);
        if (name == null) {
            name = current.fallback().get(key);
        }
        return name != null ? name : prettify(key);
    }

    /**
     * 取物品本地化名组件（GUI / 聊天主路径）：当前语言 JSON → 回退语言 JSON →
     * {@link Component#translatable(Material)}（由客户端按自身语言翻译）。
     *
     * <p>JSON 命中时返回纯文本组件（{@link Component#text(String)}），物品名格式
     * 化交由外层 lore 模板负责；未命中时返回 translatable 组件，客户端依据自身
     * 语言设置自动翻译（如 {@code Material.COD} → 中文客户端显示「生鳕鱼」），
     * 无需插件维护完整 Mojang 翻译表。服主仍可在 {@code items/<lang>.json} 中
     * 显式覆盖特定物品名，覆盖优先于客户端翻译。</p>
     *
     * <p>translatable 依赖运行时注册表（{@code RegistryAccess}），在注册表不可用
     * 时（纯单测环境 / 极早期加载阶段）回退枚举名驼峰化文本，避免渲染崩溃——
     * 此降级与 {@link #displayName(Material)} 的兜底一致。生产服务器运行时
     * 注册表恒可用，正常走 translatable 路径。</p>
     *
     * @param material 物品材质
     * @return 本地化物品名组件（永不为 null）
     */
    public Component displayComponent(Material material) {
        Objects.requireNonNull(material, "物品材质不能为空");
        Snapshot current = this.snapshot;
        String key = material.name();
        String name = current.active().get(key);
        if (name == null) {
            name = current.fallback().get(key);
        }
        if (name != null) {
            return Component.text(name);
        }
        // JSON 缺键 → translatable 兜底（客户端按自身语言翻译）；
        // translationKey 依赖运行时注册表（RegistryAccess），其静态初始化在注册表
        // 不可用时抛 LinkageError（ExceptionInInitializerError / NoClassDefFoundError），
        // 此时回退枚举名驼峰化文本，避免渲染崩溃。生产服务器运行时注册表恒可用。
        try {
            return Component.translatable(material);
        } catch (LinkageError | IllegalStateException e) {
            return Component.text(prettify(key));
        }
    }

    /**
     * Material 枚举名驼峰化兜底：{@code GOLD_INGOT → Gold Ingot}
     * （包级可见供单测）。
     */
    static String prettify(String materialName) {
        String[] words = materialName.split("_");
        StringBuilder result = new StringBuilder(materialName.length());
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            if (!result.isEmpty()) {
                result.append(' ');
            }
            result.append(Character.toUpperCase(word.charAt(0)))
                    .append(word.substring(1).toLowerCase(Locale.ROOT));
        }
        return result.toString();
    }

    /** 提取缺失的内置默认文件到数据目录（已存在的不覆盖）。 */
    private void extractBundledDefaults() {
        try {
            Files.createDirectories(itemsDirectory);
        } catch (IOException e) {
            logger.log(Level.WARNING, "创建物品名目录失败: " + itemsDirectory, e);
            return;
        }
        for (String language : BUNDLED_LANGUAGES) {
            Path target = itemsDirectory.resolve(language + ".json");
            if (Files.exists(target)) {
                continue;
            }
            try (InputStream in = ItemNameRegistry.class.getResourceAsStream(RESOURCE_ROOT + language + ".json")) {
                if (in == null) {
                    logger.warning("jar 内缺少内置物品名资源: " + RESOURCE_ROOT + language + ".json");
                    continue;
                }
                Files.copy(in, target);
            } catch (IOException e) {
                logger.log(Level.WARNING, "提取内置物品名文件失败: " + target, e);
            }
        }
    }

    /**
     * 读取一种语言的物品名映射：数据目录文件优先，缺失时回退 jar 内资源，
     * 两者皆无或解析失败返回空映射。
     */
    private Map<String, String> readLanguage(String language) {
        Path file = itemsDirectory.resolve(language + ".json");
        if (Files.isRegularFile(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                return parseFlatMap(reader);
            } catch (IOException | RuntimeException e) {
                logger.log(Level.WARNING, "解析物品名文件失败（该语言临时按回退链取词）: " + file, e);
                return Map.of();
            }
        }
        try (InputStream in = ItemNameRegistry.class.getResourceAsStream(RESOURCE_ROOT + language + ".json")) {
            if (in == null) {
                return Map.of();
            }
            try (Reader reader = new java.io.InputStreamReader(in, StandardCharsets.UTF_8)) {
                return parseFlatMap(reader);
            }
        } catch (IOException | RuntimeException e) {
            logger.log(Level.WARNING, "解析内置物品名资源失败: " + language, e);
            return Map.of();
        }
    }

    /** 解析扁平 JSON 映射；键统一大写（容忍服主写小写材质名），非字符串值忽略。 */
    private static Map<String, String> parseFlatMap(Reader reader) {
        JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
        Map<String, String> map = new HashMap<>(root.size());
        for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
            JsonElement value = entry.getValue();
            if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                map.put(entry.getKey().toUpperCase(Locale.ROOT), value.getAsString());
            }
        }
        return Map.copyOf(map);
    }

    /**
     * 映射快照（不可变，volatile 整体发布）。
     *
     * @param active   当前语言映射
     * @param fallback 回退语言（en_US）映射
     */
    private record Snapshot(Map<String, String> active, Map<String, String> fallback) {
    }
}
