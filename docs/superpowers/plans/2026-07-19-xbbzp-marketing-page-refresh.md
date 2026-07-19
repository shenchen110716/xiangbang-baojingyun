# 响帮帮官网(xbbzp.html)内容重构 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 `web/public/xbbzp.html` 的文案从"劳务派遣·工地仓储物流"旧定位，换成真实官网 `xbbzp.com` 的"一站式可信灵活用工交易平台"定位与内容，导航从 3-view 扩展为 5-view，视觉风格不变。

**Architecture:** 单文件 hash 路由静态页（不经 Vite 处理，`public/` 原样拷贝），只新增 `<div class="view" id="view-...">` 区块与扩展 `route()` 函数里的 hash 判断，复用现有 CSS 组件类名（`.hero` `.triad` `.feat-grid` `.spotlight` `.badges` `.stats`），不新增 CSS class。

**Tech Stack:** 纯 HTML/CSS/原生 JS（无框架、无构建步骤），验证用 `npm run build`（Vite，确认无报错）+ Playwright（本地渲染截图人工检查）。

## Global Constraints

- 视觉风格不变：沿用现有 `:root` CSS 变量（navy/amber/paper/steel 等）与 `:root[data-theme="dark"]` / `prefers-color-scheme: dark` 两套覆盖，不新增配色。
- 不改 `backend/app.py`、不改 `web/src/views/auth/LoginView.vue`、不新增 Vue 路由/组件 —— 改动完全限定在 `web/public/xbbzp.html` 一个文件内。
- 统计数字用示例数字 + "正式上线前请替换"注释，不编造真实经营数据。
- 响帮帮保经云在全站中保持"旗下产品/二级页面"从属关系，不与首页并列。
- 本计划不包含推送到生产（`git push origin main`）——发布环节需在计划执行完成后单独向用户确认。
- 文件第 583 行是内嵌的 base64 二维码图片（约 35000 字符/一行）。**任何 Edit 操作都不得把这一行内容纳入 old_string/new_string**——涉及该行前后的编辑，只匹配二维码行前后的锚点文本（如 `<div class="qr-wrap">` 开始标签或 `<div class="qr-caption">`），绝不复制/改写这行本身。

---

## 文件结构

只修改一个文件：`web/public/xbbzp.html`（当前 623 行）。改动分三块：

1. **全局导航/页脚/路由**（第 88-110 行 nav、第 592-624 行 footer+script）—— 所有 view 共享，只需改一次。
2. **首页内容**（`#view-home`，第 327-426 行）—— 原地重写文案，结构不变（hero/triad/spotlight/stats）。
3. **新增三个 view**（`#view-capabilities` `#view-solutions` `#view-about`）—— 插入在 `#view-baojingyun`（第 429-546 行，含 access 区块）结束、`</div>`（第 546 行 access section 后、baojingyun 整个 view 的收尾 `</div>`）之后、`<footer>` 之前。

`#view-baojingyun` 本体内容不改（已经准确），"返回响帮帮首页"链接已经是 `href="#/"`，无需改动。

---

### Task 1: 全局导航、页脚、路由扩展 + 首页文案重写

**Files:**
- Modify: `web/public/xbbzp.html`（nav 区块、`#view-home` 区块、footer+script 区块）

**Interfaces:**
- Produces：新的 hash 路由集合 `#/` `#/capabilities` `#/solutions` `#/about` `#/baojingyun`，`route()` 函数据此切换 5 个 view 的 `.active` class（Task 2 新增的 3 个 view 依赖这里定义的路由逻辑才能正确显示/隐藏）。

- [ ] **Step 1: 改写顶部导航**

用 Edit 工具，把：

```html
<div class="nav">
  <div class="shell nav-row">
    <a href="#/" class="brand">
      <span class="brand-mark">响</span>
      <span>响帮帮<span class="brand-sub">XIANGBANGBANG · 灵活用工服务</span></span>
    </a>
    <div class="nav-links">
      <a href="#/">首页</a>
      <a href="#/baojingyun">保经云</a>
      <a href="#/baojingyun#access">登录入口</a>
    </div>
    <a href="#/baojingyun#access" class="nav-cta">进入系统</a>
  </div>
</div>
```

替换为：

```html
<div class="nav">
  <div class="shell nav-row">
    <a href="#/" class="brand">
      <span class="brand-mark">响</span>
      <span>响帮帮<span class="brand-sub">XIANGBANGBANG · 灵活用工服务</span></span>
    </a>
    <div class="nav-links">
      <a href="#/">首页</a>
      <a href="#/capabilities">产品能力</a>
      <a href="#/solutions">解决方案</a>
      <a href="#/about">关于我们</a>
    </div>
    <a href="#/baojingyun#access" class="nav-cta">登录入口</a>
  </div>
</div>
```

- [ ] **Step 2: 改写首页 hero + triad + spotlight + stats 文案**

用 Edit 工具，把 `#view-home` 里的 hero 区块：

```html
      <div>
        <div class="hero-eyebrow">劳务派遣 · 灵活用工服务商</div>
        <h1>选人、派工、参保，<br>一站办完。</h1>
        <p class="lede">响帮帮为工地、仓储、物流一线岗位提供招聘派工与用工保障服务，旗下"保经云"平台把参保、理赔、结算全部搬到线上，平台、企业、一线员工三端数据实时同步。</p>
        <div class="hero-actions">
          <a href="#/baojingyun" class="btn btn-amber">了解响帮帮保经云</a>
          <a href="#/baojingyun#access" class="btn btn-ghost">直接登录系统</a>
        </div>
      </div>
```

替换为：

```html
      <div>
        <div class="hero-eyebrow">一站式可信灵活用工交易平台</div>
        <h1>响帮帮<br>零工界的嘀嘀</h1>
        <p class="lede">人岗智能速配、透明交付、合规结算、证据留存，四件事一站搞定。覆盖制造、建筑、农牧、家政等10+行业。旗下"响帮帮保经云"把参保、理赔、结算全部搬到线上，平台、企业、一线员工三端数据实时同步。</p>
        <div class="hero-actions">
          <a href="#/capabilities" class="btn btn-amber">了解产品能力</a>
          <a href="#/baojingyun#access" class="btn btn-ghost">直接登录系统</a>
        </div>
      </div>
```

再把三条业务线 `.triad`：

```html
        <div class="triad-item">
          <div class="triad-num">01 / DISPATCH</div>
          <h3>招聘派工</h3>
          <p>岗位发布、职业类别定级审核、与实际用工单位的对接台账，一线岗位有据可查。</p>
        </div>
        <div class="triad-item flagship">
          <div class="triad-num">02 / BAOJINGYUN</div>
          <h3>响帮帮保经云 · 旗舰产品</h3>
          <p>群体工伤险与雇主责任险的参保、停保、理赔、结算一体化管理，平台/企业/小程序三端同步。</p>
          <a href="#/baojingyun" class="triad-link">查看产品</a>
        </div>
        <div class="triad-item">
          <div class="triad-num">03 / SETTLEMENT</div>
          <h3>资金结算</h3>
          <p>保费与使用费账本、发票申请审核、业务员佣金核算，账目笔笔可对。</p>
        </div>
```

替换为：

```html
        <div class="triad-item">
          <div class="triad-num">01 / MATCHING</div>
          <h3>人岗智能速配</h3>
          <p>结构化人才画像，AI 智能推荐，招聘派工告别大海捞针。</p>
          <a href="#/capabilities" class="triad-link">了解能力</a>
        </div>
        <div class="triad-item flagship">
          <div class="triad-num">02 / BAOJINGYUN</div>
          <h3>响帮帮保经云 · 旗舰产品</h3>
          <p>群体工伤险与雇主责任险的参保、停保、理赔、结算一体化管理，平台/企业/小程序三端同步。</p>
          <a href="#/baojingyun" class="triad-link">查看产品</a>
        </div>
        <div class="triad-item">
          <div class="triad-num">03 / SETTLEMENT</div>
          <h3>合规结算</h3>
          <p>工资款银行监管专户存管，四流合一，账目笔笔可对，零资金池风险。</p>
          <a href="#/solutions" class="triad-link">查看方案</a>
        </div>
```

再把 spotlight 区块的说明文字（"专为灵活用工人员设计的参保管理云平台"那段）保持不变——它已经准确描述保经云，不需要动。

最后把统计条 `.stats` 里前三个 `.stat` 的数字和标签：

```html
      <div class="stat">
        <div class="stat-num tabular">1,200<span style="color:var(--steel);font-size:18px">+</span></div>
        <div class="stat-label">当前在保一线人员</div>
      </div>
      <div class="stat">
        <div class="stat-num tabular">86</div>
        <div class="stat-label">合作实际用工单位</div>
      </div>
      <div class="stat">
        <div class="stat-num tabular">24h</div>
        <div class="stat-label">理赔材料审核响应</div>
      </div>
```

替换为：

```html
      <div class="stat">
        <div class="stat-num tabular">10<span style="color:var(--steel);font-size:18px">+</span></div>
        <div class="stat-label">覆盖行业</div>
      </div>
      <div class="stat">
        <div class="stat-num tabular">1,200<span style="color:var(--steel);font-size:18px">+</span></div>
        <div class="stat-label">平台劳动者</div>
      </div>
      <div class="stat">
        <div class="stat-num tabular">8,600<span style="color:var(--steel);font-size:18px">万+</span></div>
        <div class="stat-label">累计结算规模</div>
      </div>
```

保留第 4 个 `.stat`（"3 终端数据实时同步"）与下方"示例数据，正式上线前请替换为真实经营数据"注释不变。

- [ ] **Step 3: 改写页脚**

用 Edit 工具，把：

```html
<footer>
  <div class="shell footer-row">
    <div>
      <div class="brand" style="font-size:15px"><span class="brand-mark" style="width:26px;height:26px;font-size:11px">响</span> 响帮帮</div>
      <div class="footer-note" style="margin-top:8px">灵活用工服务 · 保经云由响帮帮技术团队自研</div>
    </div>
    <div class="footer-links">
      <a href="#/">首页</a>
      <a href="#/baojingyun">保经云</a>
      <a href="#/baojingyun#access">登录入口</a>
    </div>
  </div>
</footer>
```

替换为：

```html
<footer>
  <div class="shell footer-row">
    <div>
      <div class="brand" style="font-size:15px"><span class="brand-mark" style="width:26px;height:26px;font-size:11px">响</span> 响帮帮</div>
      <div class="footer-note" style="margin-top:8px">一站式可信灵活用工交易平台 · 响帮帮保经云由响帮帮技术团队自研</div>
      <div class="footer-note" style="margin-top:6px">176 813 17237 · xiangbangbang@126.com · www.xbbzp.com</div>
    </div>
    <div class="footer-links">
      <a href="#/">首页</a>
      <a href="#/capabilities">产品能力</a>
      <a href="#/solutions">解决方案</a>
      <a href="#/about">关于我们</a>
      <a href="#/baojingyun#access">登录入口</a>
    </div>
  </div>
  <div class="shell" style="margin-top:18px">
    <div class="footer-note">豫ICP备19004802号-1 · 豫公网安备 41019702002797号 · © 2026 响帮帮灵活用工平台 xbbzp.com 版权所有</div>
  </div>
</footer>
```

- [ ] **Step 4: 扩展路由脚本支持 5 个 view**

用 Edit 工具，把：

```html
<script>
  function route() {
    var hash = window.location.hash || '#/';
    var isBjy = hash.indexOf('#/baojingyun') === 0;
    document.getElementById('view-home').classList.toggle('active', !isBjy);
    document.getElementById('view-baojingyun').classList.toggle('active', isBjy);
    window.scrollTo(0, hash.indexOf('#access') === -1 && !hash.split('#')[2] ? 0 : window.scrollY);
    if (hash === '#/baojingyun#access') {
      requestAnimationFrame(function () {
        var el = document.getElementById('access');
        if (el) el.scrollIntoView({ behavior: 'smooth', block: 'start' });
      });
    }
  }
  window.addEventListener('hashchange', route);
  route();
</script>
```

替换为：

```html
<script>
  var VIEW_IDS = ['view-home', 'view-capabilities', 'view-solutions', 'view-about', 'view-baojingyun'];
  function currentViewId() {
    var hash = window.location.hash || '#/';
    if (hash.indexOf('#/baojingyun') === 0) return 'view-baojingyun';
    if (hash.indexOf('#/capabilities') === 0) return 'view-capabilities';
    if (hash.indexOf('#/solutions') === 0) return 'view-solutions';
    if (hash.indexOf('#/about') === 0) return 'view-about';
    return 'view-home';
  }
  function route() {
    var hash = window.location.hash || '#/';
    var activeId = currentViewId();
    VIEW_IDS.forEach(function (id) {
      var el = document.getElementById(id);
      if (el) el.classList.toggle('active', id === activeId);
    });
    window.scrollTo(0, hash.indexOf('#access') === -1 && !hash.split('#')[2] ? 0 : window.scrollY);
    if (hash === '#/baojingyun#access') {
      requestAnimationFrame(function () {
        var el = document.getElementById('access');
        if (el) el.scrollIntoView({ behavior: 'smooth', block: 'start' });
      });
    }
  }
  window.addEventListener('hashchange', route);
  route();
</script>
```

- [ ] **Step 5: 手工验证 Task 1**

在 `web` 目录跑一次开发服务器确认静态文件可访问（Vite 会直接代理 `public/`）：

```bash
cd web && npx vite --port 5183 &
sleep 2
curl -s http://127.0.0.1:5183/xbbzp.html | grep -c "产品能力"
curl -s http://127.0.0.1:5183/xbbzp.html | grep -c "一站式可信灵活用工交易平台"
kill %1
```

Expected: 两条 `grep -c` 都输出 `>= 1`（导航链接文案 + hero eyebrow 都已经写入文件）。

- [ ] **Step 6: Commit**

```bash
git add web/public/xbbzp.html
git commit -m "feat(web): refresh xbbzp marketing home page copy and nav to match xbbzp.com positioning"
```

---

### Task 2: 新增"产品能力" "解决方案" "关于我们" 三个 view

**Files:**
- Modify: `web/public/xbbzp.html`（在 `#view-baojingyun` 的 `</div>`(view 收尾) 之后、`<footer>` 之前插入三个新 `<div class="view" id="...">` 区块）

**Interfaces:**
- Consumes：Task 1 定义的 `route()` / `VIEW_IDS`（`view-capabilities` `view-solutions` `view-about` 三个 id 必须与 Task 1 里 `VIEW_IDS` 数组完全一致，否则 `.active` 切换不会命中这三个新 view）。

- [ ] **Step 1: 定位插入锚点**

`#view-baojingyun` 结束的锚点是 access 区块之后紧跟的 `</div>`（对应 baojingyun 整个 `.view` 的收尾标签），再往下是空行、然后是 `<footer>`。用 Edit 工具匹配：

```html
        <div class="qr-caption">微信扫码体验 · 体验版</div>
      </div>
    </div>
  </section>
</div>

<footer>
```

替换为（保留原有 baojingyun 收尾 `</div>` 和空行，在其后插入三个新 view，`<footer>` 移到最后）：

```html
        <div class="qr-caption">微信扫码体验 · 体验版</div>
      </div>
    </div>
  </section>
</div>

<!-- ============ CAPABILITIES ============ -->
<div class="view" id="view-capabilities">
  <section class="hero" style="padding-bottom:120px">
    <div class="shell">
      <div class="hero-eyebrow">PRODUCT CAPABILITIES</div>
      <h1>产品能力</h1>
      <p class="lede">人岗智能速配、透明交付、合规结算、证据留存——响帮帮用这四件事覆盖灵活用工全流程。</p>
    </div>
  </section>

  <section>
    <div class="shell">
      <div class="sec-head">
        <div>
          <div class="eyebrow">我们只解决四件事</div>
          <h2>速配、交付、结算、留证</h2>
        </div>
        <div class="desc">四流合一，透明交付，资金安全，纠纷有据可查。</div>
      </div>
      <div class="feat-grid">
        <div class="feat">
          <div class="feat-tag">人岗智能速配</div>
          <h3>AI 人岗匹配</h3>
          <p>结构化人才画像，AI 智能推荐，让合适的人找到合适的活。</p>
        </div>
        <div class="feat">
          <div class="feat-tag">透明交付</div>
          <h3>全流程留痕</h3>
          <p>GPS 精准打卡，任务过程实时追踪，用工大屏一览无余。</p>
        </div>
        <div class="feat">
          <div class="feat-tag">合规结算</div>
          <h3>银行资金监管</h3>
          <p>工资款银行监管专户存管，个税自动代扣代缴，发票同步生成。</p>
        </div>
        <div class="feat">
          <div class="feat-tag">证据留存</div>
          <h3>全链路司法存证</h3>
          <p>操作日志加密存证，一键导出完整证据包，纠纷有据可查。</p>
        </div>
      </div>
    </div>
  </section>

  <section style="padding-top:0">
    <div class="shell">
      <div class="sec-head">
        <div>
          <div class="eyebrow">四端协同</div>
          <h2>企联端 · 灵工端 · 运管端 · 开放平台</h2>
        </div>
      </div>
      <div class="feat-grid">
        <div class="feat">
          <div class="feat-tag">企联端</div>
          <h3>用工企业</h3>
          <p>任务发布、智能速配、用工监控大屏，一手掌握全部项目。</p>
        </div>
        <div class="feat">
          <div class="feat-tag">灵工端</div>
          <h3>劳动者</h3>
          <p>岗位核验、打卡留痕、当日到账，信用资产越攒越多。</p>
        </div>
        <div class="feat">
          <div class="feat-tag">运管端</div>
          <h3>平台运营</h3>
          <p>经营数据大屏、审核与风控，全平台运行状态实时可见。</p>
        </div>
        <div class="feat">
          <div class="feat-tag">开放平台</div>
          <h3>行业伙伴</h3>
          <p>API 对接人力公司、劳务中介、产业园区，数字化转型的基石。</p>
        </div>
      </div>
    </div>
  </section>

  <section style="padding-top:0">
    <div class="shell">
      <div class="spotlight">
        <div>
          <div class="eyebrow" style="color:var(--amber)">合规结算 · 旗舰产品</div>
          <h2>响帮帮保经云</h2>
          <p>专为灵活用工人员设计的参保管理云平台——岗位定级、参停保、理赔工作台、资金账本，平台管理端、参保单位端、微信小程序三端登录入口。</p>
        </div>
        <a href="#/baojingyun" class="btn btn-amber">查看产品详情</a>
      </div>
    </div>
  </section>
</div>

<!-- ============ SOLUTIONS ============ -->
<div class="view" id="view-solutions">
  <section class="hero" style="padding-bottom:120px">
    <div class="shell">
      <div class="hero-eyebrow">SOLUTIONS</div>
      <h1>解决方案</h1>
      <p class="lede">为每一种灵活用工场景匹配对应方案，覆盖制造、建筑、农牧、家政、商贸服务等 10+ 行业。</p>
    </div>
  </section>

  <section>
    <div class="shell">
      <div class="sec-head">
        <div>
          <div class="eyebrow">按行业</div>
          <h2>覆盖 10+ 行业场景</h2>
        </div>
      </div>
      <div class="feat-grid">
        <div class="feat">
          <div class="feat-tag">制造业</div>
          <h3>产线用工</h3>
          <p>旺季批量招工，产线到岗扫码打卡，工时自动统计。</p>
        </div>
        <div class="feat">
          <div class="feat-tag">建筑业</div>
          <h3>工地用工</h3>
          <p>岗位定级审核，参保理赔线上化，用工台账有据可查。</p>
        </div>
        <div class="feat">
          <div class="feat-tag">农牧业</div>
          <h3>采收用工</h3>
          <p>采摘工一键打卡，重量在线确认，日结算日结清。</p>
        </div>
        <div class="feat">
          <div class="feat-tag">家政业</div>
          <h3>家庭用工</h3>
          <p>岗位核验、信用资产积累，好活主动找上门。</p>
        </div>
        <div class="feat">
          <div class="feat-tag">商贸服务业</div>
          <h3>门店与活动用工</h3>
          <p>餐饮酒店、大型活动会展的临时用工，日结自定义时段两套规则。</p>
        </div>
      </div>
    </div>
  </section>

  <section style="padding-top:0">
    <div class="shell">
      <div class="sec-head">
        <div>
          <div class="eyebrow">客户成功案例</div>
          <h2>来自不同行业的真实客户故事</h2>
        </div>
      </div>
      <div class="feat-grid" style="grid-template-columns:repeat(3,1fr)">
        <div class="feat">
          <div class="feat-tag">制造业</div>
          <h3>某大型制造企业</h3>
          <p>通过响帮帮实现用工全流程数字化，旺季快速批量招工，产线到岗扫码打卡，工时自动统计。</p>
          <div style="display:flex;gap:20px;margin-top:auto;padding-top:14px;border-top:1px dashed var(--line)">
            <div>
              <div class="stat-num tabular" style="font-size:22px">30%</div>
              <div class="stat-label">人力成本降低</div>
            </div>
            <div>
              <div class="stat-num tabular" style="font-size:22px">5倍</div>
              <div class="stat-label">结算效率提升</div>
            </div>
          </div>
        </div>
        <div class="feat">
          <div class="feat-tag">物业管理</div>
          <h3>某连锁物业公司</h3>
          <p>统一纳管全国多城市项目，考勤、工时、薪资线上协同，告别手工对账。</p>
          <div style="display:flex;gap:20px;margin-top:auto;padding-top:14px;border-top:1px dashed var(--line)">
            <div>
              <div class="stat-num tabular" style="font-size:22px">20+</div>
              <div class="stat-label">覆盖城市</div>
            </div>
            <div>
              <div class="stat-num tabular" style="font-size:22px">80%</div>
              <div class="stat-label">对账时间缩短</div>
            </div>
          </div>
        </div>
        <div class="feat">
          <div class="feat-tag">农牧采摘</div>
          <h3>某农业合作社</h3>
          <p>采收季数百采摘工一键打卡，重量在线确认，日结算日结清，再无结算纠纷。</p>
          <div style="display:flex;gap:20px;margin-top:auto;padding-top:14px;border-top:1px dashed var(--line)">
            <div>
              <div class="stat-num tabular" style="font-size:22px">降至0</div>
              <div class="stat-label">结算纠纷</div>
            </div>
            <div>
              <div class="stat-num tabular" style="font-size:22px">3倍</div>
              <div class="stat-label">管理效率提升</div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </section>
</div>

<!-- ============ ABOUT ============ -->
<div class="view" id="view-about">
  <section class="hero" style="padding-bottom:120px">
    <div class="shell">
      <div class="hero-eyebrow">ABOUT US</div>
      <h1>关于我们</h1>
      <p class="lede">响帮帮为每一种灵活用工场景服务——用工方、劳动者、行业伙伴，各有各的价值。</p>
    </div>
  </section>

  <section>
    <div class="shell">
      <div class="badges">
        <div class="badge">
          <div class="badge-eyebrow">如果你是用工方</div>
          <h3>工厂企业 · 农场主 · 家庭个人 · 餐饮酒店 · 工程项目 · 大型活动会展</h3>
          <p class="badge-desc">要人？任务一键发布，平台智能速配。怕失控？用工监控大屏，透明管理。怕税务暴雷？合规结算代付，资金绝对安全。</p>
        </div>
        <div class="badge">
          <div class="badge-eyebrow">如果你是劳动者</div>
          <h3>技术工种 · 零工 · 兼职</h3>
          <p class="badge-desc">找活不被骗，岗位平台核验；干活不扯皮，打卡留痕双方确认；领钱不等待，确认后当日到账。</p>
        </div>
        <div class="badge">
          <div class="badge-eyebrow">如果你是行业伙伴</div>
          <h3>人力公司 · 劳务中介 · 产业园区</h3>
          <p class="badge-desc">我们是你数字化转型的基石。四流合一、透明交付、资金安全，这些能力就是你去拿下大客户的最强背书。</p>
        </div>
      </div>
    </div>
  </section>

  <section style="padding-top:0">
    <div class="shell">
      <div class="spotlight">
        <div>
          <div class="eyebrow" style="color:var(--amber)">联系我们</div>
          <h2>176 813 17237</h2>
          <p>xiangbangbang@126.com · www.xbbzp.com</p>
        </div>
        <a href="#/baojingyun#access" class="btn btn-amber">登录入口</a>
      </div>
    </div>
  </section>
</div>

<footer>
```

- [ ] **Step 2: 手工验证 Task 2**

```bash
cd web && npx vite --port 5183 &
sleep 2
curl -s http://127.0.0.1:5183/xbbzp.html | grep -c "响帮帮为每一种灵活用工场景服务"
curl -s http://127.0.0.1:5183/xbbzp.html | grep -c "某大型制造企业"
curl -s http://127.0.0.1:5183/xbbzp.html | grep -c "view-capabilities"
kill %1
```

Expected: 三条 `grep -c` 都输出 `>= 1`。

- [ ] **Step 3: Commit**

```bash
git add web/public/xbbzp.html
git commit -m "feat(web): add capabilities/solutions/about views to xbbzp marketing page"
```

---

### Task 3: 构建验证 + 五个路由的可视化 QA

**Files:**
- 无新文件；仅运行验证脚本（可放 scratchpad，不进仓库）。

**Interfaces:**
- Consumes：Task 1 + Task 2 完成后的最终 `web/public/xbbzp.html`。

- [ ] **Step 1: 跑 Vite build 确认无报错**

```bash
cd web && npm run build
```

Expected: 命令以退出码 0 结束，输出里包含 `xbbzp.html`（说明 `public/` 下的文件被拷进 `dist/`），无 TypeScript/Vite 报错。

- [ ] **Step 2: 用 Playwright 渲染 5 个路由并截图**

在 scratchpad 目录（不要写进仓库）：

```bash
cd /private/tmp/claude-501/-Users-madisonshen-Desktop-Demo/ac71a9d3-d2a3-483d-9e10-684baa0767d7/scratchpad
cat > qa_xbbzp.mjs << 'EOF'
import { chromium } from 'playwright';
import { createServer } from 'http';
import { readFile } from 'fs/promises';
import path from 'path';

const distDir = process.argv[2];
const server = createServer(async (req, res) => {
  try {
    const filePath = path.join(distDir, req.url === '/' ? 'xbbzp.html' : req.url);
    const body = await readFile(filePath);
    res.end(body);
  } catch {
    res.writeHead(404); res.end('not found');
  }
});
await new Promise((resolve) => server.listen(5184, resolve));

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1280, height: 900 } });
const routes = ['#/', '#/capabilities', '#/solutions', '#/about', '#/baojingyun'];
for (const hash of routes) {
  await page.goto('http://127.0.0.1:5184/xbbzp.html' + hash, { waitUntil: 'load' });
  await page.waitForTimeout(300);
  const name = hash.replace(/[#/]/g, '') || 'home';
  await page.screenshot({ path: `qa_${name}.png`, fullPage: true });
  console.log(name, 'ok');
}
await browser.close();
server.close();
EOF
node qa_xbbzp.mjs /Users/madisonshen/Desktop/Demo/web/dist
```

Expected: 输出 5 行 `<name> ok`，scratchpad 目录下生成 `qa_home.png` `qa_capabilities.png` `qa_solutions.png` `qa_about.png` `qa_baojingyun.png` 五张截图。

- [ ] **Step 3: 人工检查截图**

用 Read 工具逐张打开五张截图，确认：
- 每个页面导航栏都显示"首页/产品能力/解决方案/关于我们"+"登录入口"按钮，当前页在 nav 里有视觉区分（若无高亮也可接受，视觉高亮不在本次范围内）。
- 首页统计条显示"10+ 覆盖行业 / 1,200+ 平台劳动者 / 8,600万+ 累计结算规模"。
- 解决方案页三个案例卡片的两组数字都正确显示（30%/5倍、20+/80%、降至0/3倍）。
- 保经云页（`#/baojingyun`）内容与改动前一致，未被误改。

若发现排版问题（如 5 列 industry feat-grid 在 4 列网格下换行不整齐），记录下来但**不在本任务内修复**——先完成 QA 报告，是否需要额外调整由用户决定。

- [ ] **Step 4: 验证登录页跳转不受影响**

```bash
grep -n "xbbzp.html" web/src/views/auth/LoginView.vue
```

Expected: 两处 `href="/xbbzp.html"` 保持不变（对应"返回官网"链接与顶部 brand 链接），跳转到 `#/`（新首页），无需修改 `LoginView.vue`。

- [ ] **Step 5: 汇报 QA 结果**

不做 git 提交（本任务只验证，不改代码）。向用户汇报：build 是否成功、5 张截图检查结果、有无排版问题、是否可以进入发布环节（build → 确认工作树干净 → 合并 → push 触发 Render 自动部署——这一步仍需用户明确授权才能执行）。

---

## Self-Review Notes

- **Spec coverage**：5-view 结构（首页/产品能力/解决方案/关于我们/保经云）、真实文案（四件事/四端/五行业/三客群/三案例/真实联系方式与备案号）、导航与页脚统一、视觉风格不变、保经云内容不动、发布环节单独确认——spec 里的每一条都能对应到 Task 1/2/3 里的具体步骤。
- **Placeholder scan**：三个新 view 的文案都是根据真实 xbbzp.com 内容改写的完整句子，无 TBD/TODO。
- **Type consistency**：`VIEW_IDS` 数组（Task 1）与 Task 2 新增的三个 `id="view-capabilities"` `id="view-solutions"` `id="view-about"` 完全对应；`route()` 里的 `currentViewId()` 判断顺序（先 baojingyun，再 capabilities/solutions/about，最后落回 home）覆盖了所有 5 个路由，不会有 hash 落不到任何 view 的情况。
