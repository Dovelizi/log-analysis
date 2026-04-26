# UI 回归测试清单（web-revamp · designer 风格改造验收）

> 测试对象：http://localhost:8080/
> 基线快照：10191 行 HTML，13 个 `v-if="currentPage"`，2 个 `createApp` 出现。
> 测试目标：验证 designer 的"手绘复古纸质"风格改造不破坏原有功能，同时视觉达标。

---

## A. 基线静态校验（每次重启后先跑）

| ID | 检查项 | 预期 | 判定 |
|----|--------|------|------|
| A1 | `curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/` | 200 | 阻塞 |
| A2 | `curl -s http://localhost:8080/api/health` | 返回 JSON，`"status":"UP"` 或 200 | 阻塞 |
| A3 | HTML 中 `v-if="currentPage` 出现次数 | ≥ 13（避免模板误删） | 阻塞 |
| A4 | HTML 中 `createApp(` 出现 | ≥ 1 | 阻塞 |
| A5 | HTML 中 `</body>` 和 `</html>` 成对 | 各 1 | 阻塞 |
| A6 | Chrome/Playwright 控制台 error 数 | 0（仅容忍第三方 CDN warning） | 阻塞 |
| A7 | 首屏白屏检测（body 内可见文字 > 0） | 非空 | 阻塞 |

---

## B. Tab 切换回归（13 个页面逐个验收）

每个 Tab：点击 → 截图 → 检查无白屏 → 控制台无 error。

| ID | currentPage | 导航入口 | 关键元素（存在性验证） |
|----|-------------|----------|------------------------|
| B01 | `dashboard` | 首页仪表盘 | 日期选择器、6 个趋势图容器 |
| B02 | `report` | 上报异常 | `reportContainer`、上报趋势图 |
| B03 | `push` | 推送管理 | 推送配置列表、推送日志 |
| B04 | `credentials` | 凭证管理 | 凭证列表、新建按钮 |
| B05 | `topics` | Topic 管理 | Topic 列表、新建按钮 |
| B06 | `queries` | Query Config 列表 | 查询配置列表、新建按钮 |
| B07 | `query-config-edit` | 从 B06 点"编辑"或"新建" | 编辑表单 |
| B08 | `search` | 日志搜索 | 搜索条件表单、结果区 |
| B09 | `logs` | 查询日志 | 查询历史表格 |
| B10 | `permission` | 权限管理 | 权限表格 |
| B11 | `table-mappings` | Table Mapping | 映射列表、新建按钮 |
| B12 | `table-data` | 表数据预览 | 数据表格 |
| B13 | `collection-logs` | 采集日志 | 采集日志表格 |

判定：B01~B13 任一白屏或 Console error 即 **P0**。

---

## C. 关键表单交互（CRUD）

对 5 种业务表单做 新建 / 编辑 / 删除 全路径：

| ID | 表单 | 新建 | 编辑 | 删除 | 弹框关闭 | Toast 提示 |
|----|------|------|------|------|----------|-----------|
| C1 | 凭证（credentials） | ✓ | ✓ | ✓ | ✓ | 成功/失败 |
| C2 | Topic | ✓ | ✓ | ✓ | ✓ | 成功/失败 |
| C3 | Query Config（queries） | ✓ | ✓ | ✓ | ✓ | 成功/失败 |
| C4 | Table Mapping | ✓ | ✓ | ✓ | ✓ | 成功/失败 |
| C5 | Push Config | ✓ | ✓ | ✓ | ✓ | 成功/失败 |

判定：任一操作链路断裂即 **P0**；Toast 不显示但功能正常为 **P1**。

---

## D. 6 个趋势图粒度切换

趋势图 6 个（从 HTML 精确定位）：

| ID | chart-id | 所在 Tab |
|----|----------|----------|
| D1 | `controlHitchTrendChart` | dashboard |
| D2 | `gwHitchTrendChart` | dashboard |
| D3 | `supplierSpTrendChart` | dashboard |
| D4 | `supplierTotalTrendChart` | dashboard |
| D5 | `reportErrorTrendChart` | report |
| D6 | `reportSupplierTrendChart` | report |

每个图表粒度枚举：**5m / 10m / 30m / 1h / 2h / 3h**（注：原需求写的 `1d` 模板中不存在，按实际代码为准）。

测试动作：点 `chart-granularity-settings` 组件 → 切换到每个粒度 → 确认图表重绘且 x 轴刻度变化 → 无 error。

判定：某粒度下图表不重绘或报错即 **P1**。

---

## E. 数据刷新 & 分页

| ID | 位置 | 动作 | 预期 |
|----|------|------|------|
| E1 | dashboard 刷新按钮 | 点 `refreshDashboardData` | 转圈动画 → 数据更新 |
| E2 | dashboard 自动刷新 | 勾选 + 10s 间隔 | 10s 后自动触发请求 |
| E3 | queries 列表分页 | 点下一页 | 表格内容变化 |
| E4 | logs 列表分页 | 点下一页 | 表格内容变化 |
| E5 | credentials 列表分页 | 点下一页 | 表格内容变化 |

判定：刷新按钮无响应 **P1**；自动刷新不触发 **P2**；分页断裂 **P1**。

---

## F. 模态框 & Toast

| ID | 场景 | 预期 |
|----|------|------|
| F1 | 推送日期筛选 modal（`showPushDateModal`） | 点遮罩关闭；点 × 关闭；ESC 不必须 |
| F2 | 任一 CRUD 的新建 modal | 遮罩点击关闭；表单校验错误不关闭 |
| F3 | Toast 成功/失败 | 自动 3s 消失；多个 Toast 堆叠不重叠 |
| F4 | Modal z-index | 不被任何动效/手绘元素遮挡 |

---

## G. 视觉风格验收（designer 声称变化的对照）

等 designer 明确 Phase 1/2/3 的"声称变更项"后，逐项对照：

| ID | 验收项（占位，待 designer 提供 Phase 完成清单后填充） |
|----|-------------------------------------------------------|
| G1 | 整体字体是否切换为手写/衬线纸质风？ |
| G2 | 背景是否有纸质纹理（repeat-bg / noise）？ |
| G3 | 按钮、卡片是否有手绘描边、略微倾斜/歪边效果？ |
| G4 | 动效（hover、过渡）是否不遮挡业务按钮？ |
| G5 | 配色是否偏褐/米黄系，非亮蓝？ |
| G6 | 响应式在 1280/1920 下无错位？ |

判定：任一动效/装饰遮挡操作按钮即 **P0**。

---

## H. 性能红线

| ID | 检查项 | 红线 |
|----|--------|------|
| H1 | `/api/*` 任一接口响应时间 | < 1s（遵守项目长期记忆红线） |
| H2 | 首屏 JS 解析阻塞 | < 3s |
| H3 | Tab 切换无明显卡顿 | 视感 < 300ms |

---

## I. 执行节奏

- Phase 1 完成信号收到 → 执行 A / B / G1-G3
- Phase 2 完成信号收到 → 执行 C / D / G4
- Phase 3 完成信号收到 → 执行 E / F / G5-G6 / H
- 每 Phase 发 progress message 给 main：P0 数、P1 数、通过项数
- 全通过写 `ui-test-report.md`

## J. Bug 分级

- **P0**：阻塞功能（白屏、表单无法提交、Console error、按钮被遮挡）
- **P1**：功能可用但体验破坏（Toast 丢失、图表不刷新、样式严重错位）
- **P2**：非阻塞（文案、边距、可优化项）→ backlog
