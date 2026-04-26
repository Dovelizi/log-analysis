# 全站 UI 残留审计清单（米白纸质印章风格统一化）

> 扫描对象：`src/main/resources/static/index.html`（10840 行）
> 扫描方式：源码静态扫描（grep 精确行号）
> 生成时间：2026-04-26
> 审计员：tester
> 阶段 2 playwright 截图因运行时环境限制（node/npm 缺失）**未能执行**；截图补录在 designer 改造完成后由 designer 自己做回归或要求重新调配环境

---

## 核心发现（读前必看）

**index.html 已有两套纸质化机制**：

1. **全局覆盖规则**（行 1180-1204）：已用 `!important` 把 `text-gray-*` / `bg-gray-700/800/900` / `border-gray-700/800` **重映射为米白/棕褐**。
   → 这意味着 P1（暗字 245 处）和部分 P0-grayN（9 处 `bg-gray-8xx`）**视觉已纸质化**，属于"代码冗余但不影响视觉"。
2. **局部作用域覆盖**（行 1384-1448）：只在 `.query-config-edit` 下覆盖了方括号任意值类 `bg-[#xxxxxx]` / `border-[#xxxxxx]`。

**真正的视觉残留全部集中在方括号任意值类**（Tailwind 无法全局规则覆盖方括号类，除非列举每种 hex），共 **83 处**：
- `bg-[#0d1117]`：**24 处**（P0，暗黑底容器）
- `bg-[#161b22]`：**4 处**（P0，暗黑底次级容器）
- `bg-[#21262d]`：**2 处**（P0，暗黑底按钮）
- `bg-[#30363d]`：**14 处**（P0 hover 暗底，**均为 hover 态**，不是静态残留）
- `border-[#30363d]`：**39 处**（P1，暗边框）

---

## 统计总览

| 优先级 | 类型 | 出现次数 | 视觉是否残留 | 备注 |
|-------|------|---------|-------------|------|
| **P0** | `bg-[#0d1117]` 暗黑底 | 24 | ✅ 残留 | 纸质风格破坏最严重 |
| **P0** | `bg-[#161b22]` 暗次级底 | 4 | ✅ 残留 | 下拉菜单 / 徽章底 |
| **P0** | `bg-[#21262d]` 暗按钮底 | 2 | ✅ 残留 | supplier 子 tab |
| **P0 hover** | `hover:bg-[#30363d]` | 14 | ⚠️ 仅 hover | 鼠标悬浮暗化，违和 |
| **P1** | `border-[#30363d]` | 39 | ✅ 残留 | 暗分隔线 |
| **P1** | `text-gray-1xx/2xx/3xx/4xx/5xx` | 245 | ❌ 已覆盖 | 全局 !important 已改色 |
| **P1** | `bg-gray-700/800/900` | 9 | ❌ 已覆盖 | 全局 !important 已改色 |
| **P1** | `border-gray-700/800` | 7 | ❌ 已覆盖 | 全局 !important 已改色 |
| **P2** | `text-blue-400` 等彩色强调 | 86 | ❌ 已覆盖 | 全局 !important 已重映射 |

**实际需要处理的视觉残留 = 83 处（全部为 `[#hex]` 方括号任意值类）**

---

## 推荐改造策略

### 🏆 方案 A（推荐）：全局追加方括号类覆盖规则（3 行 CSS 搞定 83 处）

在 `<style>` 中（紧随 1204 行 `.border-gray-700...` 规则之后）追加：

```css
/* ========== Tailwind 任意值暗色类全局纸质化 ========== */
.bg-\[\#0d1117\], .bg-\[\#161b22\], .bg-\[\#21262d\] {
    background: var(--paper-cream-deep) !important;
    color: var(--ink-brown) !important;
}
.hover\:bg-\[\#30363d\]:hover {
    background: rgba(139, 90, 60, 0.08) !important;
}
.border-\[\#30363d\] {
    border-color: rgba(139, 90, 60, 0.35) !important;
}
/* <code class="bg-[#0d1117]"> 类薄荷徽章（已在 query-config-edit 内验证） */
code.bg-\[\#0d1117\], code.bg-\[\#161b22\] {
    background: rgba(143, 174, 138, 0.15) !important;
    border: 1px solid rgba(95, 138, 106, 0.35) !important;
    color: var(--mint-sage-deep) !important;
}
```

**优点**：0 template 改动、覆盖 83 处、与 `.query-config-edit` 已验证的风格一致。
**代价**：template 里仍留旧 class 名（但无视觉效果），可后续 designer 需要时再清理。

### 方案 B：逐行替换 template 中的 class 为 `.paper-inset` / `.paper-cream-deep` utility

**优点**：彻底清除"代码污染"
**代价**：改 83 处 template，工作量大 10 倍，且要逐处做视觉回归

**建议**：先按方案 A 上线修复用户反馈的问题，方案 B 作为后续代码整洁度优化。

---

## 按视图分组的精确清单

> 视图行号范围参考：见"附录-视图锚点"

---

### A. page-global（侧边栏 + 偏好下拉菜单，行 1452-1832）

> 共享组件，影响所有 Tab

**P0 残留**（3 处，偏好下拉菜单）：
- **行 1790**: `bg-[#161b22] border border-[#30363d]` — 偏好下拉面板
- **行 1796**: `w-full bg-[#0d1117] border border-[#30363d] text-gray-200` — 偏好输入框 1
- **行 1805**: `w-full bg-[#0d1117] border border-[#30363d] text-gray-200` — 偏好输入框 2

**P1 残留（border）**：
- **行 1812**: `border-t border-[#30363d]` — 偏好菜单分隔线

**入口**：点击侧边栏头像 / 偏好按钮 → 右上浮现 `showPrefsDropdown` 下拉。
**建议**：纳入方案 A 统一覆盖。

---

### B. currentPage='dashboard'（行 1833-2804）

**P0 残留**：
- **行 1910**: `bg-gray-700 rounded-full` — tab 数量徽章（全局 `.bg-gray-700` 覆盖已生效 → ✅ 无需动）
- **行 2002/2005/2008**: `hover:bg-[#30363d]` — control-hitch 排序表头 hover
- **行 2015**: `bg-gray-800` — method_name code 徽章（已覆盖）
- **行 2173/2176/2179**: `hover:bg-[#30363d]` — gw-hitch 排序表头 hover
- **行 2186**: `bg-gray-800` — gw method_name（已覆盖）
- **行 2471**: `p-2 border-b border-[#30363d] bg-[#161b22]` — supplier-sp tab 条（**真残留 P0**）
- **行 2475**: `bg-[#21262d] text-gray-400 hover:bg-[#30363d]` — supplier-sp 非激活 tab（**真残留 P0**）
- **行 2487/2490/2493**: `hover:bg-[#30363d]` — supplier-sp-agg 排序表头 hover
- **行 2500**: `bg-gray-800` — method_name（已覆盖）
- **行 2640**: `p-2 border-b border-[#30363d] bg-[#161b22]` — supplier-total tab 条（**真残留 P0**）
- **行 2644**: `bg-[#21262d] text-gray-400 hover:bg-[#30363d]` — supplier-total 非激活 tab（**真残留 P0**）
- **行 2656/2659/2662**: `hover:bg-[#30363d]` — supplier-total-agg 排序表头 hover
- **行 2669**: `bg-gray-800` — method_name（已覆盖）

**P1 残留（border-[#30363d]）**：1837, 1898, 1987, 2158, 2316, 2466, 2471, 2635, 2640（9 处）

**重点入口**：supplier tab 切换、排序表头悬浮。
**建议**：方案 A 全覆盖；supplier tab 按钮的选中色 `bg-blue-600` 可单独改为 `.paper-tab-active`（见决策点）。

---

### C. currentPage='report'（行 2805-3051）

**P0 残留**：
- **行 2974**: `bg-gray-800` — report-view method_name（已覆盖）

**P1 残留（border-[#30363d]）**：2807, 2940（2 处）

**建议**：方案 A 覆盖足够。

---

### D. currentPage='push'（行 3052-3636）

**P0 残留**：
- **行 3556**: `bg-[#0d1117] p-3 rounded text-xs text-gray-400` — `currentPushLog.response_text` `<pre>` **真残留 P0**

**P1 残留（border-[#30363d]）**：3054, 3065, 3132, 3372, 3430, 3566（6 处）

**入口**：Push Tab → 点击某推送日志 → 弹出 `showPushLogModal`（行 3504）→ 查看推送返回文本。
**建议**：`<pre>` 改为 `.paper-inset` + `font-mono` 代码块；或方案 A 全覆盖。

---

### E. currentPage='credentials'（行 3637-3808）

**P0 残留**：
- **行 3668**: `bg-gray-800` — `secret_id_masked` code 徽章（已覆盖）
- **行 3671**: `bg-gray-800` — `secret_key_masked` code 徽章（已覆盖）

**P1 残留（border-[#30363d]）**：3639（1 处）

**建议**：视觉上已 OK，方案 A 覆盖 border 即可。

---

### F. currentPage='topics'（行 3809-3973）

**P0 残留**：
- **行 3834**: `bg-[#0d1117] px-2 py-1 rounded` — `topic.topic_id` code 徽章 **真残留 P0**

**P1 残留（border-[#30363d]）**：3811（1 处）

**建议**：按 query-config-edit 已验证的 code 徽章风格（薄荷色）统一。

---

### G. currentPage='queries'（行 3974-4159）

**P0 残留**：
- **行 4007**: `bg-[#0d1117] px-2 py-1 rounded max-w-xs block truncate` — `config.query_statement` code 徽章 **真残留 P0**

**P1 残留（border-[#30363d]）**：3976（1 处）

**建议**：同 F。

---

### H. currentPage='query-config-edit'（行 4160-4528）✅ 已清零（复核结果）

**P0 残留**：**0 处**（template 内无 `bg-[#xxxxxx]`）
**P1 残留（border-gray-xxx）**：
- **行 4422**: `border border-gray-700 rounded-lg p-4` — 转换测试结果容器（全局 `.border-gray-700` 覆盖已生效 → ✅）
- **行 4447**: `border-b border-gray-800 last:border-b-0` — 测试结果行分隔（已覆盖 → ✅）
- **行 4463**: `border-t border-gray-700 pt-4` — 底部按钮分隔（已覆盖 → ✅）

**P1 残留（border-[#30363d]）**：4162（scoped 覆盖已生效）

**结论**：✅ 视觉已统一，与用户反馈一致。作为其他视图的参考标杆。

---

### I. currentPage='search'（行 4529-4756）

**P0 残留**：
- **行 4653**: `p-4 bg-[#0d1117] border-b border-[#30363d]` — 处理结果详情面板 **真残留 P0**

**P1 残留（border-[#30363d]）**：4531, 4636, 4653（3 处）

**建议**：`bg-[#0d1117]` 改为 `.paper-inset`；或方案 A 全覆盖。

---

### J. currentPage='logs'（行 4757-4969）

**P0 残留**：
- **行 4782**: `bg-[#0d1117] px-2 py-1 rounded` — `record.topic_id` code 徽章 **真残留 P0**

**P1 残留（border-[#30363d]）**：4759（1 处）

**特殊说明**：本视图还使用了 `.log-viewer`（CSS 行 653，独立深棕皮革底 `#2b1d10` + 米黄字 `#e8d9bf`）— **这是有意设计的复古纸张烧焦/旧皮革风**，与米白纸印章风协调，**建议保留**。

---

### K. currentPage='permission'（行 4970-5227）

**P0 残留**：
- **行 5050**: `bg-[#0d1117] rounded-lg p-4 space-y-2` — 权限面板 1 **真残留 P0**
- **行 5082**: `bg-[#0d1117] rounded-lg p-3 border-l-4` — 权限面板 2 **真残留 P0**
- **行 5105**: `bg-[#0d1117] rounded-lg p-4` — 权限面板 3 **真残留 P0**
- **行 5129**: `bg-[#0d1117] rounded-lg p-4` — 权限面板 4 **真残留 P0**
- **行 5154**: `bg-[#0d1117] rounded-lg p-4 relative` — 权限面板 5 **真残留 P0**
- **行 5174/5178/5182/5186**: `bg-[#0d1117] rounded-lg p-4 text-center` — 权限统计卡 ×4 **真残留 P0**
- **行 5195**: `bg-[#0d1117] rounded-lg p-4 border-l-4` — 权限面板 6 **真残留 P0**

**P1 残留（border-[#30363d]）**：4974, 4996, 5039, 5166（4 处）

**结论**：此视图 `bg-[#0d1117]` 密度最高（10 处），**改造收益最大**。建议所有面板统一改为 `.paper-inset` 或方案 A 全覆盖。

---

### L. currentPage='table-mappings'（行 5228-5302）

**P0 残留**：
- **行 5255**: `bg-[#0d1117] px-2 py-1 rounded text-green-400` — `mapping.table_name` code 徽章 **真残留 P0**

**P1 残留（border-[#30363d]）**：5230（1 处）

**建议**：code 徽章按薄荷风统一。

---

### M. currentPage='table-data'（行 5303-5434）— 🎯 用户本轮点名

**P0 残留**：**0 处**（template 内无 `bg-[#xxxxxx]`）

**P1 残留（border-[#30363d]）**：5305, 5382, 5410（3 处）

**template 视觉情况**：视图本身其实已基本纸质化，但由于 border `[#30363d]` 无全局覆盖，**卡片分隔线在视觉上是暗灰/蓝黑色细线**，与米白纸基调有细微违和。

⚠️ **用户所说"编辑映射配置页有问题"的真正问题区域不在 table-data 视图本身，而在共享的 MappingModal（见下节 Q）**。

---

### N. currentPage='collection-logs'（行 5437-5508）

**P0 残留**：
- **行 5463**: `bg-[#0d1117] px-2 py-1 rounded` — `log.table_name` code 徽章 **真残留 P0**

**P1 残留（border-[#30363d]）**：5439, 5481（2 处）

**建议**：同 F/G。

---

### O. Modal - CredentialModal（行 5509-5535）

**视觉审查**：template 内**无** `bg-[#xxxxxx]`（使用 `.modal` / `.form-input` 等预定义类）→ ✅ 已纸质化

---

### P. Modal - TopicModal（行 5597-5682）

**视觉审查**：template 内**无** `bg-[#xxxxxx]` → ✅ 已纸质化

---

### Q. Modal - MappingModal（行 5744-5865）— 🎯 用户本轮点名"编辑映射配置"

**P0 残留**：
- **行 5800**: `bg-[#0d1117] rounded-lg p-3 mb-2` — **新建时**字段配置容器 **真残留 P0**
- **行 5837**: `bg-[#0d1117] rounded-lg p-3 mb-2` — **编辑时**字段配置容器 **真残留 P0**
- **行 5846**: `bg-[#161b22] px-2 py-1 rounded` — 编辑时字段名 code 徽章 **真残留 P0**

**P1 残留**：
- **行 5848**: `text-sm text-blue-400` — 编辑时字段类型显示（全局 `text-blue-400` 已覆盖为 coral-blush → ✅ 视觉OK但彩色泛滥，建议改为 `var(--ochre-gold)` 赭金）
- **行 5845**: `text-sm text-green-400` — 编辑时字段名包装层（已覆盖 → ✅）

**重要**：这是 **用户本轮反馈的核心问题**！当用户点击 `table-mappings` Tab 的"编辑"按钮 → 弹出此模态框 → 看到的"字段配置"容器仍是**黑底**（`bg-[#0d1117]`），与米白纸质背景强烈违和。

**建议**：将 5800 / 5837 的 `bg-[#0d1117] rounded-lg p-3 mb-2` 替换为 `class="paper-inset p-3 mb-2"`；5846 的 code 徽章改用 mint-sage 统一风格。

---

### R. Modal - CollectModal（行 5930-5987）

**视觉审查**：template 内**无** `bg-[#xxxxxx]` → ✅ 已纸质化

---

### S. Modal - PushConfigModal（行 3315-3503）

**视觉审查**：template 内**无** `bg-[#xxxxxx]` → ✅ 已纸质化

---

### T. Modal - PushLogModal（行 3504-5728 范围）

**P0 残留**：
- **行 3556**: `<pre class="bg-[#0d1117] p-3 rounded text-xs text-gray-400">` — response_text 显示 **真残留 P0**
  （已在 D 节记过）

---

### U. Modal - PushDateModal（复合 19 处嵌入）

**视觉审查**：每视图各嵌了一份 `<div v-if="showPushDateModal">`，template 内**无** `bg-[#xxxxxx]` → ✅ 已纸质化。

---

## 决策点（需 designer 或用户确认）

### D1. logs 页面的 `.log-viewer` 是否保留深棕皮革底？
- **现状**：`.log-viewer { background: #2b1d10; color: #e8d9bf }` — 复古羊皮纸烧焦风，和米白纸主题协调。
- **tester 建议**：✅ 保留。代码 log 的等宽字体+深底在可读性上合理，且主题是"复古纸 + 烧焦皮革手账"的组合，不破坏米白印章风。
- **需用户确认**：保留 / 改米白底。

### D2. supplier tab 的选中色 `bg-blue-600`（行 2475, 2644）如何处理？
- **现状**：未激活 tab 是 `bg-[#21262d]`（暗黑），激活 tab 是 `bg-blue-600`（亮蓝）。
- **tester 建议**：整体改为"落款印章"风 —— 未激活 = `var(--paper-cream)` 带虚线边；激活 = `var(--stamp-rust)` 带棕红印章色。
- **需 designer 决定**：是否新增 `.paper-tab` / `.paper-tab-active` 工具类，全局复用。

### D3. `<code>` 代码块统一薄荷徽章（已决定 → 继续）
- 在 `.query-config-edit` 已验证的风格（`background: rgba(143, 174, 138, 0.15); color: var(--mint-sage-deep)`）建议**提升到全局 `code` 或 `.badge-code` 工具类**，覆盖全站 17 处 code 徽章（3834、4007、4782、5255、5463、5846 等）。

### D4. text-blue-400 / text-green-400 / text-purple-400 全局 !important 覆盖是否够用？
- **现状**：行 1187-1193 已把所有彩色 400/500 级重映射到印章暖色（coral-blush / mint-sage / ochre-gold / lotus-pink）。
- **问题**：86 处彩色强调虽视觉已改色，但语义上仍"花"。
- **tester 建议**：保留现有覆盖作为兜底；单独对**重复高频的 text-blue-400**（86 处里占多数）考虑分语义处理（icon / 链接 / 状态灯各一色），但此为"锦上添花"优化，非本轮必要。

---

## 工作量估算

| 方案 | 改动范围 | 预估耗时 | 风险 |
|-----|---------|---------|------|
| **方案 A**（全局追加 CSS 覆盖方括号类） | 15 行 CSS | 15 分钟 | 低（与已有 scoped 规则同构） |
| 方案 A + MappingModal template 细调 | + 3 行 template 替换 | +10 分钟 | 低 |
| 方案 B（彻底重写 template） | 83 处 template | 2-3 小时 | 中（需逐处视觉回归） |

**tester 强烈建议：方案 A + MappingModal 细调（~25 分钟可交付）**。

---

## 交付给 designer 的操作清单（按优先级）

### 🔴 P0 必改（用户本轮核心反馈）
1. 修复 **MappingModal 字段配置区**：行 5800 + 行 5837 的 `bg-[#0d1117]` → `.paper-inset`；行 5846 code 徽章改薄荷色
2. 修复 **permission 页面 10 处黑底面板**（5050, 5082, 5105, 5129, 5154, 5174, 5178, 5182, 5186, 5195）

### 🟠 P0 批量修（方案 A）
3. 在 `<style>` 第 1204 行后追加**全局方括号类覆盖规则**（见"方案 A"CSS 片段），一次性解决剩余 83 处 `[#xxxxxx]` 任意值类

### 🟡 P1 统一体验
4. `<code>` 代码徽章工具类提升到全局（D3）
5. supplier tab 样式印章化（D2，可选）

### ⚪ 复核
6. 改完后跑一次 playwright 截图回归（需先解决 node/npm 环境问题）

---

## 附录 - 视图行号锚点

| 视图 | currentPage | 起始行 | 结束行 |
|------|-------------|-------|-------|
| Dashboard | `dashboard` | 1833 | 2804 |
| Report | `report` | 2805 | 3051 |
| Push | `push` | 3052 | 3636 |
| Credentials | `credentials` | 3637 | 3808 |
| Topics | `topics` | 3809 | 3973 |
| Queries | `queries` | 3974 | 4159 |
| Query Config Edit | `query-config-edit` | 4160 | 4528 |
| Search | `search` | 4529 | 4756 |
| Logs | `logs` | 4757 | 4969 |
| Permission | `permission` | 4970 | 5227 |
| Table Mappings | `table-mappings` | 5228 | 5302 |
| Table Data | `table-data` | 5303 | 5434 |
| Collection Logs | `collection-logs` | 5437 | 5508 |
| CredentialModal | - | 5509 | 5535 |
| TopicModal | - | 5597 | 5682 |
| **MappingModal** 🎯 | - | **5744** | **5865** |
| CollectModal | - | 5930 | 5987 |
| PushConfigModal | - | 3315 | 3503 |
| PushLogModal | - | 3504 | ~3600 |

**纸质化标杆规则位置**（1180-1204 全局、1368-1448 query-config-edit scoped、1373-1382 `.paper-inset` 工具类）

---

## 附录 - 扫描原始文件
- `/tmp/ui-audit/p0-dark-bg.txt` — 44 处暗底原始行（含 5 处备注性文字）
- `/tmp/ui-audit/p0-grayN.txt` — 9 处 `bg-gray-7/8/9xx` 原始行（全部已覆盖）
- `/tmp/ui-audit/p1-dark-border.txt` — 46 处暗边框原始行
- `/tmp/ui-audit/p1-dark-text.txt` — 245 处暗字原始行（全部已覆盖）
- `/tmp/ui-audit/p2-modern-accent.txt` — 86 处彩色强调原始行（全部已覆盖）
