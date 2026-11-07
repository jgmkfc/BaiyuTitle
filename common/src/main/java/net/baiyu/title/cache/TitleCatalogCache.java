package net.baiyu.title.cache;

import net.baiyu.title.domain.title.Title;
import net.baiyu.title.domain.title.TitleCategory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 全量称号目录内存缓存。
 * <p>装载策略：启动全量装载（含软删行，供历史所有权渲染取名）；
 * 编辑提交后单条写穿；reload 全量重建。
 * <p>读路径纪律：{@link #byId(int)} / {@link #all()} / {@link #visibleOnly()} /
 * {@link #bondsRequiring(int)} 只读 volatile 不可变快照，零锁零拷贝，可在任意
 * 线程调用（不变量 §十 11）；主索引同为整体替换的不可变 Map——若用可变容器
 * clear+重填，replaceAll 期间其他线程会瞬时读到"称号不存在"。
 * <p>写路径（{@link #replaceAll(List)} / {@link #put(Title)}）内部同步，
 * 主索引与视图整体重建后原子替换；写入频率 = 管理员编辑频率，重建开销可忽略。
 * <p>本类不做 Component 预解析（引入 RenderSnapshot 时再加）。
 */
public final class TitleCatalogCache {

    /** 主索引：称号 id → 称号（含软删行，软删行不进任何列表视图）；不可变 Map 整体替换。 */
    private volatile Map<Integer, Title> byId = Map.of();

    /** 排序视图：未软删称号按 id 升序（含隐藏），不可变列表整体替换。 */
    private volatile List<Title> sortedView = List.of();

    /** 可见视图：未软删且 visible=true 的称号按 id 升序，不可变列表整体替换。 */
    private volatile List<Title> visibleView = List.of();

    /** 羁绊反向索引：需求称号 id → 依赖它的羁绊称号 id 集合（仅未软删 BOND）。 */
    private volatile Map<Integer, Set<Integer>> bondIndex = Map.of();

    /**
     * 按 id 查询称号（含软删行；调用方按需过滤 {@link Title#isDeleted()}）。
     *
     * @param titleId 称号 id
     * @return 称号；不存在时为空
     */
    public Optional<Title> byId(int titleId) {
        return Optional.ofNullable(byId.get(titleId));
    }

    /**
     * 全部未软删称号（含隐藏 visible=false），按 id 升序。
     *
     * @return 不可变排序视图
     */
    public List<Title> all() {
        return sortedView;
    }

    /**
     * 全部商店可见称号（未软删且 visible=true），按 id 升序。
     *
     * @return 不可变可见视图
     */
    public List<Title> visibleOnly() {
        return visibleView;
    }

    /**
     * 查询依赖给定称号作为需求的羁绊称号 id 集合（羁绊检测取候选用）。
     *
     * @param titleId 需求称号 id
     * @return 羁绊称号 id 集合；无依赖时为空集
     */
    public Set<Integer> bondsRequiring(int titleId) {
        return bondIndex.getOrDefault(titleId, Set.of());
    }

    /**
     * 缓存中的称号总数（含软删行）。
     *
     * @return 称号数
     */
    public int size() {
        return byId.size();
    }

    /**
     * 全量替换缓存内容并重建全部视图（启动装载 / reload 用）。
     * <p>先在新 Map 中装满再原子替换主索引，替换期间读方要么看到旧快照
     * 要么看到新快照，绝无空窗。
     *
     * @param titles 全量称号列表（含软删行）
     */
    public synchronized void replaceAll(List<Title> titles) {
        Map<Integer, Title> fresh = new HashMap<>();
        for (Title title : titles) {
            fresh.put(title.id(), title);
        }
        this.byId = Map.copyOf(fresh);
        rebuildViews();
    }

    /**
     * 单条写穿：写入或覆盖一条称号并重建视图（编辑事务提交后调用）。
     *
     * @param title 称号（含软删标记的最新状态）
     */
    public synchronized void put(Title title) {
        Map<Integer, Title> merged = new HashMap<>(byId);
        merged.put(title.id(), title);
        this.byId = Map.copyOf(merged);
        rebuildViews();
    }

    /**
     * 从主索引快照重建三个 volatile 视图（调用方持有本类监视器锁）。
     */
    private void rebuildViews() {
        List<Title> sorted = new ArrayList<>(byId.values());
        sorted.removeIf(Title::isDeleted);
        sorted.sort(Comparator.comparingInt(Title::id));

        List<Title> visible = sorted.stream().filter(Title::visible).toList();

        Map<Integer, Set<Integer>> index = new HashMap<>();
        for (Title title : sorted) {
            if (title.category() != TitleCategory.BOND) {
                continue;
            }
            for (Integer requiredId : title.bondRequirements()) {
                index.computeIfAbsent(requiredId, ignored -> new HashSet<>()).add(title.id());
            }
        }
        // 内层集合逐个转不可变，外层再转不可变
        Map<Integer, Set<Integer>> frozen = new HashMap<>();
        index.forEach((requiredId, bondIds) -> frozen.put(requiredId, Set.copyOf(bondIds)));

        this.sortedView = List.copyOf(sorted);
        this.visibleView = List.copyOf(visible);
        this.bondIndex = Map.copyOf(frozen);
    }
}
