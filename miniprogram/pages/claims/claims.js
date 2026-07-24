const app = getApp();
const OPEN_STATUSES = ['reported', 'collecting', 'submitted', 'insurer_review', 'supplement'];
const CLOSED_STATUSES = ['approved', 'paid', 'rejected', 'closed'];

Page({
  data: {
    items: [], filtered: [], tab: 'open', search: '', loading: true,
    labels: { reported: '已报案', collecting: '材料收集中', submitted: '已提交保司', insurer_review: '保司审核中', supplement: '待补充材料', approved: '核赔通过', paid: '已赔付', rejected: '拒赔', closed: '已结案' }
  },
  // 未登录时不拦截浏览——页面正常渲染，只是列表是空的；只有真正点了会
  // 触发接口调用的操作（报案、查看/提交资料）才跳登录页。返回 false
  // 时调用方要 return，不再往下执行。
  requireLogin() {
    if (app.globalData.token) return true;
    wx.navigateTo({ url: '/pages/login/login' });
    return false;
  },
  onShow() {
    if (!app.globalData.token) { this.setData({ loading: false }); return; }
    this.load();
  },
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
    // 把 "-" 换成 "/" 是给纯日期串（yyyy-mm-dd）兼容旧 iOS 用的，后端现在
    // 返回的是带 "T" 的完整 ISO 时间（yyyy-mm-ddTHH:mm:ss），整串替换会把
    // 它拆成 yyyy/mm/ddTHH:mm:ss——不是合法格式，解析成 Invalid Date（getTime
    // 是 NaN），setData 传输时 NaN 会被序列化成 null，页面上就直接显示成了
    // 字面的"null"。带 "T" 的完整 ISO 串本身各端都能正确解析，不用再替换。
    const normalized = dateStr.indexOf('T') > -1 ? dateStr : dateStr.replace(/-/g, '/');
    const diff = Date.now() - new Date(normalized).getTime();
    if (Number.isNaN(diff)) return 0;
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
  create() {
    if (!this.requireLogin()) return;
    wx.navigateTo({ url: '/pages/claim-create/claim-create' });
  },
  detail(e) {
    if (!this.requireLogin()) return;
    wx.navigateTo({ url: `/pages/claim-detail/claim-detail?id=${e.currentTarget.dataset.id}` });
  },
  onShareAppMessage() { return app.share('/pages/claims/claims', 'from=share'); }
});
