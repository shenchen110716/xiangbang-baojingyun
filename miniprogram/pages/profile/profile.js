const app = getApp();
Page({
  data: { loggedIn: false, user: {}, userInitial: '响', enterprise: {}, dashboard: {}, messageCount: 0, linkedAccounts: [], loading: true },
  // 未登录时不能强制跳登录页——底部 tabBar 一直可见，未登录也能点到这个
  // tab，之前一进页面就 reLaunch 到登录页，等于逛都不让逛。改成和首页一样，
  // 未登录只展示品牌介绍 + 登录入口，不请求任何需要鉴权的数据；真正需要
  // 登录的操作（点菜单项、切换账号等）由 go() 等方法自己拦截提示登录。
  onShow() {
    if (!app.globalData.token) { this.setData({ loggedIn: false, loading: false }); return; }
    this.setData({ loggedIn: true });
    Promise.all([app.loadProfile(), app.request('/dashboard', { silent: true }), app.request('/messages', { silent: true })]).then(([user, dashboard, messages]) => {
      dashboard.premium_balance_total = (dashboard.premium_accounts || []).reduce((sum, item) => sum + (item.balance || 0), 0);
      this.setData({ user, userInitial: String(user.name || '响').slice(0, 1), enterprise: app.globalData.enterprise || {}, dashboard, messageCount: messages.filter((item) => item.type !== 'success').length, loading: false });
      this.loadLinkedAccounts();
    }).catch(() => this.setData({ loading: false }));
  },
  goLogin() { wx.navigateTo({ url: '/pages/login/login' }); },
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
  // pages/employees/employees 是 tabBar 页面，wx.navigateTo 不能跳 tabBar
  // 页面（会直接失败），必须用 wx.switchTab；不带筛选条件，进去就是全量列表。
  goEmployees() { wx.switchTab({ url: '/pages/employees/employees' }); },
  switchUser() { wx.showModal({ title: '切换登录用户', content: '将退出当前账号，目标操作员需重新输入账号和密码。', success: (res) => { if (!res.confirm) return; app.logout(false); wx.reLaunch({ url: '/pages/login/login?switch=1' }); } }); },
  changePassword() { wx.navigateTo({ url: '/pages/change-password/change-password' }); },
  logout() { wx.showModal({ title: '退出登录', content: '确认退出当前企业账号？', success: (res) => res.confirm && app.logout() }); },
  onShareAppMessage() { return app.share('/pages/home/home', 'from=profile'); }
});
