package net.baiyu.title.domain.title;

import java.time.LocalDate;
import java.util.Objects;

/**
 * 获取窗口值对象（闭区间，端点含）。
 * <p>只填截止日（{@code start == null}）表示"只有截止日"语义：起始视为当天，
 * 等价于判定时无下界，只比较截止日。日期比较使用服务器本地 {@link LocalDate}。
 *
 * @param start 起始日期（可空；空 = 只有截止日）
 * @param end   截止日期（必填，闭区间端点含当天）
 */
public record AcquireWindow(LocalDate start, LocalDate end) {

    /**
     * 校验不变量：截止日必填（WINDOW ⇒ 至少 end 非空）；起始不得晚于截止。
     */
    public AcquireWindow {
        Objects.requireNonNull(end, "获取窗口截止日期不能为空");
        if (start != null && start.isAfter(end)) {
            throw new IllegalArgumentException("获取窗口起始日期不能晚于截止日期: " + start + " > " + end);
        }
    }

    /**
     * 创建完整窗口。
     *
     * @param start 起始日期（可空）
     * @param end   截止日期
     * @return 窗口值对象
     */
    public static AcquireWindow of(LocalDate start, LocalDate end) {
        return new AcquireWindow(start, end);
    }

    /**
     * 创建"只有截止日"的窗口（起始视为当天）。
     *
     * @param end 截止日期
     * @return 窗口值对象
     */
    public static AcquireWindow deadlineOnly(LocalDate end) {
        return new AcquireWindow(null, end);
    }

    /**
     * 判断给定日期是否落在窗口内（闭区间，端点含）。
     *
     * @param date 待判定日期（通常为服务器本地当天）
     * @return 落在窗口内返回 true
     */
    public boolean contains(LocalDate date) {
        Objects.requireNonNull(date, "待判定日期不能为空");
        return (start == null || !date.isBefore(start)) && !date.isAfter(end);
    }

    /**
     * 是否设置了起始日期。
     *
     * @return 有起始日期返回 true
     */
    public boolean hasStart() {
        return start != null;
    }
}
