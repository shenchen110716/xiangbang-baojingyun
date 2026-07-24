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
    positionCards: [],
    filteredPositionCards: [],
    positionSearchVisible: false,
    positionSearchQuery: ''
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
  // 后端字段。在保/待生效人数按 position_id 对 /insured 全量列表分组统计，
  // 已停保的人不计入。之前把两者合并成一个数直接加总，口径本身就漏了一种
  // 情况：员工 status 是 'active' 但 effective_at 还没到（月单最早次日生
  // 效），这批人其实也该算"待生效"——和 pages/employees/employees.js 的
  // isPendingEffective() 判断口径保持一致，不能只看 status==='pending'。
  isPendingEffective(person) {
    return person.status === 'active' && person.effective_at && new Date(person.effective_at) > new Date();
  },
  buildPositionCards(positions, plans, people) {
    const planById = new Map(plans.map((plan) => [plan.id, plan]));
    const positionById = new Map(positions.map((position) => [position.id, position]));
    const activeByPosition = new Map();
    const pendingByPosition = new Map();
    people.forEach((person) => {
      if (!person.position_id) return;
      const pending = person.status === 'pending' || this.isPendingEffective(person);
      if (pending) {
        pendingByPosition.set(person.position_id, (pendingByPosition.get(person.position_id) || 0) + 1);
      } else if (person.status === 'active') {
        activeByPosition.set(person.position_id, (activeByPosition.get(person.position_id) || 0) + 1);
      }
    });
    // 卡片默认只展示已审核通过（status==='approved'）的岗位——那才是真正
    // 能继续新增/减保的入口。但如果某个岗位当前有在保/待生效的人（哪怕岗位
    // 后来因为编辑被打回待审核，生产数据里已经发现过这种情况），这些人依
    // 然是真实在保人数，不能因为过滤掉了岗位卡片就从"在保人数"里凭空消
    // 失——不然几张卡片加起来会比企业实际在保总人数少。这类岗位也一起展示。
    const positionIds = new Set(positions.filter((position) => position.status === 'approved').map((position) => position.id));
    activeByPosition.forEach((_count, id) => positionIds.add(id));
    pendingByPosition.forEach((_count, id) => positionIds.add(id));
    return Array.from(positionIds)
      .map((id) => positionById.get(id))
      .filter(Boolean)
      .map((position) => {
        const plan = position.plan_id ? planById.get(position.plan_id) : null;
        const priceText = plan ? `${plan.insurer} · ${plan.name}` : '尚未关联保司产品';
        return {
          id: position.id,
          name: position.name,
          actual_employer_name: position.actual_employer_name || '',
          occupation_class: position.occupation_class || '待定',
          plan_text: priceText,
          active_count: activeByPosition.get(position.id) || 0,
          pending_count: pendingByPosition.get(position.id) || 0
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
        // 一个企业可能有多个保费账户（不同保司/收款账户）同时余额预警，
        // 后端每条 alert 的 account 字段对премium账户全是同一个字符串
        // 'premium'（区分靠 account_id），用它当 wx:key 会撞车，这里补一个
        // 按位置生成的唯一 key。
        dashboard.balance_alerts = (dashboard.balance_alerts || []).map((alert, index) => ({ ...alert, _key: `${alert.account}-${alert.account_id || 0}-${index}` }));
        const positionCards = this.buildPositionCards(positions || [], plans || [], people || []);
        this.setData({ dashboard, user, enterprise: app.globalData.enterprise || {}, positionCards, loading: false });
        this.applyPositionSearch();
      })
      .catch((error) => { this.setData({ loading: false }); wx.showToast({ title: error.message, icon: 'none' }); });
  },
  // 按企业（用工单位）、保险（保司/产品）、岗位名称三个字段做本地过滤，数据
  // 已经在 load() 里一次性取回，不需要额外请求接口。
  applyPositionSearch() {
    const q = (this.data.positionSearchQuery || '').trim().toLowerCase();
    const filteredPositionCards = q
      ? this.data.positionCards.filter((item) => `${item.name}${item.actual_employer_name}${item.plan_text}`.toLowerCase().includes(q))
      : this.data.positionCards;
    this.setData({ filteredPositionCards });
  },
  togglePositionSearch() {
    const positionSearchVisible = !this.data.positionSearchVisible;
    this.setData({ positionSearchVisible, positionSearchQuery: '' });
    this.applyPositionSearch();
  },
  searchPositions(e) {
    this.setData({ positionSearchQuery: e.detail.value });
    this.applyPositionSearch();
  },
  // 之前点"去充值"只会跳到资金账户总览页（/pages/billing/billing），还要
  // 再点一次才进真正的充值页——直接跳 recharge-request，和 billing.js 里
  // recharge() 的跳转参数保持一致。服务费账户是企业级唯一账户，不用带保司；
  // 保费账户如果这家企业只挂了一个收款账户、且这个账户只对应一个保司名称，
  // 顺手带上，充值页就不用用户自己再选一次；有多个账户/多个保司名称的情况
  // 就不猜，交给充值页自己的保司选择器（recharge-request.js 已经支持不传
  // insurer 时自己加载可选项）。
  goRecharge(e) {
    const accountType = e.currentTarget.dataset.account === 'premium' ? 'premium' : 'usage';
    const enterpriseId = (this.data.enterprise && this.data.enterprise.id) || (app.globalData.user && app.globalData.user.enterprise_id) || 0;
    let insurerParam = '';
    if (accountType === 'premium') {
      const accounts = this.data.dashboard.premium_accounts || [];
      if (accounts.length === 1 && accounts[0].insurers && accounts[0].insurers.length === 1) {
        insurerParam = `&insurer=${encodeURIComponent(accounts[0].insurers[0])}`;
      }
    }
    wx.navigateTo({ url: `/pages/recharge-request/recharge-request?enterpriseId=${enterpriseId}&accountType=${accountType}${insurerParam}` });
  },
  goPosition(e) {
    app.globalData.pendingEmployeesFilter = { status: '', position_id: Number(e.currentTarget.dataset.id) };
    wx.switchTab({ url: '/pages/employees/employees' });
  },
  // 直接跳到新增参保表单，岗位归属信息预锁定；employee-edit.js 已经支持连续
  // 添加（第一人提交成功后不返回、清空个人信息、留在原页面继续填下一人），
  // 不用像 goPosition 那样先经过参保人员列表这一层。employee-edit 不是
  // tabBar 页面，用 wx.navigateTo 没问题。
  addEnroll(e) {
    wx.navigateTo({ url: `/pages/employee-edit/employee-edit?positionId=${e.currentTarget.dataset.id}` });
  },
  addPosition() { wx.navigateTo({ url: '/pages/position-edit/position-edit' }); },
  goLogin() { wx.navigateTo({ url: '/pages/login/login' }); },
  onShareAppMessage() { return app.share('/pages/home/home', 'from=share'); }
});
