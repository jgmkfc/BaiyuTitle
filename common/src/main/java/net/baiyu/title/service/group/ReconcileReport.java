package net.baiyu.title.service.group;

import java.util.List;

/**
 * 组对账回执（{@link net.baiyu.title.service.GroupService#reconcile} 返回）。
 * <p>有效成员 = member 表 ∪ 权限持有者：缺组称号则补授、
 * 双失（既不在表内也无权限）则补收并卸下装扮。
 *
 * @param grantedTitleIds 本次补授的组称号 id 列表
 * @param revokedTitleIds 本次补收的组称号 id 列表
 */
public record ReconcileReport(List<Integer> grantedTitleIds, List<Integer> revokedTitleIds) {

    /**
     * 紧凑构造器：防御性拷贝。
     */
    public ReconcileReport {
        grantedTitleIds = grantedTitleIds == null ? List.of() : List.copyOf(grantedTitleIds);
        revokedTitleIds = revokedTitleIds == null ? List.of() : List.copyOf(revokedTitleIds);
    }

    /**
     * 本次对账是否有任何变更。
     *
     * @return 有补授或补收返回 true
     */
    public boolean hasChanges() {
        return !grantedTitleIds.isEmpty() || !revokedTitleIds.isEmpty();
    }
}
