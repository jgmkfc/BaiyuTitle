package net.baiyu.title.bootstrap;

import net.baiyu.title.BaiYuTitle;
import net.baiyu.title.cache.PlayerProfileCache;
import net.baiyu.title.cache.TitleCatalogCache;
import net.baiyu.title.chat.ChatDisplayCache;
import net.baiyu.title.chat.ChatDisplayService;
import net.baiyu.title.chat.RarityHoverFactory;
import net.baiyu.title.chat.TabDisplayService;
import net.baiyu.title.config.ConfigHandler;
import net.baiyu.title.placeholder.InternalPlaceholderResolver;
import net.baiyu.title.placeholder.PersonalInfoService;
import net.baiyu.title.translation.Lang;

/**
 * 聊天渲染与 PAPI 解析装配切片（{@link PluginAssembly} 的内部协作者）。
 *
 * <p>集中构造：聊天展示视图门面（纯内存）→ 稀有度 hover 工厂 →
 * 聊天前缀构建服务（每消息构建一次）→ Tab 列表展示服务 → PAPI 变量内部解析器
 * （%byt_*% 全集唯一实现，PAPIExpansion 与个人信息共用）→ 个人信息聊天输出服务。
 * 全路径零 DB IO，只读 volatile 缓存快照。</p>
 *
 * @param chatDisplayCache    聊天展示视图门面（纯内存，PAPI 复用）
 * @param chatDisplayService  聊天称号前缀构建服务（ChatListener 消费）
 * @param tabDisplayService   Tab 列表称号展示服务（登录/装扮变更/收敛时刷新）
 * @param placeholderResolver PAPI 变量内部解析器（PAPIExpansion 与个人信息共用）
 * @param personalInfoService 个人信息聊天输出服务（装扮菜单 C 键消费）
 */
record ChatWiring(
        ChatDisplayCache chatDisplayCache,
        ChatDisplayService chatDisplayService,
        TabDisplayService tabDisplayService,
        InternalPlaceholderResolver placeholderResolver,
        PersonalInfoService personalInfoService) {

    /**
     * 构造整条聊天 / PAPI 链（纯构造无副作用，主线程调用）。
     *
     * @param plugin            插件实例（Tab 展示服务的调度器归属）
     * @param profileCache      玩家档案缓存
     * @param titleCatalogCache 称号目录缓存
     * @param configHandler     配置处理器（展示格式与稀有度列表取值）
     * @param lang              语言门面
     * @return 装配好的聊天 / PAPI 切片
     */
    static ChatWiring wire(BaiYuTitle plugin, PlayerProfileCache profileCache,
                           TitleCatalogCache titleCatalogCache,
                           ConfigHandler configHandler, Lang lang) {
        final ChatDisplayCache chatDisplayCache =
                new ChatDisplayCache(profileCache, titleCatalogCache);
        final RarityHoverFactory rarityHoverFactory = new RarityHoverFactory(
                () -> configHandler.getConfig().rarities, lang);
        final ChatDisplayService chatDisplayService = new ChatDisplayService(
                chatDisplayCache, configHandler::getConfig, rarityHoverFactory);
        final TabDisplayService tabDisplayService = new TabDisplayService(
                plugin, chatDisplayService, configHandler::getConfig);
        final InternalPlaceholderResolver placeholderResolver = new InternalPlaceholderResolver(
                profileCache, titleCatalogCache, chatDisplayCache,
                () -> ChatDisplayService.DisplayFormat.of(configHandler.getConfig()), lang::raw);
        final PersonalInfoService personalInfoService =
                new PersonalInfoService(lang, placeholderResolver);
        return new ChatWiring(chatDisplayCache, chatDisplayService, tabDisplayService,
                placeholderResolver, personalInfoService);
    }
}
