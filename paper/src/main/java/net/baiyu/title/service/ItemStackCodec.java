package net.baiyu.title.service;

import org.bukkit.inventory.ItemStack;

import java.util.Base64;
import java.util.Objects;
import java.util.Optional;

/**
 * 物品序列化编解码工具（Paper {@code ItemStack#serializeAsBytes} 的 Base64 封装）。
 *
 * <p>口径与购买链完全一致：{@code ItemCondition#itemBase64}、
 * {@code PurchaseSnapshot#heldItemCounts} 的键、订单 {@code item_data} 快照
 * 均为本编码产物；解码与 {@code ShopQueryService#probeItem} /
 * {@code PurchaseFlow} 的 {@code ItemStack.deserializeBytes(Base64.decode)}
 * 同一条路径——编码入口收敛在此，保证逐字节一致的映射键。</p>
 *
 * <p>线程纪律：编解码触碰 Bukkit 物品 NBT，按纪律仅在主线程调用。</p>
 */
public final class ItemStackCodec {

    private ItemStackCodec() {
    }

    /**
     * 物品 → Base64 序列化串。
     *
     * @param stack 物品（含数量；条件录入方建议先 {@code asOne()} 归一为单件）
     * @return Base64 字符串
     */
    public static String encode(ItemStack stack) {
        Objects.requireNonNull(stack, "物品不能为空");
        return Base64.getEncoder().encodeToString(stack.serializeAsBytes());
    }

    /**
     * Base64 序列化串 → 物品（数据损坏时抛运行时异常；渲染路径请用
     * {@link #tryDecode}）。
     *
     * @param base64 Base64 字符串
     * @return 反序列化物品
     */
    public static ItemStack decode(String base64) {
        Objects.requireNonNull(base64, "Base64 字符串不能为空");
        return ItemStack.deserializeBytes(Base64.getDecoder().decode(base64));
    }

    /**
     * 防御性解码：数据损坏时返回空而非抛异常（菜单渲染兜底）。
     *
     * @param base64 Base64 字符串
     * @return 反序列化物品；失败时为空
     */
    public static Optional<ItemStack> tryDecode(String base64) {
        try {
            return Optional.of(decode(base64));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
