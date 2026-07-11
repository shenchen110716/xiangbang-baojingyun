const app = getApp();
Page({
  data: { user: {}, userInitial: '响', enterprise: {}, dashboard: {}, messageCount: 0, loading: true },
  onShow() { Promise.all([app.loadProfile(), app.request('/dashboard', { silent: true }), app.request('/messages', { silent: true })]).then(([user, dashboard, messages]) => this.setData({ user, userInitial: String(user.name || '响').slice(0, 1), enterprise: app.globalData.enterprise || {}, dashboard, messageCount: messages.filter((item) => item.type !== 'success').length, loading: false })).catch(() => this.setData({ loading: false })); },
  go(e) { wx.navigateTo({ url: e.currentTarget.dataset.url }); },
  logout() { wx.showModal({ title: '退出登录', content: '确认退出当前企业账号？', success: (res) => res.confirm && app.logout() }); },
  onShareAppMessage() { return app.share('/pages/home/home', 'from=profile'); }
});
