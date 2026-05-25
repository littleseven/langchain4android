
## TODO: 恢复 MediaPipe GPU 加速

**问题**: 当前 MediaPipe 使用 CPU delegate 运行，人脸检测性能低于 GPU 模式。
**原因**: GPU delegate 在后台线程初始化时导致 SIGBUS (BUS_ADRERR) 崩溃。
**临时方案**: 已将 delegate 从 GPU 降级为 CPU，初始化移至主线程。

**修复方案（待实施）**:
1. 确保 MediaPipe GPU delegate 初始化和推理在同一线程
2. 正确管理 EGL 上下文生命周期（与相机预览共享或独立创建）
3. 配置切换时安全释放/重建 GPU 资源，避免 use-after-free
4. 添加设备兼容性检测，不兼容设备自动回退 CPU

**相关文件**:
- 
- 

**优先级**: P1（性能优化）
**影响**: 人脸检测帧率、功耗、预览流畅度

