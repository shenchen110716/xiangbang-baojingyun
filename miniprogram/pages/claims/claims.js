const app = getApp();
const OPEN_STATUSES = ['reported', 'collecting', 'submitted', 'insurer_review', 'supplement'];
const CLOSED_STATUSES = ['approved', 'paid', 'rejected', 'closed'];

Page({
  data: {
    items: [], filtered: [], tab: 'open', search: '', loading: true,
    labels: { reported: '已报案', collecting: '材料收集中', submitted: '已提交保司', insurer_review: '保司审核中', supplement: '待补充材料', approved: '核赔通过', paid: '已赔付', rejected: '拒赔', closed: '已结案' }
  },
  // 首页改为免登录直接打开后，底部 tabBar 从进入小程序起就一直可见，未登录时也能
  // 点到这个 tab；这里补上登录态检查，避免未带 token 直接打接口报"登录已过期"。
  onShow() { if (!app.globalData.token) { wx.reLaunch({ url: '/pages/login/login' }); return; } this.load(); },
  onPullDownRefresh() { this.load().finally(() => wx.stopPullDownRefresh()); },
  load() {
    const labels = this.data.labels;
    return app.request('/claims', { silent: true }).then((items) => {
      const mapped = items.map((item) => ({
        ...item,
        status_label: labels[item.status] || item.status,
        deadline_text: item.deadline_days === null ? '未计算' : item.deadline_days < 0 ? `已超期 ${Math.abs(item.deadline_days)} 天` : `剩余 ${item.deadline_days} 天`,
        id_number_masked: this.maskId(item.id_number),
        reported_days: this.daysSince(item.created_at),
      }));
      this.setData({ items: mapped, loading: false }); this.filter();
    }).catch(() => this.setData({ loading: false }));
  },
  maskId(value) { const text = String(value || ''); return text.length > 10 ? `${text.slice(0, 3)}${'*'.repeat(text.length - 7)}${text.slice(-4)}` : text; },
  daysSince(dateStr) {
    if (!dateStr) return 0;
    const diff = Date.now() - new Date(dateStr.replace(/-/g, '/')).getTime();
    return Math.max(0, Math.floor(diff / 86400000));
  },
  chooseTab(e) { this.setData({ tab: e.currentTarget.dataset.value }); this.filter(); },
  onSearch(e) { this.setData({ search: e.detail.value }); this.filter(); },
  filter() {
    const bucket = this.data.tab === 'open' ? OPEN_STATUSES : CLOSED_STATUSES;
    const q = this.data.search.trim().toLowerCase();
    const filtered = this.data.items.filter((item) => bucket.includes(item.status) && (!q || (item.person_name || '').toLowerCase().includes(q)));
    this.setData({ filtered });
  },
  create() { wx.navigateTo({ url: '/pages/claim-create/claim-create' }); },
  detail(e) { wx.navigateTo({ url: `/pages/claim-detail/claim-detail?id=${e.currentTarget.dataset.id}` }); },
  onShareAppMessage() { return app.share('/pages/claims/claims', 'from=share'); }
});
