const app = getApp();

Page({
  data: { items: [], loading: true },
  onShow() { this.load(); },
  onPullDownRefresh() { this.load().finally(() => wx.stopPullDownRefresh()); },
  load() {
    return app.request('/actual-employers', { silent: true }).then((items) => this.setData({ items: items.map((item) => ({ ...item, status_label: item.status === 'active' ? '合作中' : '已暂停' })), loading: false })).catch(() => this.setData({ loading: false }));
  },
  add() { wx.navigateTo({ url: '/pages/employer-edit/employer-edit' }); },
  edit(e) { wx.navigateTo({ url: `/pages/employer-edit/employer-edit?id=${e.currentTarget.dataset.id}` }); },
  remove(e) { const id = e.currentTarget.dataset.id; wx.showModal({ title: '删除工作单位', content: '已关联岗位的单位不能删除，可改为暂停使用。确认继续？', confirmColor: '#d94357', success: (res) => res.confirm && app.request(`/actual-employers/${id}`, { method: 'DELETE' }).then(() => { wx.showToast({ title: '已删除' }); this.load(); }) }); },
  toggle(e) {
    const item = this.data.items.find((row) => row.id === e.currentTarget.dataset.id); if (!item) return;
    const target = item.status === 'active' ? 'paused' : 'active';
    wx.showModal({ title: target === 'paused' ? '暂停使用' : '恢复使用', content: target === 'paused' ? '暂停后该实际工作单位不能新增岗位。' : '确认恢复该单位？', success: (res) => res.confirm && app.request(`/actual-employers/${item.id}/status?status=${target}`, { method: 'PATCH' }).then(() => this.load()) });
  },
  onShareAppMessage() { return app.share('/pages/employers/employers', 'from=share'); }
});
