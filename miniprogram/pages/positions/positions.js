const app = getApp();
Page({
  data: { items: [], filtered: [], status: '', loading: true, statuses: [{ value: '', label: '全部' }, { value: 'approved', label: '已通过' }, { value: 'pending', label: '待审核' }, { value: 'supplement', label: '待补材料' }] },
  onShow() { this.load(); },
  load() { return app.request('/positions', { silent: true }).then((items) => { const mapped = items.map((item) => ({ ...item, status_label: app.statusText(item.status) })); this.setData({ items: mapped, loading: false }); this.filter(); }).catch(() => this.setData({ loading: false })); },
  chooseStatus(e) { this.setData({ status: e.currentTarget.dataset.value }); this.filter(); },
  filter() { this.setData({ filtered: this.data.items.filter((item) => !this.data.status || item.status === this.data.status) }); },
  add() { wx.navigateTo({ url: '/pages/position-edit/position-edit' }); },
  detail(e) { wx.navigateTo({ url: `/pages/position-edit/position-edit?id=${e.currentTarget.dataset.id}` }); },
  onShareAppMessage() { return app.share('/pages/positions/positions', 'from=share'); }
});
