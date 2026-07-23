const app = getApp();

Page({
  data: {
    loading: true,
    // 小程序必须先打开首页，登录是进入后的授权行为，不允许首次打开就强制
    // 拦截跳登录页——未登录时首页照常渲染，只是展示品牌介绍 + 登录入口，
    // 不请求任何需要鉴权的数据。
    loggedIn: false,
    dashboard: { active_people: 0, claims_open: 0, usage_available: 0 },
    user: {},
    enterprise: {},
    greeting: '你好',
    search: '',
    positions: [],
    filtered: []
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
    this.setData({ loading: true, greeting });
    return Promise.all([app.request('/dashboard', { silent: true }), app.request('/positions', { silent: true }), app.loadProfile()])
      .then(([dashboard, positions, user]) => {
        const premiumAccounts = dashboard.premium_accounts || [];
        dashboard.premium_balance_total = premiumAccounts.reduce((sum, item) => sum + (item.available != null ? item.available : (item.balance || 0)), 0);
        const mapped = positions.filter((item) => item.status === 'approved').map((item) => ({
          ...item,
          status_label: item.has_active_people ? '保障中' : '暂无参保',
        }));
        this.setData({ dashboard, positions: mapped, user, enterprise: app.globalData.enterprise || {}, loading: false });
        this.filter();
      })
      .catch((error) => { this.setData({ loading: false }); wx.showToast({ title: error.message, icon: 'none' }); });
  },
  onSearch(e) { this.setData({ search: e.detail.value }); this.filter(); },
  filter() {
    const q = this.data.search.trim().toLowerCase();
    const filtered = !q ? this.data.positions : this.data.positions.filter((item) =>
      [item.name, item.actual_employer_name, item.plan_name].some((v) => (v || '').toLowerCase().includes(q)));
    this.setData({ filtered });
  },
  go(e) { wx.navigateTo({ url: e.currentTarget.dataset.url }); },
  goLogin() { wx.navigateTo({ url: '/pages/login/login' }); },
  addPosition() { wx.navigateTo({ url: '/pages/position-edit/position-edit' }); },
  editPosition(e) { wx.navigateTo({ url: `/pages/position-edit/position-edit?id=${e.currentTarget.dataset.id}` }); },
  // 已有参保员工的岗位不允许改实际用工单位/岗位信息，和 Web 端参保方案页规则一致
  // （保经云 7-24 反馈）；这里直接复用 /positions 已经返回的 has_active_people。
  removePosition(e) {
    const item = this.data.positions.find((p) => p.id === e.currentTarget.dataset.id);
    if (item && item.has_active_people) { wx.showToast({ title: '该岗位已有参保员工，不能删除', icon: 'none' }); return; }
    wx.showModal({
      title: '删除方案', content: '删除后不可恢复，确认删除该方案吗？', confirmColor: '#dc2626',
      success: (res) => { if (res.confirm) app.request(`/positions/${item.id}`, { method: 'DELETE' }).then(() => { wx.showToast({ title: '已删除' }); this.load(); }); }
    });
  },
  goInsure(e) { wx.navigateTo({ url: `/pages/employee-edit/employee-edit?positionId=${e.currentTarget.dataset.id}` }); },
  onShareAppMessage() { return app.share('/pages/home/home', 'from=share'); }
});
