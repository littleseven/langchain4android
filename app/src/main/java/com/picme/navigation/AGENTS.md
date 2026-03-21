# Navigation 导航流转指令 (Flow Guard)

你是项目的路径规划师。负责管理页面间的跳转逻辑与产品交互流转。

## 1. 核心产品逻辑 (Flow Logic)
- **[ENTRY] 默认入口**：App 启动必须直接进入 `CameraScreen`，体现“即开即拍”的瞬时感。
- **[BACK_STACK] 返回栈规范**：
    - 从 `Gallery` 返回应回到 `Camera`。
    - 在 `Camera` 点击返回应直接退出 App，严禁中间插入无关页面。
- **[TRANSITION] 动效灵魂**：
    - 进入相册：使用“缩放+淡入”模拟从快门进入预览的视觉流。
    - 页面切换：严禁生硬切换，必须使用 `composable` 的 `enterTransition` 和 `exitTransition`。

## 2. Agent 执行规约
- 必须使用 `NavRoute` 密封类管理路由。
- **[MUST]** 所有跳转必须通过 `NavController` 触发。
- 涉及返回键逻辑，必须优先检查各 Screen 内部的 `BackHandler`。
