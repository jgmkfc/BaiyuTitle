package net.baiyu.title.input;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 手持物品左键确认子处理器；生命周期由 {@link InputSessionManager} 统一管理。 */
public final class HeldItemInputHandler implements Listener {

    private static final long TICKS_PER_SECOND = 20L;

    /** 主线程写、异步聊天线程读。 */
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    private final InputSupport support;

    HeldItemInputHandler(InputSupport support) {
        this.support = support;
    }

    /** 打开会话；调用方已经完成旧会话取消与输入仲裁标记。 */
    void open(Player player, HeldItemInputRequest request) {
        player.closeInventory();
        final Session session = new Session(request);
        sessions.put(player.getUniqueId(), session);
        scheduleTimeout(player, session);
        support.send(player, request.promptKey());
        support.send(player, "input.held-item.hint",
                Placeholder.unparsed(
                        "seconds", String.valueOf(InputSessionManager.INPUT_TIMEOUT.toSeconds())));
    }

    boolean isCapturing(UUID uuid) {
        return sessions.containsKey(uuid);
    }

    Set<UUID> capturedIds() {
        return Set.copyOf(sessions.keySet());
    }

    /** 行政取消：不提示、不导航。 */
    void cancelQuietly(UUID uuid) {
        final Session session = sessions.remove(uuid);
        if (session == null) {
            return;
        }
        session.cancelTimeout();
        support.runCancel(session.request.onCancel());
    }

    /** 仅吞掉取消口令；等待物品期间的普通聊天仍正常发送。 */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        final Player player = event.getPlayer();
        final Session session = sessions.get(player.getUniqueId());
        if (session == null) {
            return;
        }
        final String input = PlainTextComponentSerializer.plainText()
                .serialize(event.message()).trim();
        if (!InputSessionManager.isCancelInput(input)) {
            return;
        }
        event.setCancelled(true);
        Bukkit.getScheduler().runTask(support.plugin(), () -> cancelFromChat(player, session));
    }

    /** 主手左键确认；空手时提示并重置超时。 */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND
                || (event.getAction() != Action.LEFT_CLICK_AIR
                && event.getAction() != Action.LEFT_CLICK_BLOCK)) {
            return;
        }
        final Player player = event.getPlayer();
        final Session session = sessions.get(player.getUniqueId());
        if (session == null) {
            return;
        }
        event.setCancelled(true);
        final ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) {
            support.send(player, "input.held-item.empty");
            session.cancelTimeout();
            scheduleTimeout(player, session);
            return;
        }
        if (!sessions.remove(player.getUniqueId(), session)) {
            return;
        }
        session.cancelTimeout();
        support.runComplete(session.request.onComplete(), hand.asOne());
        support.finishReturn(player, session.request.returnTo());
    }

    private void cancelFromChat(Player player, Session session) {
        if (!sessions.remove(player.getUniqueId(), session)) {
            return;
        }
        session.cancelTimeout();
        support.send(player, "input.cancelled");
        support.runCancel(session.request.onCancel());
        support.finishReturn(player, session.request.returnTo());
    }

    private void scheduleTimeout(Player player, Session session) {
        session.timeoutTask = Bukkit.getScheduler().runTaskLater(support.plugin(), () -> {
            if (!sessions.remove(player.getUniqueId(), session)) {
                return;
            }
            support.dropNavigation(player.getUniqueId());
            if (player.isOnline()) {
                support.send(player, "input.timeout");
            }
            support.runCancel(session.request.onCancel());
        }, InputSessionManager.INPUT_TIMEOUT.toSeconds() * TICKS_PER_SECOND);
    }

    private static final class Session {
        final HeldItemInputRequest request;
        @Nullable BukkitTask timeoutTask;

        Session(HeldItemInputRequest request) {
            this.request = request;
        }

        void cancelTimeout() {
            if (timeoutTask != null) {
                timeoutTask.cancel();
                timeoutTask = null;
            }
        }
    }
}
