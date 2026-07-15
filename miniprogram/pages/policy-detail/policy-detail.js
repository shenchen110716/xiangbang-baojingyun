const app = getApp();
Page({
  data: { id: 0, item: null, people: [], loading: true, exporting: false },
  onLoad(options) { const id = Number(options.id); this.setData({ id }); Promise.all([app.request('/policies'), app.request('/insured')]).then(([policies, people]) => this.setData({ item: policies.find((row) => row.id === id) || null, people: people.filter((row) => row.policy_id === id), loading: false })).catch(() => this.setData({ loading: false })); },
  person(e) { wx.navigateTo({ url: `/pages/employee-detail/employee-detail?id=${e.currentTarget.dataset.id}` }); },
  exportPolicy() { const policyNo = (this.data.item && this.data.item.policy_no) || this.data.id; this.setData({ exporting: true }); app.downloadAndOpen(`/policies/${this.data.id}/export`, { filename: `保单-${policyNo}.xlsx`, fileType: 'xlsx', loadingTitle: '正在导出' }).catch(() => {}).then(() => this.setData({ exporting: false })); },
  onShareAppMessage() { return app.share('/pages/policy-detail/policy-detail', `id=${this.data.id}`); }
});
