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
- 按任务入口展开传统逻辑控制流，解释 `next`、`on_error`、重试、循环、共享节点、JumpBack 和动态 Anchor。
- 读取 MaaFramework 日志，关联任务、节点、候选后继、识别、动作和等待静止事件，并显示本次运行结果。
- 查看和编辑任务 `pipeline_override` 的有效字段，支持新增、修改和删除覆盖字段。
- 在流程视图中进行最小范围字段编辑、后继排序、后继增删以及节点增删，所有修改进入 IDEA Undo/Redo。
- 对生成文件、外部修改和过期索引进行保护；写回前显示局部原值/新值预览。
- 新增节点时执行 JSON 语法和可用 MaaEnd Pipeline Schema 校验；Anchor 定义和引用提供独立定位与诊断。
- Anchor 总览列出设置点、映射目标、未定义引用和本次日志观察，支持跨文件定位与定义重命名、删除、绑定字段编辑；多个设置点按运行时覆盖关系解释。
- 检测到编辑期间的外部修改时，使用 IDEA 原生三方 Diff 和合并器审阅基线、当前文档和拟修改结果；合并后重新校验文档版本和 JSON 语法。
- 中间流程使用按视口创建和回收组件的虚拟行，滚动和折叠时保留可见位置；循环、共享节点和静态展开上限仍保留保护。

逻辑视图以中间完整流程为主线，源码视图用于精确编辑和核对原始 JSON/JSONC。运行轨迹是叠加信息，不能替代静态 Pipeline 定义。

选择任务入口后，日志叠加使用该入口最近启动的 `task_id`；缺少关联日志时显示未观察到，不借用其他任务的结果。Anchor 重命名仅修改选中的设置点，不自动改写同名设置点和引用。

当前日志功能依据已有事件展示观察结果，还不能完整重建 JumpBack 调用栈或解释缺失事件。断点调试、真实日志回放以及不同主题下的手工集成验收仍需分别确认；构建通过不代表所有计划验收项已完成。

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
