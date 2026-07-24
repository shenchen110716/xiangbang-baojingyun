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
  goPosition(e) {
    app.globalData.pendingEmployeesFilter = { status: '', position_id: Number(e.currentTarget.dataset.id) };
    wx.switchTab({ url: '/pages/employees/employees' });
  },
  addPosition() { wx.navigateTo({ url: '/pages/position-edit/position-edit' }); },
  goLogin() { wx.navigateTo({ url: '/pages/login/login' }); },
  onShareAppMessage() { return app.share('/pages/home/home', 'from=share'); }
});
