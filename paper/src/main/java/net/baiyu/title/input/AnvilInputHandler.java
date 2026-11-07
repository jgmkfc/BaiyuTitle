package net.baiyu.title.input;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MenuType;
import org.bukkit.inventory.view.AnvilView;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 铁砧输入子处理器（表现层设计 §5；裁决⑬：Paper 原生 {@code MenuType.ANVIL}
 * 虚拟视图，不引第三方库）：左槽放命名牌，PrepareAnvilEvent 读重命名文本并
 * 生成结果预览（修理花费清零），点结果槽=完成、关闭界面=取消。
 *
 * <p>虚拟视图纪律：视图内一切点击/拖拽一律取消（物品不进不出）；会话
 * 结束（完成/取消/顶替）时清空左槽，防止命名牌在关闭返还时落入玩家背包。</p>
 *
 * <p>生命周期归 {@link InputSessionManager}（open 前已置 expectingInput、
 * cancel/cancelAll 统一入口）；本类由 ListenerHandler 与管理器一并注册，
 * 全部方法仅主线程调用（涉及的库存事件均为同步事件）。</p>
 */
public final class AnvilInputHandler implements Listener {

    /** 铁砧结果槽的 rawSlot（0=左输入、1=右输入、2=结果）。 */
    private static final int RESULT_SLOT = 2;

    /** 公共支撑。 */
    private final InputSupport support;

    /** 铁砧会话表（仅主线程访问）。 */
    private final Map<UUID, AnvilSession> sessions = new HashMap<>();

    /** 构造铁砧输入子处理器（仅 {@link InputSessionManager} 调用）。 */
    AnvilInputHandler(InputSupport support) {
        this.support = support;
    }

    /**
     * 打开铁砧输入视图（管理器已置 expectingInput；打开新视图引发的
     * 旧菜单关闭走 §3.5「输入切换」分支，返回栈保持）。
     */
    void open(Player player, AnvilInputRequest request) {
        final AnvilView view = MenuType.ANVIL.create(player, support.title(request.titleKey()));
        final ItemStack tag = new ItemStack(Material.NAME_TAG);
        tag.editMeta(meta -> meta.displayName(support.itemName("input.anvil.item-name")));
        view.getTopInventory().setItem(0, tag);
        sessions.put(player.getUniqueId(), new AnvilSession(request, view));
        player.openInventory(view);
    }

    /** @return 该玩家是否处于铁砧输入会话 */
    boolean isCapturing(UUID uuid) {
        return sessions.containsKey(uuid);
    }

    /** @return 当前全部铁砧会话的玩家 UUID 快照 */
    Set<UUID> capturedIds() {
        return Set.copyOf(sessions.keySet());
    }

    /**
     * 行政取消（不导航、不发提示）：清左槽、执行 onCancel、关闭残留视图。
     * <p>先摘会话再关视图——随之而来的 InventoryCloseEvent 查不到会话，
     * 不会二次走取消流程。</p>
     */
    void cancelQuietly(UUID uuid) {
        final AnvilSession session = sessions.remove(uuid);
        if (session == null) {
            return;
        }
        session.view().getTopInventory().clear();
        support.runCancel(session.request().onCancel());
        final Player player = Bukkit.getPlayer(uuid);
        if (player != null && player.isOnline()) {
            player.closeInventory();
        }
    }

    /**
     * 结果预览：按当前重命名文本复制左槽命名牌为结果物品，修理花费清零
     * （免等级消耗，结果槽恒可点）。
     *
     * @param event 铁砧备料事件
     */
    @EventHandler
    public void onPrepare(PrepareAnvilEvent event) {
        final AnvilSession session = sessions.get(event.getView().getPlayer().getUniqueId());
        if (session == null || event.getView() != session.view()) {
            return;
        }
        final String text = event.getView().getRenameText();
        final ItemStack base = event.getView().getTopInventory().getItem(0);
        if (base != null && text != null && !text.isBlank()) {
            final ItemStack result = base.clone();
            result.editMeta(meta -> meta.displayName(
                    Component.text(text).decoration(TextDecoration.ITALIC, false)));
            event.setResult(result);
        }
        event.getView().setRepairCost(0);
    }

    /**
     * 点击处理：虚拟视图内一切点击取消；点结果槽=尝试完成——读重命名文本
     * 交校验器（码点计长在校验器内），失败报错且界面保持打开重试。
     *
     * @param event 库存点击事件
     */
    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        final AnvilSession session = sessions.get(player.getUniqueId());
        if (session == null || event.getView() != session.view()) {
            return;
        }
        event.setCancelled(true);
        if (event.getRawSlot() != RESULT_SLOT) {
            return;
        }
        final String text = session.view().getRenameText();
        final String input = text == null ? "" : text.trim();
        final var error = session.request().validator().check(input);
        if (error.isPresent()) {
            support.sendError(player, error.get());
            return;
        }
        sessions.remove(player.getUniqueId());
        session.view().getTopInventory().clear();
        support.runComplete(session.request().onComplete(), input);
        support.finishReturn(player, session.request().returnTo());
    }

    /**
     * 拖拽一律取消（防物品拖入虚拟视图）。
     *
     * @param event 库存拖拽事件
     */
    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        final AnvilSession session = sessions.get(player.getUniqueId());
        if (session != null && event.getView() == session.view()) {
            event.setCancelled(true);
        }
    }

    /**
     * 关闭=取消：清左槽防返还、执行 onCancel 并回菜单。
     * <p>完成路径与行政取消都先摘会话，这里查不到即直接放行。</p>
     *
     * @param event 库存关闭事件
     */
    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        final AnvilSession session = sessions.get(player.getUniqueId());
        // Opening the anvil closes the source GUI after this session is registered.
        // Ignore that stale close event; only the tracked anvil may cancel the input.
        if (session == null || event.getView() != session.view()) {
            return;
        }
        sessions.remove(player.getUniqueId());
        session.view().getTopInventory().clear();
        support.send(player, "input.cancelled");
        support.runCancel(session.request().onCancel());
        support.finishReturn(player, session.request().returnTo());
    }

    /**
     * 铁砧会话载体。
     *
     * @param request 输入请求
     * @param view    打开的铁砧视图（读重命名文本 / 清左槽用）
     */
    private record AnvilSession(AnvilInputRequest request, AnvilView view) {
    }
}
