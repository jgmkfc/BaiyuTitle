package net.baiyu.title.command.argument;

import dev.jorel.commandapi.arguments.Argument;
import dev.jorel.commandapi.arguments.ArgumentSuggestions;
import dev.jorel.commandapi.arguments.StringArgument;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * 玩家名参数工厂：字符串参数 + 在线玩家名补全。
 *
 * <p>补全只列在线玩家（Bukkit 在线列表，零数据库 IO）；
 * 离线玩家仍可手动输入全名，由命令执行器经本地离线缓存解析。</p>
 */
public final class PlayerNameArgument {

    /**
     * 创建一个带在线玩家补全的玩家名参数。
     *
     * @param nodeName 参数节点名
     * @return CommandAPI 字符串参数
     */
    public Argument<String> create(String nodeName) {
        return new StringArgument(nodeName).replaceSuggestions(
                ArgumentSuggestions.strings(info -> Bukkit.getOnlinePlayers().stream()
                        .map(Player::getName)
                        .toArray(String[]::new)));
    }
}
