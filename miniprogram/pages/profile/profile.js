const app = getApp();
Page({
  data: { user: {}, userInitial: '响', enterprise: {}, dashboard: {}, messageCount: 0, loading: true },
  // 首页改为免登录直接打开后，底部 tabBar 从进入小程序起就一直可见，未登录时也能
  // 点到这个 tab；这里补上登录态检查，避免未带 token 直接打接口报"登录已过期"。
  onShow() { if (!app.globalData.token) { wx.reLaunch({ url: '/pages/login/login' }); return; } Promise.all([app.loadProfile(), app.request('/dashboard', { silent: true }), app.request('/messages', { silent: true })]).then(([user, dashboard, messages]) => { dashboard.premium_balance_total = (dashboard.premium_accounts || []).reduce((sum, item) => sum + (item.balance || 0), 0); this.setData({ user, userInitial: String(user.name || '响').slice(0, 1), enterprise: app.globalData.enterprise || {}, dashboard, messageCount: messages.filter((item) => item.type !== 'success').length, loading: false }); }).catch(() => this.setData({ loading: false })); },
  go(e) { wx.navigateTo({ url: e.currentTarget.dataset.url }); },
  switchUser() { wx.showModal({ title: '切换登录用户', content: '将退出当前账号，目标操作员需重新输入账号和密码。', success: (res) => { if (!res.confirm) return; app.logout(false); wx.reLaunch({ url: '/pages/login/login?switch=1' }); } }); },
  changePassword() { wx.navigateTo({ url: '/pages/change-password/change-password' }); },
  logout() { wx.showModal({ title: '退出登录', content: '确认退出当前企业账号？', success: (res) => res.confirm && app.logout() }); },
  onShareAppMessage() { return app.share('/pages/home/home', 'from=profile'); }
});
