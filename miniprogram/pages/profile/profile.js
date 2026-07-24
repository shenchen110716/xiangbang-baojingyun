const app = getApp();
Page({
  data: { user: {}, userInitial: '响', enterprise: {}, dashboard: { premium_balance_total: 0, premium_recharged_total: 0, premium_consumed_total: 0, usage_available: 0, usage_recharged: 0, usage_consumed: 0, balance_alerts: [] }, messageCount: 0, linkedAccounts: [], loading: true },
  // 未登录时不拦截浏览——菜单结构照常渲染，只是没有账号数据；只有真正
  // 点了会触发接口调用的操作（菜单项、切换账号、改密码等）才跳登录页。
  // 返回 false 时调用方要 return，不再往下执行。
  requireLogin() {
    if (app.globalData.token) return true;
    wx.navigateTo({ url: '/pages/login/login' });
    return false;
  },
  onShow() {
    if (!app.globalData.token) { this.setData({ loading: false }); return; }
    Promise.all([app.loadProfile(), app.request('/dashboard', { silent: true }), app.request('/messages', { silent: true })]).then(([user, dashboard, messages]) => {
      const premiumAccounts = dashboard.premium_accounts || [];
      // 跟首页原来同一份"可用余额"口径（充值 − 已消耗），不是原始充值总额
      // balance——保费/服务费余额这块完整功能从首页搬过来后，充值/已用的
      // 明细文案也一起带过来，不只是搬一个总数。
      dashboard.premium_balance_total = premiumAccounts.reduce((sum, item) => sum + (item.available != null ? item.available : (item.balance || 0)), 0);
      dashboard.premium_recharged_total = premiumAccounts.reduce((sum, item) => sum + (item.recharged != null ? item.recharged : (item.balance || 0)), 0);
      dashboard.premium_consumed_total = premiumAccounts.reduce((sum, item) => sum + (item.consumed || 0), 0);
      // 一个企业可能有多个保费账户（不同保司/收款账户）同时余额预警，后端
      // 每条 alert 的 account 字段对premium账户全是同一个字符串 'premium'
      // （区分靠 account_id），用它当 wx:key 会撞车，这里补一个按位置生成
      // 的唯一 key。
      dashboard.balance_alerts = (dashboard.balance_alerts || []).map((alert, index) => ({ ...alert, _key: `${alert.account}-${alert.account_id || 0}-${index}` }));
      this.setData({ user, userInitial: String(user.name || '响').slice(0, 1), enterprise: app.globalData.enterprise || {}, dashboard, messageCount: messages.filter((item) => item.type !== 'success').length, loading: false });
      this.loadLinkedAccounts();
    }).catch(() => this.setData({ loading: false }));
  },
  go(e) {
    if (!this.requireLogin()) return;
    wx.navigateTo({ url: e.currentTarget.dataset.url });
  },
  // 保费/服务费余额卡片的完整功能（含去充值跳转）从首页搬过来的：直接跳
  // 到真正的充值页（不是先到账户总览页 billing 再点一次），和首页原来
  // goRecharge() 同一套跳转参数——服务费账户是企业级唯一账户不用带保司；
  // 保费账户如果这家企业只挂了一个收款账户、且这个账户只对应一个保司
  // 名称，顺手带上，充值页就不用用户自己再选一次。
  goRecharge(e) {
    if (!this.requireLogin()) return;
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
  // 企业切换：同一手机号可能是多家投保单位的主管，各自单独开户；后端 /auth/linked-accounts
  // 已经按手机号匹配好了可切换的账号列表（和 Web 端「切换账户」共用同一套接口），
  // 只对单位主管（is_owner）生效。
  loadLinkedAccounts() {
    if (!this.data.user.is_owner) { this.setData({ linkedAccounts: [] }); return; }
    app.request('/auth/linked-accounts', { silent: true }).then((rows) => this.setData({ linkedAccounts: rows })).catch(() => {});
  },
  switchIdentity() {
    if (!this.requireLogin()) return;
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
  goEmployees() {
    if (!this.requireLogin()) return;
    wx.switchTab({ url: '/pages/employees/employees' });
  },
  switchUser() {
    if (!this.requireLogin()) return;
    wx.showModal({ title: '切换登录用户', content: '将退出当前账号，目标操作员需重新输入账号和密码。', success: (res) => { if (!res.confirm) return; app.logout(false); wx.reLaunch({ url: '/pages/login/login?switch=1' }); } });
  },
  changePassword() {
    if (!this.requireLogin()) return;
    wx.navigateTo({ url: '/pages/change-password/change-password' });
  },
  logout() {
    if (!this.requireLogin()) return;
    wx.showModal({ title: '退出登录', content: '确认退出当前企业账号？', success: (res) => res.confirm && app.logout() });
  },
  onShareAppMessage() { return app.share('/pages/home/home', 'from=profile'); }
});
