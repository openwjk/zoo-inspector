# ZooInspector

ZooKeeper 桌面客户端：**毫秒级搜索** + 现代化界面。Java 8 / Swing / FlatLaf。

一个 Maven 工程，`mvn package` 直接产出一个**自包含且可执行**的 jar，不需要外部 `lib/`。

## 没有 Java 环境也能跑

默认产出的 jar 需要系统里有 Java。加一个 profile 就能把运行时一起打进去：

```bash
mvn package -Pbundle-runtime
```

这会把一份 JRE 拷到 `dist/runtime/`（用 JDK 8 构建时约 **107 MB**），`dist/` 就成了
一个**不依赖系统 Java 的绿色目录**：

```
dist/
  zookeeper-dev-ZooInspector.jar
  ZooInspector.bat    Windows 双击启动器
  create-shortcut.bat 生成带图标的桌面快捷方式
  ZooInspector.ico    快捷方式图标
  runtime/            内置 JRE
```

启动器按 **内置 runtime → JAVA_HOME → PATH** 的顺序找 java，所以拷到没装 Java 的机器上
直接跑就行：

```bash
./dist/zookeeper-dev-ZooInspector.jar
```

整个 `dist/` 目录拷走即可，不需要安装任何东西。

指定别的运行时（比如想要更小的、或别的平台）：

```bash
mvn package -Pbundle-runtime -Druntime.src=/path/to/jre
```

> **平台相关**：内置的是构建机所用平台的 JRE（这里是 Windows x64）。
> 要给 Linux / macOS 用，就在对应平台上执行同一条命令，或用 `-Druntime.src` 指向匹配的 JRE。
>
> 想更小：JDK 9+ 可以用 `jlink` 裁一个只含所需模块的运行时（Swing 应用通常 40–60 MB），
> 再用 `-Druntime.src` 指过去。本仓库默认走 JDK 8，没有 jlink，所以直接内置完整 JRE。

## 打包与运行

需要 JDK 8+ 和 Maven 3.6+（已在 Corretto 1.8.0_442 + Maven 3.9.9 上验证）。

```bash
mvn package
```

产物同时落在 `target/` 和 `dist/`。**jar 本身就是可执行文件**：

```bash
./dist/zookeeper-dev-ZooInspector.jar
```

Windows cmd / PowerShell 不认 shebang，用配套的 bat：

```bash
.\zooinspector.bat
```

**想双击启动**用 `dist\ZooInspector.bat`：它就在 jar 旁边，用 `javaw` 静默启动，不留控制台窗口。
整个 `dist/` 拷到别的机器上双击即可，有内置 runtime 时不需要装 Java。

```bash
.\dist\ZooInspector.bat --connect 10.0.0.1:2181/myapp --dark
```

```bash
.\dist\ZooInspector.bat --console
```

`--console` 留在当前控制台里跑，能看到日志与报错；`--help` 自动走控制台。
两个 bat 找 java 的顺序一致：**内置 runtime → JAVA_HOME → PATH**，都支持 `ZOOINSPECTOR_OPTS`。
`dist\ZooInspector.bat` 保持纯 ASCII：带中文的 UTF-8 `.bat` 会被 cmd 按字节错位解析，
`chcp 65001` 也救不回来，所以中文说明只放在本文件里。

### 桌面快捷方式（带图标）

`.bat` 文件本身不能带图标，**只有快捷方式能**。双击 `dist\create-shortcut.bat` 即在桌面
生成 `ZooInspector.lnk`：

```bash
.\dist\create-shortcut.bat
```

```bash
.\dist\create-shortcut.bat "D:\tools"
```

- 有内置 runtime（或 `JAVA_HOME`）时，快捷方式**直接指向 `javaw.exe -jar ...`**，
  连控制台一闪都没有；否则回退到 `ZooInspector.bat`，以最小化方式启动。
- 工作目录设为 jar 所在目录，配置照样落在同级的 `config\`。
- 图标取 `dist\ZooInspector.ico`，与程序窗口/任务栏图标同源（`Icons.appImage`），
  含 16 / 24 / 32 / 48 / 64 / 128 / 256 七个尺寸，全部 32 位带 alpha。
- 路径通过环境变量传给 PowerShell，不拼进 `-Command` 字符串：
  结尾带反斜杠的路径紧挨引号会让 PowerShell 解析失败。

改过图标设计后重新生成 `.ico`（先按「本地验证」备好 classpath）：

```bash
java -cp "target/classes;target/test-classes;$(cat target/cp.txt)" com.zooinspector.dev.IconExport dist/ZooInspector.ico target/shots/icon
```

第二个参数可选，给了就把各尺寸另存一份 PNG，方便肉眼核对。

`java -jar` 方式同样有效：

```bash
java -jar dist/zookeeper-dev-ZooInspector.jar
```

带参数启动：

```bash
./dist/zookeeper-dev-ZooInspector.jar --connect 10.0.0.1:2181,10.0.0.2:2181/myapp --dark
```

自定义 JVM 参数：

```bash
ZOOINSPECTOR_OPTS="-Xmx1g" ./dist/zookeeper-dev-ZooInspector.jar
```

拷到新机器后若提示 Permission denied：

```bash
chmod +x dist/zookeeper-dev-ZooInspector.jar
```

> 自执行的原理：jar 前面拼了一段 shell stub（`src/main/scripts/launch.stub`）。
> ZIP 从文件**尾部**定位中央目录，所以同一个文件 `java -jar` 照样能读，
> Spring Boot 的 executable jar 也是这个做法。`unzip -l` 会提示
> "extra bytes at beginning"，那是 stub，属正常。

## 配置存在哪

默认落在**启动的 jar 同级的 `config/` 目录**，所以整个目录拷到别的机器上，连接配置跟着走：

```
dist/
  zookeeper-dev-ZooInspector.jar
  config/
    profiles.json     连接（地址、超时、认证）
    settings.json     主题、字号、搜索与索引设置
  runtime/            内置 JRE（-Pbundle-runtime 才有）
```

位置优先级：

1. `-Dzooinspector.config.dir=<path>` 显式指定
2. jar（或 classes 目录）同级的 `config/`
3. 该位置不可写时（例如装在 `Program Files` 下）回退 `~/.zooinspector`

首次启用新位置会把 `~/.zooinspector` 里已有的配置**拷过来**，只补缺失的文件、不覆盖，
所以从旧版本升级不会丢连接。设置面板底部会显示当前实际使用的配置目录。

**密码默认不落盘**，只保留在本次运行的内存里；要持久化需在连接对话框显式勾选
"Remember passwords on disk (plain text)"，而且是明文。`config/` 已加进 `.gitignore`。

## 快速搜索

顶部搜索框输入即搜（防抖 80 ms），左侧面板在「树」与「搜索结果」间自动切换。

| 模式 | 含义 |
| --- | --- |
| **Contains** | 全路径大小写不敏感子串匹配（默认） |
| **Name only** | 只匹配最后一段节点名 |
| **Glob** | `*` 与 `?` 通配，`*` 可跨 `/`，例如 `*/config/db` |
| **Regex** | Java 正则 |
| **Exact path** | 完整路径精确匹配 |
| **Data** | 搜索节点 data 内容，命中处给出上下文片段 |

结果按相关性分档：节点名精确 > 名前缀 > 名包含 > 路径/数据包含，同档短路径优先。
上下键导航，`Enter` 展开或定位，`Esc` 清空回到树。

**默认不读取任何节点 data**，只爬路径，对集群压力最小。只有主动选 **Data** 模式
才会拉取内容建索引，并受单节点 64 KB、每节点保留 8 KB 文本、总量预算三重上限约束。

索引连上后自动构建，状态栏显示进度、节点数与耗时，可随时取消。`Ctrl+I` 手动重建；
应用内增删改就地打补丁；对显式 watch 的节点，变更时只重爬那一棵子树再合并进索引。

### 接口聚合（Dubbo 布局）

搜索条上的 **Group interfaces**（默认开启）让结果**只到接口层**：

```
🔍 com.example.trade                    40 interfaces · 7,400 nodes · 26 ms
  ▾ Order0Service  com.example.trade
    120 providers · 60 consumers
      ▾ providers · 120
          10.30.0.1:20880
          application=order-app · version=1.0.0
      ▸ consumers · 60
      ▸ configurators · 0
      ▸ routers · 0
  ▸ Order1Service  com.example.trade
```

- 「接口节点」按子节点识别：直接子节点含 `providers` 或 `consumers` 的那一层。
  不写死根路径，`/dubbo`、`/services`、`/sofa` 等任何根都适用。
- 点开接口才展开分类，点开分类才看实例。`Enter` 或双击 = 展开/收起。
- provider/consumer 节点名是 URL 编码长串，这里解码成 **host:port**，
  第二行给 `application` / `version` / `group`，完整 URL 在 tooltip。
- 归并发生在**扫描过程中、截断之前**——否则实例节点被结果上限截掉后连接口都露不出来。
- 不在任何接口下的命中保持原样，普通路径搜索不受影响。
- 展开的子节点全部来自内存索引，**零网络请求**。关掉开关即回到平铺列表。

## 界面

FlatLaf 深/浅双主题，统一标题栏、圆角控件、25 个自绘 SVG 图标（随主题着色）。

- **左**：节点树（文件夹/叶子/ephemeral 图标，右侧 badge 显示子节点数与数据大小）或搜索结果
- **右**：路径头 + 四个按需加载的 tab
  - **Data** — Text / JSON / Hex / Base64 四视图，JSON 自动美化，数据内查找高亮，
    可编辑并带版本校验保存（冲突明确提示，不静默覆盖）
  - **Stat** — 全部 znode 元数据，时间戳给可读值与原始 epoch，zxid 给十六进制与十进制
  - **ACL** — scheme / id / 权限勾选表格，可增删写回，带 aversion 校验与二次确认
  - **Children** — 可排序可过滤的子节点表格，双击跳转
- **底**：连接状态、服务地址、session id、索引统计与进度

节点操作：右键新建（Persistent / Ephemeral / Sequential 四种）、编辑 data、
递归删除（带子树计数确认）、复制路径/数据、刷新子树、watch 变更通知。

## 快捷键

| 键 | 作用 |
| --- | --- |
| `Ctrl+F` | 聚焦搜索框 |
| `Esc` | 清空搜索，回到树 |
| `Enter` | 展开选中接口 / 定位到树 |
| `Ctrl+I` | 重建搜索索引 |
| `Ctrl+R` | 刷新子树、当前节点与索引 |
| `Ctrl+N` | 新建子节点 |
| `Delete` | 删除选中节点 |
| `Ctrl+S` | 保存节点 data |
| `Ctrl+Shift+F` | 在节点 data 内查找 |
| `Ctrl+Shift+C` | 复制节点路径 |
| `Ctrl+Shift+D` | 切换深/浅色主题 |

## 为什么不卡

常见的 ZooKeeper GUI 把网络请求放在 Swing 的树模型里：`getChildCount()` / `isLeaf()` /
`getChildAt()` 每次被调用都同步发一次 `exists()` / `getChildren()`，而且发生在 EDT 上，
渲染一屏就是几十次串行往返。

这里：

- **`ZkTreeModel`** 实现 `TreeModel`，背后是快照缓存，Swing 的任何询问都只读内存，零网络。
- 展开时异步发一次 `getChildren(path, false, stat)`，子节点列表与 stat 一次拿全。
- 子节点 stat 用**异步 exists 流水线**补齐：请求在同一条连接上排队发出，一次往返带回
  大批结果，回调合并成一批 UI 更新。索引爬取同样用这套流水线（在途窗口 256）。
- 右侧四个 tab 按需加载，选中节点只拉当前 tab 的数据。

### 实测

本地 ZooKeeper 3.4.14，104,202 个节点，`-Xmx512m`：

| 项目 | 结果 |
| --- | --- |
| 路径索引构建 | 13.7 s（约 7.6 k 节点/秒，受服务端单会话处理速率限制） |
| 索引占用堆内存 | 约 30 MB |
| Contains / Name / Exact 查询 | 2.4 ms / 1.4 ms / 0.7 ms |
| Glob / Regex 查询 | 14.8 ms / 10.7 ms |
| 树展开时 EDT 最大停顿 | 43–63 ms |

4,001 节点的小集群：索引 643 ms，查询 < 1 ms。

Dubbo 布局（40 接口 × 120 provider + 60 consumer）：搜包名前缀平铺视图命中 7400 条、
500 条上限一截 provider 实例全丢；聚合后 40 行接口全部展示，26 ms。

## 工程结构

```
pom.xml                          Maven 构建（shade fat jar + antrun 拼自执行 stub）
src/main/java/com/zooinspector/
  ZooInspectorApp.java           入口
  core/                          连接、缓存树模型、索引、搜索、节点操作、配置
  ui/                            主窗口、树、搜索、四个详情 tab、对话框、主题
  util/                          EDT 调度、路径、JSON、Hex
src/main/resources/icons/        25 个 SVG 图标
src/main/scripts/launch.stub     自执行 jar 的 shell 头
src/test/java/com/zooinspector/dev/  本地验证工具，不进 fat jar
zooinspector.bat                 Windows 命令行启动器
dist/                            mvn package 产出的可执行 jar + 双击启动器 ZooInspector.bat
```

依赖：FlatLaf 3.4.1（+ extras / jsvg）、zookeeper 3.4.14 客户端、slf4j-simple、gson 2.10.1。
客户端选 3.4.x 是为了核心操作对 3.4~3.9 服务端都兼容；代价是没有持久递归 watch（3.6+ 才有），
watch 用一次性 watch + 触发后重挂实现。

## 本地验证

`src/test/java/com/zooinspector/dev/` 下的工具只用于开发验证，**不进 fat jar**，
且只连本地临时 ZooKeeper。

```bash
mvn test-compile dependency:build-classpath -Dmdep.outputFile=target/cp.txt -Dmdep.includeScope=test
```

之后 `CP` 取 `target/classes;target/test-classes;` 加上 `target/cp.txt` 的内容
（Windows 用 `;`，Unix 用 `:`）。

| 类 | 作用 |
| --- | --- |
| `DevServer` | 起本地 ZooKeeper（默认 21811，数据在 `target/devzk`） |
| `SeedData` | 造约 4.5 k 个结构真实的节点 |
| `ScaleSeed` | 造十万级节点 |
| `IndexBench` | 无界面基准：索引构建与查询耗时 |
| `OpsCheck` | 22 项写操作与异常路径断言 |
| `DubboProbe` | 造 Dubbo 形状的树，对比平铺与聚合结果 |
| `UiHarness` / `GroupUiShot` / `ToastShot` | 界面端到端 + EDT 卡顿看门狗 + 离屏截图 |
| `IconExport` | 把 `Icons.appImage` 导出成多尺寸 `.ico`，供桌面快捷方式使用 |

**验证时务必加 `-Dzooinspector.config.dir=target/devcfg`**（或 `-Duser.home=target/devhome`），
让配置落到隔离目录，不要覆盖你自己的连接 profile 与设置。
从 `target/classes` 跑时配置默认就落在 `target/classes/config/`，本身已隔离；
但直接跑 `dist/` 里的 jar 会写进 `dist/config/`，那种场合一定要显式指定。

示例（Git Bash）：

```bash
CP="target/classes;target/test-classes;$(cat target/cp.txt)"
java -cp "$CP" com.zooinspector.dev.DevServer 21811 target/devzk &
java -cp "$CP" com.zooinspector.dev.SeedData 127.0.0.1:21811
java -Duser.home=target/devhome -cp "$CP" com.zooinspector.dev.OpsCheck 127.0.0.1:21811
```

## License

Apache License 2.0，见 `LICENSE`。