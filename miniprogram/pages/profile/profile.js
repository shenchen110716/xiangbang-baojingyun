const app = getApp();
Page({
  data: { user: {}, userInitial: '响', enterprise: {}, dashboard: {}, messageCount: 0, linkedAccounts: [], loading: true },
  // 首页改为免登录直接打开后，底部 tabBar 从进入小程序起就一直可见，未登录时也能
  // 点到这个 tab；这里补上登录态检查，避免未带 token 直接打接口报"登录已过期"。
  onShow() {
    if (!app.globalData.token) { wx.reLaunch({ url: '/pages/login/login' }); return; }
    Promise.all([app.loadProfile(), app.request('/dashboard', { silent: true }), app.request('/messages', { silent: true })]).then(([user, dashboard, messages]) => {
      dashboard.premium_balance_total = (dashboard.premium_accounts || []).reduce((sum, item) => sum + (item.balance || 0), 0);
      this.setData({ user, userInitial: String(user.name || '响').slice(0, 1), enterprise: app.globalData.enterprise || {}, dashboard, messageCount: messages.filter((item) => item.type !== 'success').length, loading: false });
      this.loadLinkedAccounts();
    }).catch(() => this.setData({ loading: false }));
  },
  go(e) { wx.navigateTo({ url: e.currentTarget.dataset.url }); },
  // 企业切换：同一手机号可能是多家投保单位的主管，各自单独开户；后端 /auth/linked-accounts
  // 已经按手机号匹配好了可切换的账号列表（和 Web 端「切换账户」共用同一套接口），
  // 只对单位主管（is_owner）生效。
  loadLinkedAccounts() {
    if (!this.data.user.is_owner) { this.setData({ linkedAccounts: [] }); return; }
    app.request('/auth/linked-accounts', { silent: true }).then((rows) => this.setData({ linkedAccounts: rows })).catch(() => {});
  },
  switchIdentity() {
    if (!this.data.linkedAccounts.length) { wx.showToast({ title: '暂无可切换的其他企业账号', icon: 'none' }); return; }
    wx.showActionSheet({
      itemList: this.data.linkedAccounts.map((a) => `${a.enterprise_name} · ${a.name}`),
      success: (res) => {
        const target = this.data.linkedAccounts[res.tapIndex];
        app.request(`/auth/switch-account?target_user_id=${target.id}`, { method: 'POST', silent: true }).then((token) => {
          app.globalData.token = token.access_token;
          wx.setStorageSync('token', token.access_token);
          wx.showToast({ title: `已切换到 ${target.enterprise_name}` });
          this.onShow();
        }).catch((error) => wx.showToast({ title: error.message || '切换失败', icon: 'none' }));
      }
    });
  },
  contactSupport() { wx.showModal({ title: '联系客服', content: '如需帮助，请联系您的专属业务员，或在「消息与待办」中留言，平台工作人员会尽快处理。', showCancel: false }); },
  switchUser() { wx.showModal({ title: '切换登录用户', content: '将退出当前账号，目标操作员需重新输入账号和密码。', success: (res) => { if (!res.confirm) return; app.logout(false); wx.reLaunch({ url: '/pages/login/login?switch=1' }); } }); },
  changePassword() { wx.navigateTo({ url: '/pages/change-password/change-password' }); },
  logout() { wx.showModal({ title: '退出登录', content: '确认退出当前企业账号？', success: (res) => res.confirm && app.logout() }); },
  onShareAppMessage() { return app.share('/pages/home/home', 'from=profile'); }
});
