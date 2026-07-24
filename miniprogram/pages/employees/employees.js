const app = getApp();

Page({
  data: {
    items: [],
    filtered: [],
    q: '',
    status: '',
    positionId: 0,
    positionName: '',
    statuses: [{ value: '', label: '全部' }, { value: 'pending', label: '待生效' }, { value: 'active', label: '在保' }, { value: 'stopped', label: '已停保' }],
    statusChips: [],
    loading: false,
    // 参保/停保批量操作：不选具体人直接点标题栏"参保"/"停保"进入勾选模式，
    // 和网页端 WorkersView 的"批量参保/批量停保"同一套接口，只是把网页表格
    // 多选换成这里逐条 checkbox。停保必须先选停保时间，不能像参保那样直接
    // 一键提交——和网页端同一条限制（不能一键立即停保）。
    selectMode: '', // '' | 'active' | 'stopped'
    selectedIds: [],
    stopDate: '',
    bulkSubmitting: false
  },
  onLoad(options) {
    if (options && options.status) this.setData({ status: options.status });
    if (options && options.position_id) this.setData({ positionId: Number(options.position_id) });
  },
  // 首页改为免登录直接打开后，底部 tabBar 从进入小程序起就一直可见，未登录时也能
  // 点到这个 tab；这里补上登录态检查，避免未带 token 直接打接口报"登录已过期"。
  onShow() {
    if (!app.globalData.token) { wx.reLaunch({ url: '/pages/login/login' }); return; }
    // 首页的统计卡片/参保方案卡片跳这个 tab 时用 wx.switchTab（tabBar 页面
    // 不支持 wx.navigateTo，switchTab 又不支持带参数），筛选条件走全局变量
    // 中转，这里读一次就清空。没有待处理的中转数据说明是用户直接点了底部
    // tab（不是从首页卡片跳进来的）——这时要把上一次可能残留的岗位筛选
    // （positionId）清掉，不然用户从首页某个岗位卡片进来看过一次之后，
    // 再直接点"员工" tab 会莫名其妙一直停留在那个岗位的筛选结果里。
    // status 是页面自己的筛选 chip，属于用户在本页内的选择，不受这次影响。
    const pending = app.globalData.pendingEmployeesFilter;
    if (pending) {
      app.globalData.pendingEmployeesFilter = null;
      this.setData({ status: pending.status || '', positionId: pending.position_id || 0 });
    } else if (this.data.positionId) {
      this.setData({ positionId: 0, positionName: '' });
    }
    this.load();
  },
  onPullDownRefresh() { this.load().finally(() => wx.stopPullDownRefresh()); },
  isPendingEffective(item) { return item.status === 'active' && item.effective_at && new Date(item.effective_at) > new Date(); },
  load() {
    this.setData({ loading: true });
    return app.request('/insured', { silent: true }).then((items) => {
      const mapped = items.map((item) => {
        const pendingEffective = this.isPendingEffective(item);
        // 待生效对外统一成一个筛选值：待审核(pending) + 已通过但未来才生效(active 但 effective_at
        // 还没到)，之前拆成 pending/active-pending 两个同名不同值的筛选项，看着像重复。
        const pendingBucket = pendingEffective || item.status === 'pending';
        return { ...item, initial: String(item.name || '员').slice(0, 1), status_label: pendingBucket ? '待生效' : app.statusText(item.status), status_display: pendingBucket ? 'pending' : item.status, id_masked: this.maskId(item.id_number) };
      });
      // 首页参保方案卡片带 position_id 进来时，标题里显示岗位名——从命中的第一条
      // 记录上取 position_name，不用再单独请求 /positions。
      const positionName = this.data.positionId ? ((mapped.find((item) => item.position_id === this.data.positionId) || {}).position_name || '') : '';
      this.setData({ items: mapped, positionName, loading: false });
      this.applyFilter();
    }).catch((error) => { this.setData({ loading: false }); wx.showToast({ title: error.message, icon: 'none' }); });
  },
  // 筛选 chip 文案后面带上人数，和网页端 WorkersView 顶部四个统计卡片同一份
  // 口径（在保排除待生效、待生效=pending+已通过但未生效的 active）；如果当前
  // 是从首页某个岗位卡片进来的（positionId 有值），人数只统计这个岗位范围内
  // 的，和页面标题"该岗位参保人员"的范围保持一致，不算全企业。
  buildStatusChips() {
    const positionId = this.data.positionId;
    const scoped = positionId ? this.data.items.filter((item) => item.position_id === positionId) : this.data.items;
    return this.data.statuses.map((s) => ({
      ...s,
      count: s.value ? scoped.filter((item) => item.status_display === s.value).length : scoped.length,
    }));
  },
  maskId(value) { const text = String(value || ''); return text.length > 10 ? `${text.slice(0, 6)}********${text.slice(-4)}` : text; },
  search(e) { this.setData({ q: e.detail.value }); this.applyFilter(); },
  chooseStatus(e) { this.setData({ status: e.currentTarget.dataset.value }); this.applyFilter(); },
  applyFilter() {
    const q = this.data.q.trim().toLowerCase(), status = this.data.status, positionId = this.data.positionId;
    const filtered = this.data.items.filter((item) => (!positionId || item.position_id === positionId) && (!status || item.status_display === status) && (!q || `${item.name}${item.phone}${item.id_number}${item.position_name}${item.actual_employer_name}`.toLowerCase().includes(q)));
    this.setData({ filtered, statusChips: this.buildStatusChips() });
  },
  clearPositionFilter() { this.setData({ positionId: 0, positionName: '' }); this.applyFilter(); },
  add() {
    const url = this.data.positionId ? `/pages/employee-edit/employee-edit?positionId=${this.data.positionId}` : '/pages/employee-edit/employee-edit';
    wx.navigateTo({ url });
  },
  detail(e) { wx.navigateTo({ url: `/pages/employee-detail/employee-detail?id=${e.currentTarget.dataset.id}` }); },
  rowTap(e) {
    if (this.data.selectMode) { this.toggleSelect(e); return; }
    this.detail(e);
  },
  importFile() { wx.navigateTo({ url: '/pages/import/import' }); },
  // 参保/停保批量操作：标题栏"参保"/"停保"进入勾选模式（不用先点进详情页
  // 逐个操作），和网页端 WorkersView 的批量参保/批量停保同一套接口——参保
  // 直接改状态，停保必须先选停保时间。
  startSelect(e) {
    const mode = e.currentTarget.dataset.mode;
    const stopDate = new Date(); stopDate.setDate(stopDate.getDate() + 1);
    this.setData({ selectMode: mode, selectedIds: [], stopDate: stopDate.toISOString().slice(0, 10) });
  },
  cancelSelect() { this.setData({ selectMode: '', selectedIds: [] }); },
  toggleSelect(e) {
    const id = Number(e.currentTarget.dataset.id);
    const selectedIds = this.data.selectedIds.includes(id) ? this.data.selectedIds.filter((x) => x !== id) : this.data.selectedIds.concat(id);
    this.setData({ selectedIds });
  },
  stopDateChange(e) { this.setData({ stopDate: e.detail.value }); },
  confirmSelect() {
    if (this.data.bulkSubmitting) return;
    const ids = this.data.selectedIds;
    if (!ids.length) { wx.showToast({ title: '请先勾选员工', icon: 'none' }); return; }
    if (this.data.selectMode === 'stopped') {
      if (!this.data.stopDate) { wx.showToast({ title: '请选择停保时间', icon: 'none' }); return; }
      wx.showModal({
        title: '批量停保确认', content: `将对选中的 ${ids.length} 名员工统一在 ${this.data.stopDate} 停保`,
        success: (res) => { if (res.confirm) this.runBulk(ids); }
      });
      return;
    }
    wx.showModal({
      title: '批量参保确认', content: `确定对选中的 ${ids.length} 名员工执行参保吗？`,
      success: (res) => { if (res.confirm) this.runBulk(ids); }
    });
  },
  runBulk(ids) {
    this.setData({ bulkSubmitting: true });
    const mode = this.data.selectMode;
    const requests = ids.map((id) => mode === 'stopped'
      ? app.request(`/insured/${id}`, { method: 'PATCH', data: { terminated_at: this.data.stopDate }, silent: true })
      : app.request(`/insured/${id}/status?status=active`, { method: 'PATCH', silent: true }));
    Promise.allSettled(requests).then((results) => {
      const failCount = results.filter((r) => r.status === 'rejected').length;
      const successCount = results.length - failCount;
      this.setData({ bulkSubmitting: false, selectMode: '', selectedIds: [] });
      wx.showToast({ title: `${mode === 'stopped' ? '停保' : '参保'}完成：成功 ${successCount} 人${failCount ? '，失败 ' + failCount + ' 人' : ''}`, icon: 'none', duration: 3000 });
      this.load();
    });
  },
  onShareAppMessage() { return app.share('/pages/employees/employees', 'from=share'); }
});
