const app = getApp();

Page({
  data: {
    items: [],
    filtered: [],
    q: '',
    status: '',
    statuses: [{ value: '', label: '全部' }, { value: 'pending', label: '待生效' }, { value: 'active', label: '在保' }, { value: 'stopped', label: '已停保' }],
    loading: false
  },
  onLoad(options) { if (options && options.status) this.setData({ status: options.status }); },
  // 首页改为免登录直接打开后，底部 tabBar 从进入小程序起就一直可见，未登录时也能
  // 点到这个 tab；这里补上登录态检查，避免未带 token 直接打接口报"登录已过期"。
  onShow() { if (!app.globalData.token) { wx.reLaunch({ url: '/pages/login/login' }); return; } this.load(); },
  onPullDownRefresh() { this.load().finally(() => wx.stopPullDownRefresh()); },
  isPendingEffective(item) { return item.status === 'active' && item.effective_at && new Date(item.effective_at) > new Date(); },
  load() {
    this.setData({ loading: true });
    return app.request('/insured', { silent: true }).then((items) => {
      const mapped = items.map((item) => {
        const pendingEffective = this.isPendingEffective(item);
        // 待生效对外统一成一个筛选值：待审核(pending) + 已通过但未来才生效(active 但 effective_at
        // 还没到)，之前拆成 pending/active-pending 两个同名不同值的筛选项，看着像重复。
        const pendingBucket = pendingEffective || item.status === 'pending';
        return { ...item, initial: String(item.name || '员').slice(0, 1), status_label: pendingBucket ? '待生效' : app.statusText(item.status), status_display: pendingBucket ? 'pending' : item.status, id_masked: this.maskId(item.id_number) };
      });
      this.setData({ items: mapped, loading: false }); this.applyFilter();
    }).catch((error) => { this.setData({ loading: false }); wx.showToast({ title: error.message, icon: 'none' }); });
  },
  maskId(value) { const text = String(value || ''); return text.length > 10 ? `${text.slice(0, 6)}********${text.slice(-4)}` : text; },
  search(e) { this.setData({ q: e.detail.value }); this.applyFilter(); },
  chooseStatus(e) { this.setData({ status: e.currentTarget.dataset.value }); this.applyFilter(); },
  applyFilter() {
    const q = this.data.q.trim().toLowerCase(), status = this.data.status;
    const filtered = this.data.items.filter((item) => (!status || item.status_display === status) && (!q || `${item.name}${item.phone}${item.id_number}${item.position_name}${item.actual_employer_name}`.toLowerCase().includes(q)));
    this.setData({ filtered });
  },
  add() { wx.navigateTo({ url: '/pages/employee-edit/employee-edit' }); },
  detail(e) { wx.navigateTo({ url: `/pages/employee-detail/employee-detail?id=${e.currentTarget.dataset.id}` }); },
  importFile() { wx.navigateTo({ url: '/pages/import/import' }); },
  onShareAppMessage() { return app.share('/pages/employees/employees', 'from=share'); }
});
