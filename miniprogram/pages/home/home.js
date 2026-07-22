const app = getApp();

Page({
  data: {
    loading: true,
    // 小程序必须先打开首页，登录是进入后的授权行为，不允许首次打开就强制
    // 拦截跳登录页——未登录时首页照常渲染，只是展示品牌介绍 + 登录入口，
    // 不请求任何需要鉴权的数据。
    loggedIn: false,
    dashboard: { enterprises: 0, people: 0, pending_people: 0, premium_balance_total: 0, usage_balance: 0, usage_available: 0, usage_recharged: 0, usage_consumed: 0, claims_open: 0, balance_alerts: [] },
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
