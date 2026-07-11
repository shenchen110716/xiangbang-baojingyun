const app = getApp();

Page({
  data: {
    loading: true,
    dashboard: { enterprises: 0, people: 0, pending_people: 0, premium_balance: 0, usage_balance: 0, claims_open: 0, balance_alerts: [] },
    messages: [],
    user: {},
    enterprise: {},
    greeting: '你好',
    today: ''
  },
  onShow() { this.load(); },
  onPullDownRefresh() { this.load().finally(() => wx.stopPullDownRefresh()); },
  load() {
    const hour = new Date().getHours();
    const greeting = hour < 6 ? '夜深了' : hour < 12 ? '上午好' : hour < 18 ? '下午好' : '晚上好';
    const today = new Date().toLocaleDateString('zh-CN', { month: 'long', day: 'numeric', weekday: 'long' });
    this.setData({ loading: true, greeting, today });
    return Promise.all([app.request('/dashboard', { silent: true }), app.request('/messages', { silent: true }), app.loadProfile()])
      .then(([dashboard, messages, user]) => this.setData({ dashboard, messages: messages.slice(0, 3), user, enterprise: app.globalData.enterprise || {}, loading: false }))
      .catch((error) => { this.setData({ loading: false }); wx.showToast({ title: error.message, icon: 'none' }); });
  },
  go(e) { wx.navigateTo({ url: e.currentTarget.dataset.url }); },
  openMessage(e) { const path = e.currentTarget.dataset.path; if (path) wx.navigateTo({ url: path }); },
  onShareAppMessage() { return app.share('/pages/home/home', 'from=share'); }
});
