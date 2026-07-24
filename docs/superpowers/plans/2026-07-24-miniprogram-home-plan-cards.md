# 小程序首页参保方案卡片 + 我的页企业切换优化 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Trim the mini-program home dashboard and add a per-position "参保方案" card list below it that jumps straight into scoped 增/减保 actions, plus a small visual tweak to the identity-switch control on 我的.

**Architecture:** Pure front-end change to three existing WeChat Mini Program pages (`pages/home`, `pages/employees`, `pages/profile`). No backend or schema changes — every data source used here (`GET /positions`, `GET /plans`, `GET /insured`, `GET /dashboard`) already exists and is already called from other pages in this mini-program. `pages/employees` gains an optional `position_id` query-string filter (same pattern as its existing `status` filter) so the new home cards can link into a pre-scoped view of the existing employee list; `pages/employee-edit` already accepts `?positionId=` from a previous (reverted) attempt at this feature and needs no changes.

**Tech Stack:** Native WeChat Mini Program (WXML/WXSS/JS, no framework), calling the existing FastAPI backend via the shared `app.request()` helper in `miniprogram/app.js`.

## Global Constraints

- Do NOT touch the bottom tabBar (`miniprogram/app.json`'s `tabBar.list`) — stays at 4 tabs (首页/员工/理赔/我的). This was explicitly rejected once already (see commit `de00a97`).
- Do NOT remove the existing 5 dashboard stat cards, the balance-alert banner, or any existing menu item on 我的 — only the "快捷办理" icon grid and the "消息与待办" list get removed from the home page.
- Do NOT add real-name verification (实名认证) or a 员工签约码 entry — explicitly out of scope per the design doc.
- No backend/schema changes in this plan — every field used here already exists in an existing API response. If a task discovers a field genuinely missing, stop and flag it rather than improvising a backend change.
- This mini-program has no automated test suite. "Tests" in this plan mean: `node -c <file>.js` for syntax validation (catches typos/syntax errors, not runtime behavior) plus an explicit manual-verification checklist to run in the WeChat DevTools simulator before publishing.
- Design doc: `docs/superpowers/specs/2026-07-24-miniprogram-home-plan-cards-design.md`.

---

### Task 1: `pages/employees` accepts an optional `position_id` filter

**Files:**
- Modify: `miniprogram/pages/employees/employees.js`
- Modify: `miniprogram/pages/employees/employees.wxml`

**Interfaces:**
- Consumes: nothing new — `app.request('/insured', ...)` (existing), and each returned item already has a `position_id` field (it's a plain column on `InsuredPerson`, present via `serialize()` on the backend — confirmed in `backend/routers/insured.py:88-101`).
- Produces: `pages/employees/employees` now supports being opened as `/pages/employees/employees?position_id=<id>`, which (a) filters the list to that position only, (b) makes the page's "+" fab open `/pages/employee-edit/employee-edit?positionId=<id>` instead of the unscoped form, (c) shows a "查看全部员工" link to clear the filter. Task 2's home-page cards rely on this exact query-param name and behavior.

- [ ] **Step 1: Add `position_id` to page data and read it in `onLoad`**

In `miniprogram/pages/employees/employees.js`, the current `data` and `onLoad` are:

```js
  data: {
    items: [],
    filtered: [],
    q: '',
    status: '',
    statuses: [{ value: '', label: '全部' }, { value: 'pending', label: '待生效' }, { value: 'active', label: '在保' }, { value: 'stopped', label: '已停保' }],
    loading: false
  },
  onLoad(options) { if (options && options.status) this.setData({ status: options.status }); },
```

Replace with:

```js
  data: {
    items: [],
    filtered: [],
    q: '',
    status: '',
    positionId: 0,
    positionName: '',
    statuses: [{ value: '', label: '全部' }, { value: 'pending', label: '待生效' }, { value: 'active', label: '在保' }, { value: 'stopped', label: '已停保' }],
    loading: false
  },
  onLoad(options) {
    if (options && options.status) this.setData({ status: options.status });
    if (options && options.position_id) this.setData({ positionId: Number(options.position_id) });
  },
```

- [ ] **Step 2: Filter by `positionId` in `applyFilter`, and derive `positionName` for the header**

Current `load()` and `applyFilter()`:

```js
  load() {
    this.setData({ loading: true });
    return app.request('/insured', { silent: true }).then((items) => {
      const mapped = items.map((item) => {
        const pendingEffective = this.isPendingEffective(item);
        // 待生效对外统一成一个筛选值：待审核(pending) + 已通过但未来才生效(active 但 effective_at
        // 还没到)，之前拆成 pending/active-pending 两个同名不同值的筛选项，看着像重复。
        const pendingBucket = pendingEffective || item.status === 'pending';
        return { ...item, initial: String(item.name || '员').slice(0, 1), status_label: pendingBucket ? '待生效' : app.statusText(item.status), status_display: pendingBucket ? 'pending' : item.status, id_masked: this.maskId(item.id_number) };
      });
      this.setData({ items: mapped, loading: false }); this.applyFilter();
    }).catch((error) => { this.setData({ loading: false }); wx.showToast({ title: error.message, icon: 'none' }); });
  },
  maskId(value) { const text = String(value || ''); return text.length > 10 ? `${text.slice(0, 6)}********${text.slice(-4)}` : text; },
  search(e) { this.setData({ q: e.detail.value }); this.applyFilter(); },
  chooseStatus(e) { this.setData({ status: e.currentTarget.dataset.value }); this.applyFilter(); },
  applyFilter() {
    const q = this.data.q.trim().toLowerCase(), status = this.data.status;
    const filtered = this.data.items.filter((item) => (!status || item.status_display === status) && (!q || `${item.name}${item.phone}${item.id_number}${item.position_name}${item.actual_employer_name}`.toLowerCase().includes(q)));
    this.setData({ filtered });
  },
  add() { wx.navigateTo({ url: '/pages/employee-edit/employee-edit' }); },
```

Replace `load()`'s success branch, `applyFilter()`, and `add()` with:

```js
  load() {
    this.setData({ loading: true });
    return app.request('/insured', { silent: true }).then((items) => {
      const mapped = items.map((item) => {
        const pendingEffective = this.isPendingEffective(item);
        // 待生效对外统一成一个筛选值：待审核(pending) + 已通过但未来才生效(active 但 effective_at
        // 还没到)，之前拆成 pending/active-pending 两个同名不同值的筛选项，看着像重复。
        const pendingBucket = pendingEffective || item.status === 'pending';
        return { ...item, initial: String(item.name || '员').slice(0, 1), status_label: pendingBucket ? '待生效' : app.statusText(item.status), status_display: pendingBucket ? 'pending' : item.status, id_masked: this.maskId(item.id_number) };
      });
      // 首页参保方案卡片带 position_id 进来时，标题里显示岗位名——从命中的第一条
      // 记录上取 position_name，不用再单独请求 /positions。
      const positionName = this.data.positionId ? ((mapped.find((item) => item.position_id === this.data.positionId) || {}).position_name || '') : '';
      this.setData({ items: mapped, positionName, loading: false }); this.applyFilter();
    }).catch((error) => { this.setData({ loading: false }); wx.showToast({ title: error.message, icon: 'none' }); });
  },
  maskId(value) { const text = String(value || ''); return text.length > 10 ? `${text.slice(0, 6)}********${text.slice(-4)}` : text; },
  search(e) { this.setData({ q: e.detail.value }); this.applyFilter(); },
  chooseStatus(e) { this.setData({ status: e.currentTarget.dataset.value }); this.applyFilter(); },
  applyFilter() {
    const q = this.data.q.trim().toLowerCase(), status = this.data.status, positionId = this.data.positionId;
    const filtered = this.data.items.filter((item) => (!positionId || item.position_id === positionId) && (!status || item.status_display === status) && (!q || `${item.name}${item.phone}${item.id_number}${item.position_name}${item.actual_employer_name}`.toLowerCase().includes(q)));
    this.setData({ filtered });
  },
  clearPositionFilter() { this.setData({ positionId: 0, positionName: '' }); this.applyFilter(); },
  add() {
    const url = this.data.positionId ? `/pages/employee-edit/employee-edit?positionId=${this.data.positionId}` : '/pages/employee-edit/employee-edit';
    wx.navigateTo({ url });
  },
```

- [ ] **Step 3: Update the WXML header to show the scoped title + clear-filter link**

Current header block in `miniprogram/pages/employees/employees.wxml`:

```html
  <view class="row between">
    <view><view class="title">参保员工</view><view class="subtitle">共 {{items.length}} 人，按单位和岗位管理</view></view>
    <button class="mini-btn" bindtap="importFile">批量导入</button>
  </view>
```

Replace with:

```html
  <view class="row between">
    <view>
      <view class="title">{{positionId?(positionName||'该岗位')+' · 参保员工':'参保员工'}}</view>
      <view class="subtitle">{{positionId?'共 '+filtered.length+' 人':'共 '+items.length+' 人，按单位和岗位管理'}}<text wx:if="{{positionId}}" class="link-btn" style="margin-left:16rpx" bindtap="clearPositionFilter">查看全部员工 →</text></view>
    </view>
    <button class="mini-btn" bindtap="importFile">批量导入</button>
  </view>
```

- [ ] **Step 4: Syntax-check the JS file**

Run: `node -c /Users/madisonshen/Desktop/Demo/miniprogram/pages/employees/employees.js`
Expected: no output, exit code 0.

- [ ] **Step 5: Manual verification in WeChat DevTools**

Open the mini-program project in WeChat DevTools, log in as an enterprise account that has at least one approved position with enrolled people, then in the console/simulator navigate to:
`/pages/employees/employees?position_id=<a real position id from GET /positions>`
Verify: title shows "`<岗位名>` · 参保员工", list only contains people with that `position_id`, "查看全部员工" clears the filter back to the full list, and tapping the "+" fab while filtered opens `employee-edit` with the 投保单位/实际用工单位/岗位 selectors already locked to that position (this reuses `employee-edit.js`'s existing `?positionId=` handling — do not modify `employee-edit.js` in this task).

- [ ] **Step 6: Commit**

```bash
cd /Users/madisonshen/Desktop/Demo
git add miniprogram/pages/employees/employees.js miniprogram/pages/employees/employees.wxml
git commit -m "feat(miniprogram): let 参保员工 list filter by position_id"
```

---

### Task 2: Home page — trim dashboard, add 参保方案 cards

**Files:**
- Modify: `miniprogram/pages/home/home.js`
- Modify: `miniprogram/pages/home/home.wxml`

**Interfaces:**
- Consumes: `GET /positions` (existing, returns `{id, name, status, occupation_class, actual_employer_name, plan_id, plan_name, ...}` per `backend/routers/positions.py`'s `positions()` handler), `GET /plans` (existing, enterprise-scoped, returns `{id, name, insurer, billing_mode, effective_mode, sale_price, ...}`), `GET /insured` (existing, each item has `position_id`), and Task 1's `/pages/employees/employees?position_id=<id>` route.
- Produces: nothing consumed by later tasks (Task 3 is independent).

- [ ] **Step 1: Load positions/plans/insured alongside the existing dashboard data, and compute per-position headcount**

Current `data` and `load()` in `miniprogram/pages/home/home.js`:

```js
  data: {
    loading: true,
    // 小程序必须先打开首页，登录是进入后的授权行为，不允许首次打开就强制
    // 拦截跳登录页——未登录时首页照常渲染，只是展示品牌介绍 + 登录入口，
    // 不请求任何需要鉴权的数据。
    loggedIn: false,
    dashboard: { active_people: 0, pending_people: 0, premium_balance_total: 0, usage_available: 0, usage_recharged: 0, usage_consumed: 0, claims_open: 0, balance_alerts: [] },
    messages: [],
    user: {},
    enterprise: {},
    greeting: '你好',
    today: ''
  },
  onShow() {
    if (!app.globalData.token) {
      this.setData({ loggedIn: false, loading: false });
      return;
    }
    this.setData({ loggedIn: true });
    this.load();
  },
  onPullDownRefresh() {
    if (!this.data.loggedIn) { wx.stopPullDownRefresh(); return; }
    this.load().finally(() => wx.stopPullDownRefresh());
  },
  load() {
    const hour = new Date().getHours();
    const greeting = hour < 6 ? '夜深了' : hour < 12 ? '上午好' : hour < 18 ? '下午好' : '晚上好';
    const today = new Date().toLocaleDateString('zh-CN', { month: 'long', day: 'numeric', weekday: 'long' });
    this.setData({ loading: true, greeting, today });
    return Promise.all([app.request('/dashboard', { silent: true }), app.request('/messages', { silent: true }), app.loadProfile()])
      .then(([dashboard, messages, user]) => {
        const premiumAccounts = dashboard.premium_accounts || [];
        // balance 现在等于可用余额（充值 − 已消耗），与三端口径一致。
        dashboard.premium_balance_total = premiumAccounts.reduce((sum, item) => sum + (item.available != null ? item.available : (item.balance || 0)), 0);
        dashboard.premium_recharged_total = premiumAccounts.reduce((sum, item) => sum + (item.recharged != null ? item.recharged : (item.balance || 0)), 0);
        dashboard.premium_consumed_total = premiumAccounts.reduce((sum, item) => sum + (item.consumed || 0), 0);
        this.setData({ dashboard, messages: messages.slice(0, 3), user, enterprise: app.globalData.enterprise || {}, loading: false });
      })
      .catch((error) => { this.setData({ loading: false }); wx.showToast({ title: error.message, icon: 'none' }); });
  },
  go(e) { wx.navigateTo({ url: e.currentTarget.dataset.url }); },
  openMessage(e) { const path = e.currentTarget.dataset.path; if (path) wx.navigateTo({ url: path }); },
  goLogin() { wx.navigateTo({ url: '/pages/login/login' }); },
  onShareAppMessage() { return app.share('/pages/home/home', 'from=share'); }
});
```

Replace the whole file with:

```js
const app = getApp();

Page({
  data: {
    loading: true,
    // 小程序必须先打开首页，登录是进入后的授权行为，不允许首次打开就强制
    // 拦截跳登录页——未登录时首页照常渲染，只是展示品牌介绍 + 登录入口，
    // 不请求任何需要鉴权的数据。
    loggedIn: false,
    dashboard: { active_people: 0, pending_people: 0, premium_balance_total: 0, usage_available: 0, usage_recharged: 0, usage_consumed: 0, claims_open: 0, balance_alerts: [] },
    user: {},
    enterprise: {},
    greeting: '你好',
    today: '',
    positionCards: []
  },
  onShow() {
    if (!app.globalData.token) {
      this.setData({ loggedIn: false, loading: false });
      return;
    }
    this.setData({ loggedIn: true });
    this.load();
  },
  onPullDownRefresh() {
    if (!this.data.loggedIn) { wx.stopPullDownRefresh(); return; }
    this.load().finally(() => wx.stopPullDownRefresh());
  },
  // 每张卡片对应一个已审核通过（status==='approved'）的岗位——职业类别、关联
  // 方案在审核时已经定好，这里只负责把三份已有接口的数据拼到一起，不新增
  // 后端字段。在保人数按 position_id 对 /insured 全量列表分组统计，只算
  // active/pending（已停保的人不计入"在保方案"的人数展示）。
  buildPositionCards(positions, plans, people) {
    const planById = new Map(plans.map((plan) => [plan.id, plan]));
    const countByPosition = new Map();
    people.forEach((person) => {
      if (person.status !== 'active' && person.status !== 'pending') return;
      countByPosition.set(person.position_id, (countByPosition.get(person.position_id) || 0) + 1);
    });
    return positions
      .filter((position) => position.status === 'approved')
      .map((position) => {
        const plan = position.plan_id ? planById.get(position.plan_id) : null;
        const priceText = plan ? `${plan.insurer} · ${plan.name}` : '尚未关联保司产品';
        return {
          id: position.id,
          name: position.name,
          actual_employer_name: position.actual_employer_name || '',
          occupation_class: position.occupation_class || '待定',
          plan_text: priceText,
          insured_count: countByPosition.get(position.id) || 0
        };
      });
  },
  load() {
    const hour = new Date().getHours();
    const greeting = hour < 6 ? '夜深了' : hour < 12 ? '上午好' : hour < 18 ? '下午好' : '晚上好';
    const today = new Date().toLocaleDateString('zh-CN', { month: 'long', day: 'numeric', weekday: 'long' });
    this.setData({ loading: true, greeting, today });
    return Promise.all([
      app.request('/dashboard', { silent: true }),
      app.loadProfile(),
      app.request('/positions', { silent: true }),
      app.request('/plans', { silent: true }),
      app.request('/insured', { silent: true })
    ])
      .then(([dashboard, user, positions, plans, people]) => {
        const premiumAccounts = dashboard.premium_accounts || [];
        // balance 现在等于可用余额（充值 − 已消耗），与三端口径一致。
        dashboard.premium_balance_total = premiumAccounts.reduce((sum, item) => sum + (item.available != null ? item.available : (item.balance || 0)), 0);
        dashboard.premium_recharged_total = premiumAccounts.reduce((sum, item) => sum + (item.recharged != null ? item.recharged : (item.balance || 0)), 0);
        dashboard.premium_consumed_total = premiumAccounts.reduce((sum, item) => sum + (item.consumed || 0), 0);
        const positionCards = this.buildPositionCards(positions || [], plans || [], people || []);
        this.setData({ dashboard, user, enterprise: app.globalData.enterprise || {}, positionCards, loading: false });
      })
      .catch((error) => { this.setData({ loading: false }); wx.showToast({ title: error.message, icon: 'none' }); });
  },
  go(e) { wx.navigateTo({ url: e.currentTarget.dataset.url }); },
  goPosition(e) { wx.navigateTo({ url: `/pages/employees/employees?position_id=${e.currentTarget.dataset.id}` }); },
  addPosition() { wx.navigateTo({ url: '/pages/position-edit/position-edit' }); },
  goLogin() { wx.navigateTo({ url: '/pages/login/login' }); },
  onShareAppMessage() { return app.share('/pages/home/home', 'from=share'); }
});
```

Note: `messages`/`openMessage` are removed from this page entirely (the 消息与待办 section is being deleted in Step 2) — `pages/profile/profile.js` already independently fetches `/messages` for its own "消息与待办" menu badge, so no message functionality is lost.

- [ ] **Step 2: Rewrite the WXML — trim dashboard, add the 参保方案 card section**

Current `miniprogram/pages/home/home.wxml`:

```html
<view class="page safe-bottom">
  <view wx:if="{{!loggedIn}}" class="hero">
    <view class="hero-title">响帮帮无忧保</view>
    <view class="hero-sub">企业员工保障服务一站式管理平台</view>
  </view>
  <view wx:if="{{!loggedIn}}" class="section">
    <view class="card notice">
      <view class="strong">欢迎使用响帮帮无忧保</view>
      <view class="list-meta">登录后可查看参保员工、理赔进度、资金账户等信息</view>
    </view>
    <button class="primary" style="margin-top:20rpx" bindtap="goLogin">登录 / 切换账号</button>
  </view>

  <block wx:if="{{loggedIn}}">
  <view class="hero">
    <view class="hero-sub">{{today}}</view>
    <view class="hero-title">{{greeting}}，{{user.name||'企业管理员'}} 👋</view>
    <view class="hero-sub">{{enterprise.name||'响帮帮无忧保'}}｜今日业务运行正常</view>
  </view>

  <view class="grid-2 section">
    <view class="stat stat-tappable" data-url="/pages/employees/employees" bindtap="go"><text class="stat-label">参保员工 <text class="stat-go">查看 ›</text></text><text class="stat-value">{{dashboard.active_people||0}}<text class="stat-unit">人</text></text></view>
    <view class="stat stat-tappable" data-url="/pages/employees/employees?status=pending" bindtap="go"><text class="stat-label">待生效 <text class="stat-go">查看 ›</text></text><text class="stat-value">{{dashboard.pending_people||0}}<text class="stat-unit">人</text></text></view>
    <view class="stat stat-tappable" data-url="/pages/billing/billing" bindtap="go"><text class="stat-label">保费可用余额 <text class="stat-go">去充值 ›</text></text><text class="stat-value">¥{{dashboard.premium_balance_total||0}}</text><text class="stat-sub">充值 ¥{{dashboard.premium_recharged_total||0}} · 销售保费 ¥{{dashboard.premium_consumed_total||0}}</text></view>
    <view class="stat stat-tappable" data-url="/pages/billing/billing" bindtap="go"><text class="stat-label">服务费可用余额 <text class="stat-go">去充值 ›</text></text><text class="stat-value">¥{{dashboard.usage_available||0}}</text><text class="stat-sub">充值 ¥{{dashboard.usage_recharged||0}} · 已用 ¥{{dashboard.usage_consumed||0}}</text></view>
    <view class="stat stat-tappable" data-url="/pages/claims/claims" bindtap="go"><text class="stat-label">理赔处理中 <text class="stat-go">查看 ›</text></text><text class="stat-value">{{dashboard.claims_open||0}}<text class="stat-unit">件</text></text></view>
  </view>

  <view wx:if="{{dashboard.balance_alerts.length}}" class="section">
    <view wx:for="{{dashboard.balance_alerts}}" wx:key="account" class="card notice notice-danger" data-url="/pages/billing/billing" bindtap="go">
      <view class="row between"><text class="strong">账户余额预警</text><text class="tag tag-danger">{{item.days_left}} 天</text></view>
      <view class="list-meta">{{item.account==='premium'?'保费账户':'平台使用费账户'}}余额 ¥{{item.balance}}，请及时充值</view>
    </view>
  </view>

  <view class="section">
    <view class="section-head"><text class="section-title">快捷办理</text></view>
    <view class="grid-3">
      <view class="quick" data-url="/pages/employee-edit/employee-edit" bindtap="go"><view class="quick-icon">+</view><view class="quick-text">新增员工</view></view>
      <view class="quick" data-url="/pages/import/import" bindtap="go"><view class="quick-icon">⇧</view><view class="quick-text">批量导入</view></view>
      <view class="quick" data-url="/pages/batches/batches" bindtap="go"><view class="quick-icon">⇄</view><view class="quick-text">参停保</view></view>
      <view class="quick" data-url="/pages/positions/positions" bindtap="go"><view class="quick-icon">岗</view><view class="quick-text">岗位管理</view></view>
      <view class="quick" data-url="/pages/policies/policies" bindtap="go"><view class="quick-icon">保</view><view class="quick-text">保单</view></view>
      <view class="quick" data-url="/pages/billing/billing" bindtap="go"><view class="quick-icon">¥</view><view class="quick-text">资金账户</view></view>
    </view>
  </view>

  <view class="section">
    <view class="section-head"><text class="section-title">消息与待办</text><text class="section-link" data-url="/pages/messages/messages" bindtap="go">全部 →</text></view>
    <view wx:for="{{messages}}" wx:key="id" class="card" data-path="{{item.path}}" bindtap="openMessage">
      <view class="row between"><text class="strong">{{item.title}}</text><text class="tag {{item.type==='danger'?'tag-danger':item.type==='warning'?'tag-warning':'tag-success'}}">{{item.type==='todo'?'待办':'通知'}}</text></view>
      <view class="list-meta">{{item.content}}</view>
    </view>
    <view wx:if="{{!messages.length&&!loading}}" class="card empty">暂无消息</view>
  </view>
  </block>
</view>
```

Replace the `快捷办理` section and `消息与待办` section (everything from `<view class="section">\n    <view class="section-head"><text class="section-title">快捷办理</text></view>` through the closing `</view>` right before `</block>`) with a single new `参保方案` section. The kept portion (hero, `grid-2` stats, balance alerts) is unchanged. Full new file:

```html
<view class="page safe-bottom">
  <view wx:if="{{!loggedIn}}" class="hero">
    <view class="hero-title">响帮帮无忧保</view>
    <view class="hero-sub">企业员工保障服务一站式管理平台</view>
  </view>
  <view wx:if="{{!loggedIn}}" class="section">
    <view class="card notice">
      <view class="strong">欢迎使用响帮帮无忧保</view>
      <view class="list-meta">登录后可查看参保员工、理赔进度、资金账户等信息</view>
    </view>
    <button class="primary" style="margin-top:20rpx" bindtap="goLogin">登录 / 切换账号</button>
  </view>

  <block wx:if="{{loggedIn}}">
  <view class="hero">
    <view class="hero-sub">{{today}}</view>
    <view class="hero-title">{{greeting}}，{{user.name||'企业管理员'}} 👋</view>
    <view class="hero-sub">{{enterprise.name||'响帮帮无忧保'}}｜今日业务运行正常</view>
  </view>

  <view class="grid-2 section">
    <view class="stat stat-tappable" data-url="/pages/employees/employees" bindtap="go"><text class="stat-label">参保员工 <text class="stat-go">查看 ›</text></text><text class="stat-value">{{dashboard.active_people||0}}<text class="stat-unit">人</text></text></view>
    <view class="stat stat-tappable" data-url="/pages/employees/employees?status=pending" bindtap="go"><text class="stat-label">待生效 <text class="stat-go">查看 ›</text></text><text class="stat-value">{{dashboard.pending_people||0}}<text class="stat-unit">人</text></text></view>
    <view class="stat stat-tappable" data-url="/pages/billing/billing" bindtap="go"><text class="stat-label">保费可用余额 <text class="stat-go">去充值 ›</text></text><text class="stat-value">¥{{dashboard.premium_balance_total||0}}</text><text class="stat-sub">充值 ¥{{dashboard.premium_recharged_total||0}} · 销售保费 ¥{{dashboard.premium_consumed_total||0}}</text></view>
    <view class="stat stat-tappable" data-url="/pages/billing/billing" bindtap="go"><text class="stat-label">服务费可用余额 <text class="stat-go">去充值 ›</text></text><text class="stat-value">¥{{dashboard.usage_available||0}}</text><text class="stat-sub">充值 ¥{{dashboard.usage_recharged||0}} · 已用 ¥{{dashboard.usage_consumed||0}}</text></view>
    <view class="stat stat-tappable" data-url="/pages/claims/claims" bindtap="go"><text class="stat-label">理赔处理中 <text class="stat-go">查看 ›</text></text><text class="stat-value">{{dashboard.claims_open||0}}<text class="stat-unit">件</text></text></view>
  </view>

  <view wx:if="{{dashboard.balance_alerts.length}}" class="section">
    <view wx:for="{{dashboard.balance_alerts}}" wx:key="account" class="card notice notice-danger" data-url="/pages/billing/billing" bindtap="go">
      <view class="row between"><text class="strong">账户余额预警</text><text class="tag tag-danger">{{item.days_left}} 天</text></view>
      <view class="list-meta">{{item.account==='premium'?'保费账户':'平台使用费账户'}}余额 ¥{{item.balance}}，请及时充值</view>
    </view>
  </view>

  <view class="section">
    <view class="section-head"><text class="section-title">参保方案</text><text class="section-link" bindtap="addPosition">+ 新增方案</text></view>
    <view wx:for="{{positionCards}}" wx:key="id" class="card" data-id="{{item.id}}" bindtap="goPosition">
      <view class="row between"><text class="list-title">{{item.name}}</text><text class="tag tag-success">在保 {{item.insured_count}} 人</text></view>
      <view class="list-meta">{{item.actual_employer_name||'未关联用工单位'}} · {{item.occupation_class}}</view>
      <view class="row between" style="margin-top:18rpx"><text class="small">{{item.plan_text}}</text><text class="link-btn">增/减保 →</text></view>
    </view>
    <view wx:if="{{!loading&&!positionCards.length}}" class="empty">
      <view class="empty-icon">案</view>
      <view>暂无已审核通过的岗位</view>
      <view class="small">新增岗位并上传视频，等待平台/保司定类后会出现在这里</view>
      <button class="primary" style="margin-top:24rpx" bindtap="addPosition">+ 新增方案</button>
    </view>
  </view>
  </block>
</view>
```

- [ ] **Step 3: Syntax-check the JS file**

Run: `node -c /Users/madisonshen/Desktop/Demo/miniprogram/pages/home/home.js`
Expected: no output, exit code 0.

- [ ] **Step 4: Manual verification in WeChat DevTools**

Log in as an enterprise account with at least one approved position that has enrolled people, open 首页, and verify:
- Dashboard area shows only: hero greeting, the 5 stat cards, and (if applicable) the balance-alert banner. No "快捷办理" icon grid, no "消息与待办" list.
- Below that, a "参保方案" section lists a card per approved position, each showing 岗位名/用工单位/职业类别/关联方案/在保人数.
- Tapping a card navigates to `/pages/employees/employees?position_id=<that id>` (Task 1's filtered view).
- Tapping "+ 新增方案" navigates to `/pages/position-edit/position-edit` (blank form).
- Log in as (or simulate) an enterprise account with zero approved positions and confirm the empty-state card renders instead of a blank section.
- Pull-to-refresh still works and re-loads both the dashboard and the position cards.

- [ ] **Step 5: Commit**

```bash
cd /Users/madisonshen/Desktop/Demo
git add miniprogram/pages/home/home.js miniprogram/pages/home/home.wxml
git commit -m "feat(miniprogram): trim home dashboard, add 参保方案 cards"
```

---

### Task 3: 我的页 identity-switch visual tweak

**Files:**
- Modify: `miniprogram/pages/profile/profile.wxml`

**Interfaces:**
- Consumes: nothing new — `switchIdentity()` and `linkedAccounts` already exist in `pages/profile/profile.js` and are unchanged by this task.
- Produces: nothing consumed elsewhere.

- [ ] **Step 1: Replace the text-link identity switch with a tag-styled pill**

Current line in `miniprogram/pages/profile/profile.wxml`:

```html
        <view class="row"><text class="hero-sub">{{enterprise.name||'当前投保单位'}}</text><text wx:if="{{linkedAccounts.length}}" class="hero-sub" style="margin-left:14rpx;text-decoration:underline" bindtap="switchIdentity">身份切换 ⇄</text></view>
```

Replace with:

```html
        <view class="row"><text class="hero-sub">{{enterprise.name||'当前投保单位'}}</text><text wx:if="{{linkedAccounts.length}}" class="tag" style="margin-left:14rpx;background:rgba(255,255,255,.22);color:#fff" bindtap="switchIdentity">身份切换 ⇄</text></view>
```

This reuses the existing `.tag` pill class (`app.wxss:54`, `border-radius:999rpx;padding:8rpx 16rpx`) with inline overrides for a translucent-white look that reads on the hero's colored background (the same pattern `.tag` already needs on dark heroes — check `pages/profile/profile.wxml`'s existing `<text class="tag">企业用户</text>` a few lines below for the base look this is adapting).

- [ ] **Step 2: Manual verification in WeChat DevTools**

Log in as an enterprise owner (`is_owner: true`) whose phone number has more than one linked enterprise account (or, if no such test account exists in this environment, verify at minimum that: with `linkedAccounts.length === 0` the pill doesn't render at all — matching current `wx:if` behavior — and that the page doesn't error). If a multi-enterprise test account is available, tap the pill and confirm the existing action-sheet + switch flow still works unchanged.

- [ ] **Step 3: Commit**

```bash
cd /Users/madisonshen/Desktop/Demo
git add miniprogram/pages/profile/profile.wxml
git commit -m "style(miniprogram): pill-style identity switch on 我的"
```

---

## After all tasks: publish to 体验版

This mini-program has no automated deploy pipeline (confirmed during design: no CI workflow, no upload script in the repo). Once Tasks 1–3 are committed and manually verified in WeChat DevTools:

1. Open the project in WeChat DevTools with AppID `wx260be14355516a2f`.
2. Use "上传" (Upload) with a version number and the changelog "首页参保方案卡片 + 我的页身份切换样式调整".
3. In WeChat 公众平台 (mp.weixin.qq.com) under 版本管理, set the uploaded version as the 体验版 (trial version).
4. This step requires either the user's WeChat Devtools session/credentials or the user performing it directly — flag this back to the user rather than attempting to automate it.
