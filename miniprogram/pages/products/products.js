const app = getApp();
Page({
  data: { items: [], loading: true },
  onShow() { app.request('/plans', { silent: true }).then((items) => this.setData({ items: items.map((item) => ({ ...item, status_label: app.statusText(item.status) })), loading: false })).catch(() => this.setData({ loading: false })); },
  detail(e) { wx.navigateTo({ url: `/pages/product-detail/product-detail?id=${e.currentTarget.dataset.id}` }); },
  onShareAppMessage() { return app.share('/pages/products/products', 'from=share'); }
});
