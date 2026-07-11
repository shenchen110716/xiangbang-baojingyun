const app = getApp();
Page({
  data: { id: 0, item: null, tiers: [], loading: true },
  onLoad(options) { const id = Number(options.id); this.setData({ id }); Promise.all([app.request('/plans'), app.request(`/plan-tiers?plan_id=${id}`)]).then(([plans, tiers]) => this.setData({ item: plans.find((row) => row.id === id) || null, tiers, loading: false })).catch(() => this.setData({ loading: false })); },
  positions() { wx.navigateTo({ url: '/pages/positions/positions' }); },
  onShareAppMessage() { return app.share('/pages/product-detail/product-detail', `id=${this.data.id}`); }
});
