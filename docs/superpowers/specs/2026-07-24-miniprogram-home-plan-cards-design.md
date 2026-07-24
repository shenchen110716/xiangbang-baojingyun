# 小程序首页参保方案卡片 + 我的页企业切换优化 Design

## 目标

参考竞品截图（无忧勤·全都行），优化"响帮帮无忧保"小程序端首页与"我的"页，让企业端用户进来就能直接办理参保/增减保，而不必先在几个 tab 间找路径。

## 背景：上一次同类改动为什么被回退

commit `d3b18f7`（7-23）曾经把首页整体替换成纯"方案管理"卡片列表，去掉了统计仪表盘，并把底部导航从 4 个 tab（首页/员工/理赔/我的）压缩成 3 个（方案管理/理赔/我的），"员工"作为独立 tab 被折叠掉。用户反馈后被 `de00a97` 完整回退：仪表盘和"员工"独立 tab 都要保留。

理赔 tab（待理赔/结案中 二态切换 + 内联查看/提交资料）和"我的"页（身份切换 / 企业通讯录 / 联系客服）的改动**没有被回退**，现在已经在线上，且已经符合参考图的样式。`employee-edit.js` 里当时为"去投保"流程加的 `?positionId=` 预锁定支持也还在，只是现在没有入口调用它——这次可以直接复用。

本次设计是与上次不同的折中方案：**不删除仪表盘、不动底部导航结构**，只是压缩仪表盘 + 在首页仪表盘下方新增参保方案卡片区。

## 范围

**这次做**：
1. 首页：仪表盘精简（保留问候语 + 现有 5 个统计卡片 + 余额预警条，去掉快捷办理格和消息列表）+ 新增"参保方案"卡片区（按已审核通过的岗位分组，可直接进入该岗位的参保人员列表办理增/减保，或新增岗位）。
2. "我的"页：身份切换从文字链接改成更醒目的徽标/胶囊样式，纯视觉调整，逻辑不变。

**这次不做**（用户已确认）：
- 实名认证（无后端支持，本轮跳过）。
- 员工签约码（参考图有，我们不做）。
- 底部导航 tab 数量/顺序调整（保持 4 个：首页/员工/理赔/我的）。
- 理赔 tab、我的页其余部分（已经符合参考图，不改）。

## 架构

### 首页 (`pages/home/home.js` / `.wxml`)

**数据加载**：在现有 `Promise.all([dashboard, messages, profile])` 基础上，并行加入两个只读接口：
- `GET /positions`（已有，返回全部岗位，前端过滤 `status === 'approved'`）
- `GET /plans`（已有，企业角色下已经按 `AgentCommission`/自己岗位已用方案 天然限定范围，用于取每个岗位关联方案的名称/保司/计费方式，复用 `employee-edit.js` 里 `planText()` 的同款计算方式）
- `GET /insured`（已有，用于按 `position_id` 分组算出每个岗位当前在保+待生效人数——不新增后端字段，纯前端 `reduce` 分组）

不再请求/渲染 `messages`（原来首页展示的"消息与待办"移除；消息入口保留在"我的"页的"消息与待办"菜单项，未受影响）。

**渲染结构**（自上而下）：
1. hero 问候语（不变）。
2. 5 个统计卡片 `grid-2`（不变，保留原有 `data-url`+`bindtap="go"` 的可点击跳转）。
3. 余额预警条（不变，`wx:if="{{dashboard.balance_alerts.length}}"`）。
4. **新增**「参保方案」区块：
   - 区块标题栏：「参保方案」+ 右侧「+ 新增方案」入口（`bindtap` 跳转 `/pages/position-edit/position-edit`，不带 id，即新增岗位——复用上次已经上线的"新增岗位时可选保司产品"能力）。
   - 卡片列表（`wx:for` 遍历 approved 岗位），每张卡片展示：
     - 岗位名称 + 实际用工单位
     - 职业类别 + 关联方案名称（`保司 · 产品名`，取自 `plans` 里 `plan_id` 匹配项）
     - 在保人数（本页面按 `position_id` 分组算出的计数），样式参考现有 `stat-tappable` 的"数字+右侧箭头"视觉
   - 卡片整体可点击（`bindtap="goPosition" data-id="{{item.id}}"`），跳转 `/pages/employees/employees?position_id={{item.id}}`。
   - 空状态：没有任何已审核通过的岗位时，显示"暂无已定类岗位，请先新增岗位并等待审核"+ 新增入口（同上）。

移除的部分：原来的"快捷办理" `grid-3`（新增员工/批量导入/参停保/岗位管理/保单/资金账户 六个图标）整体删除——批量导入、参停保、岗位管理、保单、资金账户这些入口本身不消失，只是不在首页占地方了（用户仍可从"我的"页的对应菜单项进入，那些菜单项已经存在，未受影响）。"消息与待办"列表整体删除（消息入口同样保留在"我的"页）。

### 参保人员列表 (`pages/employees/employees.js`)

新增一个可选的 `position_id` 查询参数（和现有 `status` 参数是同一种模式）：
- `onLoad(options)`：除了现有的 `options.status`，读取 `options.position_id`，存入 `data.position_id`。
- `applyFilter()`：在现有 `status`/`q` 过滤条件基础上，追加 `(!position_id || item.position_id === Number(position_id))`。
- 页面标题/副标题在有 `position_id` 时换成"该岗位参保人员"字样，并显示一个"查看全部员工"的清除筛选入口（避免用户以为看到的是全量列表）。
- `add()`：有 `position_id` 时导航到 `/pages/employee-edit/employee-edit?positionId={{position_id}}`（复用已有的预锁定支持），否则和现在一样不带参数。

这一步是本设计里**唯一新增的后端无关、纯前端**的联动逻辑；不涉及任何接口改动。

### 我的页 (`pages/profile/profile.js` / `.wxml`)

只改 `profile.wxml` 里身份切换那一行的样式：从
```
<text wx:if="{{linkedAccounts.length}}" class="hero-sub" style="margin-left:14rpx;text-decoration:underline" bindtap="switchIdentity">身份切换 ⇄</text>
```
改成一个带背景色的小胶囊（复用 app.wxss 里已有的 `tag`/`chip` 类或新增一个 `identity-switch` 样式类），视觉上更接近参考图里企业名旁边的"身份切换"标签。`switchIdentity()` 逻辑、`/auth/linked-accounts`、`/auth/switch-account` 完全不动。

## 数据流

```
首页 onShow
  → Promise.all([/dashboard, /positions, /plans, /insured, profile])
  → 过滤 approved 岗位 + 按 position_id 分组算人数 + 关联 plan 信息
  → setData 渲染卡片列表

点击卡片 / "+新增方案"
  → wx.navigateTo 到 employees（带 position_id）或 position-edit（新增）

参保人员列表内点击 "+"
  → wx.navigateTo 到 employee-edit（带 positionId，复用已有预锁定表单）
```

## 测试

小程序端没有自动化测试基础设施（纯手工在开发者工具里验证 + 发布体验版）。验证清单：
- 首页：仪表盘只剩问候语+5卡片+预警条；参保方案卡片按岗位分组正确显示人数、职业类别、方案信息；无已审核岗位时空状态文案正确。
- 点击卡片进入的员工列表标题变化、`position_id` 过滤生效、清除筛选可回到全量列表。
- 从过滤后的列表点"+"新增，跳到 employee-edit 后投保单位/实际用工单位/岗位三级选择被正确预锁定（复用现成逻辑，重点验证没有被上次回退误删）。
- "+新增方案"跳转到 position-edit 后能正常选择保司产品并提交（复用上次会话已经上线并测试过的功能，这里只验证入口链路通）。
- 我的页身份切换胶囊点击后行为不变（有可切换账号时弹出选择，无则提示"暂无可切换的其他企业账号"）。
- 回归：员工/理赔/我的 tab 本身内容不受影响；`/positions`、`/plans`、`/insured`、`/employees` 现有页面行为不变。

## 发布

改完后需要在微信开发者工具里手动上传到体验版，AppID `wx260be14355516a2f`（本次会话无法自动化，需要用户手动操作或提供上传凭据）。
