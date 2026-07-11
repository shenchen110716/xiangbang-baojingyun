const app = getApp();
Page({
  data: { id: 0, item: null, people: [], loading: true, exporting: false },
  onLoad(options) { const id = Number(options.id); this.setData({ id }); Promise.all([app.request('/policies'), app.request('/insured')]).then(([policies, people]) => this.setData({ item: policies.find((row) => row.id === id) || null, people: people.filter((row) => row.policy_id === id), loading: false })).catch(() => this.setData({ loading: false })); },
  person(e) { wx.navigateTo({ url: `/pages/employee-detail/employee-detail?id=${e.currentTarget.dataset.id}` }); },
  exportPolicy() { this.setData({ exporting: true }); wx.downloadFile({ url: `${app.globalData.apiBase}/policies/${this.data.id}/export`, header: { Authorization: `Bearer ${app.globalData.token}` }, success: (res) => { this.setData({ exporting: false }); if (res.statusCode === 200) wx.openDocument({ filePath: res.tempFilePath, showMenu: true }); else wx.showToast({ title: '保单导出失败', icon: 'none' }); }, fail: () => { this.setData({ exporting: false }); wx.showToast({ title: '保单导出失败', icon: 'none' }); } }); },
  onShareAppMessage() { return app.share('/pages/policy-detail/policy-detail', `id=${this.data.id}`); }
});
