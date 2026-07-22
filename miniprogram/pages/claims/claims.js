const app = getApp();
Page({
  data: { items: [], filtered: [], status: '', loading: true, statuses: [{ value: '', label: '全部' }, { value: 'reported', label: '已报案' }, { value: 'collecting', label: '材料收集' }, { value: 'submitted', label: '已提交保司' }, { value: 'insurer_review', label: '保司审核' }, { value: 'supplement', label: '待补件' }, { value: 'approved', label: '核赔通过' }, { value: 'paid', label: '已赔付' }, { value: 'rejected', label: '拒赔' }, { value: 'closed', label: '已结案' }] },
  // 首页改为免登录直接打开后，底部 tabBar 从进入小程序起就一直可见，未登录时也能
  // 点到这个 tab；这里补上登录态检查，避免未带 token 直接打接口报"登录已过期"。
  onShow() { if (!app.globalData.token) { wx.reLaunch({ url: '/pages/login/login' }); return; } this.load(); },
  onPullDownRefresh() { this.load().finally(() => wx.stopPullDownRefresh()); },
  load() { const labels = { reported: '已报案', collecting: '材料收集中', submitted: '已提交保司', insurer_review: '保司审核中', supplement: '待补充材料', approved: '核赔通过', paid: '已赔付', rejected: '拒赔', closed: '已结案' }; return app.request('/claims', { silent: true }).then((items) => { const mapped = items.map((item) => ({ ...item, status_label: labels[item.status] || item.status, deadline_text: item.deadline_days === null ? '未计算' : item.deadline_days < 0 ? `已超期 ${Math.abs(item.deadline_days)} 天` : `剩余 ${item.deadline_days} 天` })); this.setData({ items: mapped, loading: false }); this.filter(); }).catch(() => this.setData({ loading: false })); },
  chooseStatus(e) { this.setData({ status: e.currentTarget.dataset.value }); this.filter(); },
  filter() { this.setData({ filtered: this.data.items.filter((item) => !this.data.status || item.status === this.data.status) }); },
  create() { wx.navigateTo({ url: '/pages/claim-create/claim-create' }); },
  detail(e) { wx.navigateTo({ url: `/pages/claim-detail/claim-detail?id=${e.currentTarget.dataset.id}` }); },
  onShareAppMessage() { return app.share('/pages/claims/claims', 'from=share'); }
});
