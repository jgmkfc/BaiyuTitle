package net.baiyu.title.service.trade;

/**
 * 报价标的引用（GUI 选择称号菜单收集的 (所有权 id, 称号 id) 对）。
 * <p>双字段冗余互为校验：服务端以所有权 id 定位实例、以称号 id 复核归属，
 * 防止客户端会话期间所有权变动导致的错拿。
 *
 * @param ownershipId 所有权实例 id
 * @param titleId     称号 id
 */
public record OfferItem(long ownershipId, int titleId) {
}
