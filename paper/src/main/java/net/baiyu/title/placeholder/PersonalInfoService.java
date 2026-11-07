package net.baiyu.title.placeholder;

import io.github.milkdrinkers.colorparser.paper.ColorParser;
import net.baiyu.title.translation.Lang;
import org.bukkit.entity.Player;

import java.util.Objects;
import java.util.UUID;

/**
 * 个人信息聊天输出。
 *
 * <p>装扮管理菜单 C 键的落点：逐行取 {@code chat.personal-info} 模板，
 * 经 {@link InternalPlaceholderResolver} 展开 %byt_*% 变量后发给玩家；
 * 复用同一解析器，PAPI 不在场亦可用。含 {@code ~} 栏位通配 token 的行
 * 按玩家栏位上限逐栏展开（业务模板"栏位~"行的实现语义）。</p>
 *
 * <p>仅主线程调用（发消息触碰玩家实体）。</p>
 */
public final class PersonalInfoService {

    /** 栏位通配 token（模板中该行按栏位 1..上限逐行展开，行内 {@code ~} 全部替换为栏位号）。 */
    private static final String PER_SLOT_TOKEN = "%byt_player_title_using_~%";

    /** 语言门面。 */
    private final Lang lang;

    /** PAPI 变量内部解析器。 */
    private final InternalPlaceholderResolver resolver;

    /**
     * 构造个人信息聊天输出服务。
     *
     * @param lang     语言门面
     * @param resolver PAPI 变量内部解析器
     */
    public PersonalInfoService(Lang lang, InternalPlaceholderResolver resolver) {
        this.lang = Objects.requireNonNull(lang, "语言门面不能为空");
        this.resolver = Objects.requireNonNull(resolver, "变量解析器不能为空");
    }

    /**
     * 向玩家输出个人信息（<b>仅主线程</b>；模板逐行展开变量后发送）。
     *
     * @param player 玩家
     */
    public void send(Player player) {
        Objects.requireNonNull(player, "玩家不能为空");
        UUID uuid = player.getUniqueId();
        for (String line : lang.rawList("chat.personal-info")) {
            if (line.contains(PER_SLOT_TOKEN)) {
                sendPerSlotLines(player, uuid, line);
            } else {
                sendLine(player, uuid, line);
            }
        }
    }

    /** 栏位通配行：按栏位上限逐栏替换 {@code ~} 后展开发送（无栏位时整行省略）。 */
    private void sendPerSlotLines(Player player, UUID uuid, String template) {
        int slots = resolver.resolve(uuid, "player_title_slots")
                .map(PersonalInfoService::parseIntSafely)
                .orElse(0);
        for (int slotNo = 1; slotNo <= slots; slotNo++) {
            sendLine(player, uuid, template.replace("~", Integer.toString(slotNo)));
        }
    }

    /** 展开单行变量并按混合色码解析后发送。 */
    private void sendLine(Player player, UUID uuid, String line) {
        player.sendMessage(ColorParser.of(resolver.expand(line, uuid)).build());
    }

    /** 数字解析兜底（解析失败按 0，个人信息输出不因异常值中断）。 */
    private static int parseIntSafely(String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
