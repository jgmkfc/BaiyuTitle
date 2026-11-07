package net.baiyu.title.chat;

import net.baiyu.title.cache.PlayerProfileCache;
import net.baiyu.title.cache.TitleCatalogCache;
import net.baiyu.title.domain.condition.PurchaseCondition;
import net.baiyu.title.domain.title.CombineLogic;
import net.baiyu.title.domain.title.LimitMode;
import net.baiyu.title.domain.title.Title;
import net.baiyu.title.domain.title.TitleCategory;
import net.baiyu.title.translation.Lang;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 聊天前缀构建纯函数单测。
 *
 * <p>只驱动 {@link ChatDisplayService} 的纯函数三件套（composePrefix /
 * renderSlot / renderTitle），视图、格式快照与随机源全部显式注入，
 * 不触碰 Bukkit 与运行时语言存储。</p>
 */
class ChatDisplayServiceTest {

    /** 展示格式样例：括号段带空格（§1.5.2 空格回归点）。 */
    private static final ChatDisplayService.DisplayFormat FORMAT =
            new ChatDisplayService.DisplayFormat("<gray>[ ", "<gray> ]", "<gray> | ", " ", "<white>[新人]");

    /** 被测服务（配置提供器与视图门面在纯函数路径上不被触碰）。 */
    private final ChatDisplayService service = new ChatDisplayService(
            new ChatDisplayCache(new PlayerProfileCache(), new TitleCatalogCache()),
            () -> null,
            new RarityHoverFactory(List::of, new Lang()));

    /** 空视图列表（全部栏位停用）→ 无前缀。 */
    @Test
    void composePrefix_emptySlots_returnsEmpty() {
        Optional<Component> prefix = service.composePrefix(List.of(), FORMAT, new FixedRandom(0));
        assertTrue(prefix.isEmpty());
    }

    /** 多称号以分隔段连接，末尾追加称号-玩家名分隔段；括号空格逐字保留。 */
    @Test
    void composePrefix_joinsSeparatorsAndKeepsSpaces() {
        var slots = List.of(
                new ChatDisplayCache.EquippedSlotView(1, false, List.of(title(1, "剑圣"))),
                new ChatDisplayCache.EquippedSlotView(2, false, List.of(title(2, "诗人"))));

        Component prefix = service.composePrefix(slots, FORMAT, new FixedRandom(0)).orElseThrow();
        assertEquals("[ 剑圣 ] | [ 诗人 ] ", plain(prefix), "括号内空格与分隔段必须逐字保留（不 trim）");
    }

    /** 候选空集 = 默认称号：整段渲染 defaultTitle，不套括号段，不触碰随机源。 */
    @Test
    void renderSlot_emptyCandidates_rendersDefaultTitle() {
        FixedRandom random = new FixedRandom(0);
        Component slot = service.renderSlot(
                new ChatDisplayCache.EquippedSlotView(1, false, List.of()), FORMAT, random);

        assertEquals("[新人]", plain(slot));
        assertEquals(0, random.calls, "默认称号不应消耗随机数");
    }

    /** 单候选：直取不采样；多候选：恰取样一次并渲染被选中项。 */
    @Test
    void renderSlot_samplesExactlyOnceOnlyWhenNeeded() {
        FixedRandom single = new FixedRandom(0);
        service.renderSlot(new ChatDisplayCache.EquippedSlotView(1, false,
                List.of(title(1, "独苗"))), FORMAT, single);
        assertEquals(0, single.calls, "单候选不应消耗随机数");

        FixedRandom picking = new FixedRandom(2);
        Component slot = service.renderSlot(new ChatDisplayCache.EquippedSlotView(2, true,
                List.of(title(1, "甲"), title(2, "乙"), title(3, "丙"))), FORMAT, picking);
        assertEquals("[ 丙 ]", plain(slot), "应渲染随机源选中的候选");
        assertEquals(1, picking.calls, "随机栏位每次渲染恰取样一次（同消息全观察者一致）");
    }

    /** 三段拼接不 trim：称号名自带首尾空格也逐字保留。 */
    @Test
    void renderTitle_preservesInnerAndOuterSpaces() {
        Component unit = service.renderTitle(title(1, "<red> 王者 "), FORMAT);
        assertEquals("[  王者  ]", plain(unit), "称号名首尾空格与括号段空格都不得丢失");
    }

    /** 无稀有度（null）与未知稀有度（不在 config 列表）都不挂 hover。 */
    @Test
    void renderTitle_noHoverWithoutValidRarity() {
        assertNull(service.renderTitle(title(1, "无稀"), FORMAT).style().hoverEvent());
        assertNull(service.renderTitle(
                titleWithRarity(2, "野称号", "不存在的稀有度"), FORMAT).style().hoverEvent());
    }

    /** 组件转纯文本。 */
    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    /** 构造最小合法称号。 */
    private static Title title(int id, String name) {
        return titleWithRarity(id, name, null);
    }

    /** 构造带稀有度的最小合法称号。 */
    private static Title titleWithRarity(int id, String name, String rarity) {
        return new Title(id, name, name, TitleCategory.NORMAL, CombineLogic.AND, true, true,
                rarity, List.of(), "NAME_TAG", 0, LimitMode.NONE, null, null, null, 0,
                null, null, null, 1_000L, 1_000L, null,
                List.<PurchaseCondition>of(), Set.of());
    }

    /** 计数随机源：只允许经 {@code nextInt(bound)} 取样，并统计调用次数。 */
    private static final class FixedRandom implements RandomGenerator {

        /** 固定返回的下标（超界时压到 bound-1）。 */
        private final int fixed;

        /** nextInt 调用次数。 */
        private int calls;

        FixedRandom(int fixed) {
            this.fixed = fixed;
        }

        @Override
        public int nextInt(int bound) {
            calls++;
            return Math.min(fixed, bound - 1);
        }

        @Override
        public long nextLong() {
            throw new AssertionError("渲染路径只应使用 nextInt(bound) 取样");
        }
    }
}
