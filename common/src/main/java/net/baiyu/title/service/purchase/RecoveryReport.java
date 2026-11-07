package net.baiyu.title.service.purchase;

/**
 * 孤儿订单恢复报告（{@code recoverOrphans()} 启动收敛的量化结果，供控制台告警）。
 *
 * @param reservedRefunded RESERVED 孤儿退款数（必未触碰 Vault，恒退款）
 * @param payingGranted    PAYING 孤儿按 grant 策略履约数
 * @param payingRefunded   PAYING 孤儿按 refund 策略退款数
 * @param markedManual     PAYING 孤儿转 MANUAL 留置数（含履约失败兜底转人工）
 * @param manualPending    既有 MANUAL 留置数（不动，等待管理员裁决）
 */
public record RecoveryReport(
        int reservedRefunded,
        int payingGranted,
        int payingRefunded,
        int markedManual,
        int manualPending) {

    /**
     * 收敛处理的订单总数（不含既有 MANUAL 留置）。
     *
     * @return 处理总数
     */
    public int resolvedCount() {
        return reservedRefunded + payingGranted + payingRefunded + markedManual;
    }

    /**
     * 是否存在需要管理员关注的订单（新转人工或既有留置）。
     *
     * @return 存在返回 true
     */
    public boolean needsAttention() {
        return markedManual > 0 || manualPending > 0;
    }
}
