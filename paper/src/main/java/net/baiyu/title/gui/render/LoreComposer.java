package net.baiyu.title.gui.render;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.jetbrains.annotations.Nullable;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 分区 lore 组装器。
 *
 * <p>lore 由若干<b>区段组</b>构成，非空组之间以空行 {@link #BLANK_LINE} 分隔
 * （对应 lore 字符画中的 {@code ' '} 行）。本阶段实现仓库 / 装扮上下文
 * 需要的区段：状态区（到期时间 + 装扮态，满足 {@code &a} 不满足 {@code &7}）、
 * 稀有度行、简介区、操作提示行；购买区 / 限定区在 M4 商店上下文扩展。
 * 注意稀有度行与简介区在设计字符画中<b>相邻无空行</b>——两者应并入同一组传给
 * {@link #compose(List[])}。</p>
 *
 * <p>渲染管线（{@link LoreLines} 包内共享）：模板经 {@code ColorNormalizer} 归一化后由
 * MiniMessage 直接反序列化，占位符一律 {@code Placeholder#parsed}（预处理替换，
 * 值先归一化再插入）；刻意不走 ColorParser——其 build() 触碰 Bukkit
 * （探测 PlaceholderAPI）无法单测，亦不应对 lore 文本做占位符展开
 * （与 chat.ChatDisplayService#parse 同理）。全类纯函数、零 Bukkit 依赖。</p>
 */
public final class LoreComposer {

    /** 区段组之间的空行分隔。 */
    public static final Component BLANK_LINE = Component.text(" ");

    /** lore 模板取词接缝（生产为 {@code lang::raw}）。 */
    private final LoreTemplateSource templates;

    /**
     * 构造分区 lore 组装器。
     *
     * @param templates lore 模板取词接缝
     */
    public LoreComposer(LoreTemplateSource templates) {
        this.templates = Objects.requireNonNull(templates, "lore 模板取词接缝不能为空");
    }

    /**
     * 操作提示行类型（业务设计 §1.3.2.4.2；商店侧的购买 / 无法购买 / 跳转仓库
     * 操作行在 M4 扩展本枚举）。
     */
    public enum ActionType {

        /** 未装扮 → 点击装扮（{@code &8[&a✔&8] &f点击装扮}）。 */
        EQUIP("gui-item.action.equip"),

        /** 已装扮 → 点击卸下（{@code &8[&cx&8] &7点击卸下}）。 */
        UNEQUIP("gui-item.action.unequip");

        /** 对应的 lang 模板键。 */
        private final String templateKey;

        ActionType(String templateKey) {
            this.templateKey = templateKey;
        }
    }

    /**
     * 状态区：表头 + 到期时间行 + 装扮态行。
     * <p>表头裁决：文档两例表头不一致（例1 {@code 状态:}、例2 {@code 个人状态:}），
     * 取例1——仓库语境的"状态"指称号持有状态，与商店"个人状态"（资源对照区，
     * M4 扩展为独立区段）语义不同，例2 判为商店表头误写。</p>
     *
     * @param expiresOn 到期日期（null = 永久，渲染 {@code format.permanent}）
     * @param equipped  是否装扮中（满足 {@code &a ✔ 已装扮}，不满足 {@code &7 × 未装扮}）
     * @return 状态区组件行
     */
    public List<Component> statusSection(@Nullable LocalDate expiresOn, boolean equipped) {
        return List.of(
                line("gui-item.section.status"),
                line("gui-item.line.expiry", value("expiry", expiryText(expiresOn))),
                line(equipped ? "gui-item.line.equipped" : "gui-item.line.not-equipped"));
    }

    /**
     * 稀有度行（{@code &b ■  &f稀有度: &6史诗}）。
     * <p>设计字符画中稀有度行紧邻简介表头（两者之间无空行），调用侧应把本行
     * 与 {@link #descriptionSection(List)} 并入同一组传给 {@link #compose(List[])}。</p>
     *
     * @param rarity 稀有度名称（null / 空白 = 无稀有度，整行省略）
     * @return 稀有度行；无稀有度时为空列表
     */
    public List<Component> raritySection(@Nullable String rarity) {
        if (rarity == null || rarity.isBlank()) {
            return List.of();
        }
        return List.of(line("gui-item.section.rarity", value("rarity", rarity)));
    }

    /**
     * 简介区：表头 + 逐行简介正文。
     *
     * @param description 简介多行文本（空列表 = 整区省略，含前置空行）
     * @return 简介区组件行；无简介时为空列表
     */
    public List<Component> descriptionSection(List<String> description) {
        Objects.requireNonNull(description, "简介行列表不能为空（可传空列表）");
        if (description.isEmpty()) {
            return List.of();
        }
        List<Component> lines = new ArrayList<>(description.size() + 1);
        lines.add(line("gui-item.section.description"));
        for (String row : description) {
            lines.add(line("gui-item.line.description", value("line", row)));
        }
        return List.copyOf(lines);
    }

    /**
     * 操作提示行（lore 末行；仓库版 lore 无此行——业务设计 §1.3.2.4.2
     * "仓库菜单的lore只需要去掉最后两行"）。
     *
     * @param action 操作类型
     * @return 单行操作提示
     */
    public List<Component> actionLine(ActionType action) {
        Objects.requireNonNull(action, "操作类型不能为空");
        return List.of(line(action.templateKey));
    }

    /**
     * 组装完整 lore：按传入顺序连接各区段组，非空组之间插入空行分隔，
     * 空组整体跳过（不产生悬空空行）。
     *
     * @param groups 区段组（每组为一段连续行；稀有度行 + 简介区应并作一组）
     * @return 完整 lore 组件行（不可变列表）
     */
    @SafeVarargs
    public final List<Component> compose(List<Component>... groups) {
        List<Component> result = new ArrayList<>();
        for (List<Component> group : groups) {
            if (group.isEmpty()) {
                continue;
            }
            if (!result.isEmpty()) {
                result.add(BLANK_LINE);
            }
            result.addAll(group);
        }
        return List.copyOf(result);
    }

    /**
     * 到期时间文本：永久取 {@code format.permanent}；有日期时对 {@code format.date}
     * 做值级插值（year/month/day 为纯数字，不补零——对照文档示例
     * {@code 2027年5月14号}；数字值无解析歧义，此处用字符串替换而非 TagResolver）。
     */
    private String expiryText(@Nullable LocalDate expiresOn) {
        if (expiresOn == null) {
            return templates.raw("format.permanent");
        }
        return templates.raw("format.date")
                .replace("<year>", Integer.toString(expiresOn.getYear()))
                .replace("<month>", Integer.toString(expiresOn.getMonthValue()))
                .replace("<day>", Integer.toString(expiresOn.getDayOfMonth()));
    }

    /** 取模板 → 归一化 → MiniMessage 反序列化为单行组件（委托 {@link LoreLines}）。 */
    private Component line(String key, TagResolver... resolvers) {
        return LoreLines.line(templates, key, resolvers);
    }

    /** 构造预处理占位符：值先归一化（兼容 &amp; 码简介 / 稀有度文本）再插入解析流。 */
    private static TagResolver value(String name, String rawValue) {
        return LoreLines.value(name, rawValue);
    }
}
