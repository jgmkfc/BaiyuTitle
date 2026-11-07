# BaiyuTitle

[![Build](https://github.com/jgmkfc/BaiyuTitle/actions/workflows/build.yml/badge.svg?branch=main)](https://github.com/jgmkfc/BaiyuTitle/actions/workflows/build.yml)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/java-21-orange.svg)](https://adoptium.net/)
[![Paper](https://img.shields.io/badge/paper-1.21.11-4ea6ea.svg)](https://papermc.io/)

**Minecraft Paper 服务器的称号插件。** 多称号同时展示、五种购买条件自由组合、玩家间交易、
称号组与羁绊自动授予、兑换码，以及带 19 项编辑器的管理端。

| 项目 | 要求 |
| :--- | :--- |
| 服务端 | Paper 1.21.11 及以上（Purpur 可用） |
| Java | 21 |
| 硬依赖 | 无 |
| 软依赖 | Vault（金币支付）、PlaceholderAPI（变量） |
| 数据库 | SQLite（默认）/ H2 / MySQL / MariaDB |

不支持 Folia。原因见[已知限制](#已知限制)。

## 特性

- **多称号展示**：同一玩家可同时显示多个称号，数量由玩家组决定（默认普通玩家 1 个、VIP 3 个）。
  每个栏位可以是固定称号，也可以从自选池里随机；支持聊天栏前缀与 Tab 列表（不写头顶）。
- **条件组合购买**：免费、金币、称号币、物品、权限五种条件自由组合，支持 AND / OR 两种逻辑。
  多个付款分支同时满足时由玩家选择，而不是由系统按固定顺序取第一个。
- **玩家间交易**：报价、详情、历史记录完整闭环。交易期间不锁定称号，允许多单并存，
  成交时全量复核，先成交者得；报价 72 小时自动过期。
- **称号组与羁绊**：组成员由权限自动同步，入组授予、离组收回；羁绊称号在集齐所需称号后自动授予，
  失去条件不回收。
- **兑换与自定义**：兑换码（含使用次数限制与每玩家限次）、称号卡、自定义称号（四种付款方式任选，
  铁砧界面输入名称，50 码点上限）。
- **管理端**：19 项称号编辑器、玩家仓库查看与收回、购买者列表、全服交易日志。

## 关于开发方式

这个项目的代码由 AI 编程助手完成。写在前面是因为从注释风格和测试密度都能看出来，藏着没有意义。

需要说明的是它不是什么：**不是「描述需求 → 拿到代码」的一次性产物**。实际过程是先写 954 行的业务设计文档
定清边界，再定架构约束（单类 250 行上限、禁止静态单例、领域层不依赖服务端 API），然后分十个阶段推进，
每阶段跑完整构建并做一次评审。

AI 承担的是「按约束把设计翻译成代码」。技术选型、边界判断与验收标准由人定——质量下限来自约束，不来自模型。

## 开始使用

### 安装

1. 把 `BaiYuTitle-paper-*.jar` 放入 `plugins/` 目录
2. 需要经济功能时安装 [Vault](https://github.com/MilkBowl/Vault) 与一个经济插件
3. 启动服务器，插件会在 `plugins/BaiYuTitle/` 下生成 `config.yml`、`database.yml` 与 `lang/`
4. 默认语言为 `en_US`，中文服请把 `config.yml` 的 `language` 改为 `zh_CN`

首次启动会把 `config-version` 从 2 迁移到 3，**升级前请备份 `config.yml`**——迁移会重建文件，
配置值保留但注释与字段顺序会重排。

### 命令

| 命令 | 说明 |
| :--- | :--- |
| `/byt` | 打开装扮菜单 |
| `/byt shop` | 打开商店 |
| `/byt open` | 打开仓库 |
| `/byt trade` | 交易报价列表 |
| `/byt redeem` | 输入兑换码 |
| `/byt group` | 玩家组管理（管理权限） |
| `/byt admin` | 管理菜单（管理权限） |
| `/byt reload` | 重载配置与语言（管理权限） |

### 权限

| 权限 | 默认 | 说明 |
| :--- | :--- | :--- |
| `baiyutitle.use` | 所有玩家 | 基础使用 |
| `baiyutitle.command` | 所有玩家 | 命令入口 |
| `baiyutitle.command.help` | 所有玩家 | 帮助 |
| `baiyutitle.trade.create` | 所有玩家 | 发起交易 |
| `baiyutitle.admin` | OP | 管理与编辑 |
| `baiyutitle.command.coin.others` | OP | 调整他人称号币 |
| `baiyutitle.*` | OP | 以上全部 |

栏位上限由组决定，在 `config.yml` 的 `groups` 段配置：按顺序取第一个玩家拥有的组权限，权限为空的组作为兜底。
组权限（例如默认的 `baiyutitle.group.vip`）由配置定义、启动时注册，不在插件清单里写死。

## 构建

### 环境要求

- JDK 21
- Git（版本号由提交哈希生成）
- Gradle 由 wrapper 提供，无需单独安装

### 编译

```bash
./gradlew build
```

产物在 `paper/build/libs/`。文件名带 `sources` 或 `javadoc` 的不是运行包。

### 测试

```bash
./gradlew :common:test       # 数据层：H2 与 SQLite 各跑一遍完整迁移
./gradlew :paper:test        # 表现层纯函数
./gradlew :paper:runServer   # 起测试服，自动下载 Vault 与 PlaceholderAPI
```

486 个测试，其中数据层 290 个跑的是真迁移而不是 mock 出来的表结构。

## 项目结构

```
api       对外事件与接口，不依赖其他模块
common    领域模型 + 数据访问 + 业务服务，不依赖服务端 API
paper     服务端实现、命令、菜单、监听器
buildSrc  构建期插件：数据库方言迁移合并、版本号生成
```

依赖方向单向：`paper → common → api`。

`common` 不 import 任何 `org.bukkit` 类型，所以领域层的两百多个测试不需要启动服务器即可运行。
业务对象全部经构造器注入，唯一组合根是 `paper` 模块的 `bootstrap/PluginAssembly`，没有静态单例。

## 给开发者

### 事件

`api` 模块提供 6 个事件，其中 2 个可取消：

| 事件 | 可取消 | 时机 |
| :--- | :---: | :--- |
| `TitlePrePurchaseEvent` | 是 | 确认购买后、任何数据写入前 |
| `TradePreAcceptEvent` | 是 | 目标方同意后、任何数据写入前 |
| `TitlePurchasedEvent` | 否 | 订单履约授予后 |
| `TradeCompletedEvent` | 否 | 成交事务提交后 |
| `TitleGrantEvent` | 否 | 任一授予入口提交后 |
| `TitleRevokeEvent` | 否 | 回收提交后 |

### PlaceholderAPI

标识符 `byt`，20 余个变量，包含余额、七类持有数、栏位上限、当前佩戴预览等。
离线玩家查询时数值类变量降级为 0、文本类返回空。

## 已知限制

- **不支持 Folia。** 区域化调度需要重写整个表现层，收益不抵成本。
- **不显示头顶称号。** 只能用记分板队伍前缀实现，会与记分板、前缀、TAB 类插件争抢队伍归属，
  因此只写入 Tab 列表。
- **菜单布局写死在代码里。** 不支持 yml 自定义布局；文案可以改（语言文件），布局不行。
- **业务设计文档与开发记录不随仓库发布。** 仓库只放代码。
- **部分类超过自己定的 250 行上限**，最长的一个 580 行，属于已知遗留。

## 几个实现细节

**售罄靠条件 UPDATE，不靠先查后写。** 扣减库存是 `UPDATE ... WHERE sold_count < cap`，
影响行数为 0 即视为售罄，并发下不会超卖。

**防止重复持有靠索引，不靠应用层判断。** 三列唯一索引加一个可空的 `active_marker` 列——
NULL 不参与唯一约束比较，只有 ACTIVE 状态的行填 `TRUE`。同一玩家对同一称号至多一条有效持有记录，
历史记录不受限制。

**线程边界是硬约束。** 数据库操作只在单写线程与小读池执行，服务端 API 只在主线程调用，
运行时断言线程归属，混用会立刻抛异常。

**聊天路径零数据库查询。** 聊天渲染与 PlaceholderAPI 变量只读内存快照。

## 许可

MIT，见 [LICENSE](LICENSE)。
