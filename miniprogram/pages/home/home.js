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
    const activeByPosition = new Map();
    const pendingByPosition = new Map();
    people.forEach((person) => {
      const pending = person.status === 'pending' || this.isPendingEffective(person);
      if (pending) {
        pendingByPosition.set(person.position_id, (pendingByPosition.get(person.position_id) || 0) + 1);
      } else if (person.status === 'active') {
        activeByPosition.set(person.position_id, (activeByPosition.get(person.position_id) || 0) + 1);
      }
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
