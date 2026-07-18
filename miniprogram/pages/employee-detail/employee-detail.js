const app = getApp();

Page({
  data: { id: 0, item: null, loading: true, operating: false },
  onLoad(options) { this.setData({ id: Number(options.id) }); },
  onShow() { this.load(); },
  load() {
    return app.request('/insured', { silent: true }).then((items) => {
      const item = items.find((row) => row.id === this.data.id) || null;
      if (item) {
        const pendingEffective = item.status === 'active' && item.effective_at && new Date(item.effective_at) > new Date();
        item.status_label = (pendingEffective || item.status === 'pending') ? '待生效' : app.statusText(item.status);
        item.status_display = pendingEffective ? 'active-pending' : item.status;
        item.initial = String(item.name || '员').slice(0, 1);
        item.effective_at_display = app.formatCoverageDate(item.effective_at, item.effective_mode);
        item.terminated_at_display = app.formatCoverageDate(item.terminated_at, item.effective_mode);
      }
      this.setData({ item, loading: false });
    }).catch(() => this.setData({ loading: false }));
  },
  edit() { wx.navigateTo({ url: `/pages/employee-edit/employee-edit?id=${this.data.id}` }); },
  claim() { wx.navigateTo({ url: `/pages/claim-create/claim-create?personId=${this.data.id}` }); },
  cert() { wx.navigateTo({ url: `/pages/cert/cert?id=${this.data.id}` }); },
  changeStatus() {
    const item = this.data.item;
    if (!item || item.status === 'pending') return;
    if (item.status === 'active') {
      // 停保需要选择停保时间，不能一键直接停保，跳转到编辑页选择日期
      wx.navigateTo({ url: `/pages/employee-edit/employee-edit?id=${item.id}` });
      return;
    }
    wx.showModal({ title: '申请恢复参保', content: '恢复后将重新进入人工审核。', confirmColor: '#1d4ed8', success: (res) => {
      if (!res.confirm) return; this.setData({ operating: true });
      app.request(`/insured/${item.id}/status?status=pending`, { method: 'PATCH' }).then(() => { wx.showToast({ title: '已提交审核' }); this.setData({ operating: false }); this.load(); }).catch(() => this.setData({ operating: false }));
    } });
  },
  onShareAppMessage() { return app.share('/pages/employee-detail/employee-detail', `id=${this.data.id}`); }
});
