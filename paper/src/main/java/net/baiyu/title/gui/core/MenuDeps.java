package net.baiyu.title.gui.core;

import net.baiyu.title.chat.TabDisplayService;
import net.baiyu.title.config.ConfigHandler;
import net.baiyu.title.gui.render.ItemNameRegistry;
import net.baiyu.title.gui.render.TitleItemRenderer;
import net.baiyu.title.input.InputSessionManager;
import net.baiyu.title.placeholder.PersonalInfoService;
import net.baiyu.title.service.AdminTitleService;
import net.baiyu.title.service.CustomTitleFlow;
import net.baiyu.title.service.PurchaseFlow;
import net.baiyu.title.service.RedeemCodeFlow;
import net.baiyu.title.service.ShopQueryService;
import net.baiyu.title.service.TradeFlow;
import net.baiyu.title.service.TradeViewService;
import net.baiyu.title.service.WardrobeViewService;
import net.baiyu.title.translation.Lang;
import org.bukkit.plugin.Plugin;

import java.util.Objects;

/**
 * 菜单依赖聚合：一次装配、按需取用——
 * 菜单类经 {@link MenuContext} 拿到本 record，避免每加一个服务
 * 就改一遍所有菜单构造签名。
 *
 * <p>后续阶段扩展点：ShopQueryService、InputSessionManager 等
 * 按里程碑追加字段即可，组合根 {@code PluginAssembly} 是唯一的构造处。</p>
 *
 * @param plugin         插件实例（Bukkit 调度器归属；异步 loadData 与回主线程用）
 * @param router         菜单路由（open / replace / back / closeAll）
 * @param sessionManager 会话管理器（每玩家状态）
 * @param lang           语言门面（玩家可见文本唯一入口）
 * @param configHandler  配置处理器（主菜单指令、rarities 等只读取用）
 * @param itemFactory    GUI 物品构造唯一入口
 * @param buttons        通用按钮构造器（边框 / 翻页 / 返回等）
 * @param renderer       动态称号物品渲染门面
 * @param itemNames      物品名本地化注册表（物品价 lore 等取词）
 * @param wardrobeViews  装扮视图门面（装扮 / 选择 / 仓库菜单数据源）
 * @param shopViews      商店查询门面（商店 / 详情菜单数据源）
 * @param purchases      购买执行链（确认菜单点击后的完整购买编排）
 * @param inputs         输入会话管理器（聊天 / 铁砧输入统一入口）
 * @param adminViews     管理端称号编辑门面（管理菜单族数据源与操作入口）
 * @param tradeViews     交易视图门面（交易菜单族数据源）
 * @param tradeFlow      交易执行链（创建 / 同意 / 拒绝的主线程编排）
 * @param redeemCodes    玩家兑换码输入与兑换反馈流程
 * @param customFlow     自定义称号执行链（付款选项预计算 + 创建编排）
 * @param personalInfo   个人信息聊天输出（装扮菜单 C 键落点）
 * @param tabDisplay     Tab 列表称号展示（装扮变更成功后刷新玩家列表名）
 * @param cooldownGate   装扮切换冷却闸门（{@code title.toggles-cool-down} 消费方）
 */
public record MenuDeps(
        Plugin plugin,
        MenuRouter router,
        GuiSessionManager sessionManager,
        Lang lang,
        ConfigHandler configHandler,
        GuiItemFactory itemFactory,
        MenuButtons buttons,
        TitleItemRenderer renderer,
        ItemNameRegistry itemNames,
        WardrobeViewService wardrobeViews,
        ShopQueryService shopViews,
        PurchaseFlow purchases,
        InputSessionManager inputs,
        AdminTitleService adminViews,
        TradeViewService tradeViews,
        TradeFlow tradeFlow,
        RedeemCodeFlow redeemCodes,
        CustomTitleFlow customFlow,
        PersonalInfoService personalInfo,
        TabDisplayService tabDisplay,
        ToggleCooldownGate cooldownGate) {

    /** 紧凑构造：全部依赖必填。 */
    public MenuDeps {
        Objects.requireNonNull(plugin, "插件实例不能为空");
        Objects.requireNonNull(router, "菜单路由不能为空");
        Objects.requireNonNull(sessionManager, "会话管理器不能为空");
        Objects.requireNonNull(lang, "语言门面不能为空");
        Objects.requireNonNull(configHandler, "配置处理器不能为空");
        Objects.requireNonNull(itemFactory, "物品构造工厂不能为空");
        Objects.requireNonNull(buttons, "按钮构造器不能为空");
        Objects.requireNonNull(renderer, "称号物品渲染门面不能为空");
        Objects.requireNonNull(itemNames, "物品名注册表不能为空");
        Objects.requireNonNull(wardrobeViews, "装扮视图门面不能为空");
        Objects.requireNonNull(shopViews, "商店查询门面不能为空");
        Objects.requireNonNull(purchases, "购买执行链不能为空");
        Objects.requireNonNull(inputs, "输入会话管理器不能为空");
        Objects.requireNonNull(adminViews, "管理端编辑门面不能为空");
        Objects.requireNonNull(tradeViews, "交易视图门面不能为空");
        Objects.requireNonNull(tradeFlow, "交易执行链不能为空");
        Objects.requireNonNull(redeemCodes, "兑换码执行链不能为空");
        Objects.requireNonNull(customFlow, "自定义称号执行链不能为空");
        Objects.requireNonNull(personalInfo, "个人信息输出服务不能为空");
        Objects.requireNonNull(tabDisplay, "Tab 展示服务不能为空");
        Objects.requireNonNull(cooldownGate, "切换冷却闸门不能为空");
    }
}
