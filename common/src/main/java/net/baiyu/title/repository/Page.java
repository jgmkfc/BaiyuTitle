package net.baiyu.title.repository;

import java.util.List;

/**
 * 分页结果值对象（交易历史等分页查询的统一返回类型）。
 * <p>页码从 1 起；{@code items} 构造时防御性拷贝为不可变列表。
 *
 * @param items      当前页条目
 * @param page       当前页码（从 1 起）
 * @param size       每页条数（≥ 1）
 * @param totalItems 总条目数（≥ 0）
 * @param <T>        条目类型
 */
public record Page<T>(List<T> items, int page, int size, int totalItems) {

    /**
     * 紧凑构造器：防御性拷贝与参数校验。
     */
    public Page {
        items = items == null ? List.of() : List.copyOf(items);
        if (page < 1) {
            throw new IllegalArgumentException("页码必须从 1 起: " + page);
        }
        if (size < 1) {
            throw new IllegalArgumentException("每页条数必须 ≥ 1: " + size);
        }
        if (totalItems < 0) {
            throw new IllegalArgumentException("总条目数不能为负: " + totalItems);
        }
    }

    /**
     * 总页数（向上取整；无条目时为 0）。
     *
     * @return 总页数
     */
    public int totalPages() {
        return (totalItems + size - 1) / size;
    }

    /**
     * 是否存在下一页。
     *
     * @return 有下一页返回 true
     */
    public boolean hasNext() {
        return page < totalPages();
    }

    /**
     * 是否存在上一页。
     *
     * @return 有上一页返回 true
     */
    public boolean hasPrevious() {
        return page > 1;
    }
}
