const app = getApp();

Page({
  data: { user: {}, items: [], loading: true, canManage: false },
  onShow() { this.load(); },
  onPullDownRefresh() { this.load().finally(() => wx.stopPullDownRefresh()); },
  load() {
    return Promise.all([app.loadProfile(), app.request('/operators', { silent: true })])
      .then(([user, items]) => this.setData({ user, items, canManage: user.role === 'admin' || user.is_owner, loading: false }))
      .catch(() => this.setData({ loading: false }));
  },
  add() { wx.navigateTo({ url: '/pages/operator-edit/operator-edit' }); },
  toggle(e) {
    const item = this.data.items.find((row) => row.id === Number(e.currentTarget.dataset.id));
    if (!item || item.is_owner) return;
    const active = !item.active;
    wx.showModal({ title: active ? '启用操作员' : '停用操作员', content: `确认${active?'启用':'停用'} ${item.name} 的登录账号？`, success: (res) => res.confirm && app.request(`/operators/${item.id}`, { method: 'PATCH', data: { active } }).then(() => this.load()) });
  },
  reset(e) {
    const id = Number(e.currentTarget.dataset.id);
    wx.showModal({ title: '重置密码', editable: true, placeholderText: '请输入至少 6 位新密码', success: (res) => { if (!res.confirm) return; const password = String(res.content || ''); if (password.length < 6) { wx.showToast({ title: '密码至少 6 位', icon: 'none' }); return; } app.request(`/operators/${id}`, { method: 'PATCH', data: { password } }).then(() => wx.showToast({ title: '密码已重置' })); } });
  }
});
