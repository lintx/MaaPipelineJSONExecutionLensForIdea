# MaaPipeline JSON Execution Lens for IDEA

## 项目定位

这是一个面向 MaaFramework Pipeline JSON/JSONC 的 IntelliJ IDEA 插件方案。

核心原则：

- 原始 Pipeline JSON/JSONC 是唯一事实来源。
- 不新增 Pipeline DSL，不生成第二份源文件。
- 不通过编译步骤维护另一套流程代码。
- 插件只在内存中建立 Pipeline 语义索引，用于展示、导航、诊断和精确编辑。
- 用户可以在源码视图、流程视图和分栏视图之间切换。
- 流程视图的修改直接写回当前 JSON/JSONC 文档，并尽量保持最小 Diff。

## 当前能力

- 在 IntelliJ IDEA Development Instance 中启动插件。
- 识别 MaaFramework Pipeline JSON/JSONC 文件。
- 建立顶层节点到 JSON PSI 源码范围的索引。
- 提供源码、流程、分栏三种编辑视图。
- 从流程节点跳转到对应的 JSON 定义。
- 直接使用外部 MaaEnd 工程作为调试目标，不复制或修改 MaaEnd 源码。

## 本地开发配置

复制 `local.properties.example` 为 `local.properties`，填写本机 IntelliJ IDEA SDK 路径；需要使用 MaaEnd 作为 Development Instance 目标时，再填写 `maaendPath`。也可以通过 `IDEA_SDK_PATH` 和 `MAAEND_PATH` 环境变量提供路径。

`local.properties`、IDEA 工程状态、Gradle 缓存、Development Instance 数据和构建产物均属于本机文件，不应提交。

## 常用命令

```powershell
.\gradlew.bat test buildPlugin verifyPluginProjectConfiguration verifyPluginStructure --no-daemon
.\gradlew.bat runIde --no-daemon
```

Development Instance 会使用 `maaendPath` 配置打开真实 MaaEnd 工程。

## 重要边界

- 不把 MaaEnd 复制到插件工程。
- 不把插件工程加入 MaaEnd 的构建系统。
- 不直接覆盖生成型 Pipeline 文件。
- 不重新序列化整份 JSON 来完成单字段修改。
- 不自动推断无法从 JSON 确定的业务意图。
- 流程视图可以解释控制流，但不能擅自把节点重构成新的代码结构。
