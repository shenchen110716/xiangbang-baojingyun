const app = getApp();

Page({
  data: { id: 0, item: null, loading: true, operating: false },
  onLoad(options) { this.setData({ id: Number(options.id) }); },
  onShow() { this.load(); },
  load() {
    return app.request('/insured', { silent: true }).then((items) => {
      const item = items.find((row) => row.id === this.data.id) || null;
      if (item) { item.status_label = app.statusText(item.status); item.initial = String(item.name || '员').slice(0, 1); }
      this.setData({ item, loading: false });
    }).catch(() => this.setData({ loading: false }));
  },
  edit() { wx.navigateTo({ url: `/pages/employee-edit/employee-edit?id=${this.data.id}` }); },
  claim() { wx.navigateTo({ url: `/pages/claim-create/claim-create?personId=${this.data.id}` }); },
  changeStatus() {
    const item = this.data.item;
    if (!item || item.status === 'pending') return;
    const stopped = item.status === 'stopped', target = stopped ? 'pending' : 'stopped';
    wx.showModal({ title: stopped ? '申请恢复参保' : '确认停保', content: stopped ? '恢复后将重新进入人工审核。' : '停保后将不再产生后续保费与使用费。', confirmColor: stopped ? '#3157e5' : '#d94357', success: (res) => {
      if (!res.confirm) return; this.setData({ operating: true });
      app.request(`/insured/${item.id}/status?status=${target}`, { method: 'PATCH' }).then(() => { wx.showToast({ title: stopped ? '已提交审核' : '已停保' }); this.setData({ operating: false }); this.load(); }).catch(() => this.setData({ operating: false }));
    } });
  },
  onShareAppMessage() { return app.share('/pages/employee-detail/employee-detail', `id=${this.data.id}`); }
});
