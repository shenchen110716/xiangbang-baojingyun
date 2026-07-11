const app = getApp();
Page({
  data: { items: [], filtered: [], status: '', loading: true, statuses: [{ value: '', label: '全部' }, { value: 'reported', label: '已报案' }, { value: 'collecting', label: '收集材料' }, { value: 'supplement', label: '待补件' }, { value: 'paid', label: '已赔付' }] },
  onShow() { this.load(); },
  onPullDownRefresh() { this.load().finally(() => wx.stopPullDownRefresh()); },
  load() { return app.request('/claims', { silent: true }).then((items) => { const mapped = items.map((item) => ({ ...item, status_label: app.statusText(item.status), complete_percent: Math.round((7 - Number(item.missing_count || 0)) / 7 * 100) })); this.setData({ items: mapped, loading: false }); this.filter(); }).catch(() => this.setData({ loading: false })); },
  chooseStatus(e) { this.setData({ status: e.currentTarget.dataset.value }); this.filter(); },
  filter() { this.setData({ filtered: this.data.items.filter((item) => !this.data.status || item.status === this.data.status) }); },
  create() { wx.navigateTo({ url: '/pages/claim-create/claim-create' }); },
  detail(e) { wx.navigateTo({ url: `/pages/claim-detail/claim-detail?id=${e.currentTarget.dataset.id}` }); },
  onShareAppMessage() { return app.share('/pages/claims/claims', 'from=share'); }
});
