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
    // 批量停保：点悬浮"－"按钮进入勾选模式，和网页端 WorkersView 的"批量停保"
    // 同一套接口，只是把网页表格多选换成这里逐条 checkbox。必须先选停保时间，
    // 不能一键立即停保——和网页端同一条限制。
    selectMode: '', // '' | 'stopped'
    selectedIds: [],
    stopDate: '',
    minStopDate: '',
    bulkSubmitting: false
  },
  onLoad(options) {
    if (options && options.status) this.setData({ status: options.status });
    if (options && options.position_id) this.setData({ positionId: Number(options.position_id) });
  },
  // 未登录时不拦截浏览——页面正常渲染（搜索框、筛选栏、悬浮按钮都在），
  // 只是列表是空的；只有真正点了会触发接口调用的操作（参保/停保/查看
  // 详情/导入）才跳登录页。返回 false 时调用方要 return，不再往下执行。
  requireLogin() {
    if (app.globalData.token) return true;
    wx.navigateTo({ url: '/pages/login/login' });
    return false;
  },
  onShow() {
    if (!app.globalData.token) { this.setData({ loading: false }); return; }
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
        return {
          ...item, initial: String(item.name || '员').slice(0, 1), status_label: pendingBucket ? '待生效' : app.statusText(item.status), status_display: pendingBucket ? 'pending' : item.status, id_masked: this.maskId(item.id_number),
          // 参保时间（保险生效时间）：和详情页 formatCoverageDate() 同一个
          // 格式化规则，只要有 effective_at 就带出来，不区分状态——待生效
          // 的人尤其需要看到"什么时候生效"，不用点进详情才知道。
          effective_at_display: item.effective_at ? app.formatCoverageDate(item.effective_at, item.effective_mode) : '',
          // 已停保的人在列表里之前只有一个灰色状态标签，看不出具体哪天停的，
          // 要点进详情才知道——审计"停保相关显示"时发现的缺口，这里直接把
          // 停保时间带出来，和详情页 formatCoverageDate() 同一个格式化规则。
          terminated_at_display: item.status === 'stopped' ? app.formatCoverageDate(item.terminated_at, item.effective_mode) : ''
        };
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
  // 导出的是当前筛选结果（搜索关键字 + 状态 chip + 岗位范围都已经在
  // filtered 里体现），把这批人的 id 传给后端，导出内容就和屏幕上看到的
  // 保持一致，不用在后端重新实现一遍这几个筛选条件。
  exportList() {
    if (!this.requireLogin()) return;
    if (!this.data.filtered.length) { wx.showToast({ title: '没有可导出的记录', icon: 'none' }); return; }
    const ids = this.data.filtered.map((item) => item.id).join(',');
    app.downloadAndOpen(`/insured/export?ids=${ids}`, { filename: '参保员工导出.xlsx', fileType: 'xlsx', loadingTitle: '正在导出' }).catch(() => {});
  },
  add() {
    if (!this.requireLogin()) return;
    const url = this.data.positionId ? `/pages/employee-edit/employee-edit?positionId=${this.data.positionId}` : '/pages/employee-edit/employee-edit';
    wx.navigateTo({ url });
  },
  detail(e) {
    if (!this.requireLogin()) return;
    wx.navigateTo({ url: `/pages/employee-detail/employee-detail?id=${e.currentTarget.dataset.id}` });
  },
  rowTap(e) {
    if (this.data.selectMode) { this.toggleSelect(e); return; }
    this.detail(e);
  },
  importFile() {
    if (!this.requireLogin()) return;
    wx.navigateTo({ url: '/pages/import/import' });
  },
  // 批量停保：悬浮"－"按钮进入勾选模式（不用先点进详情页逐个操作），和
  // 网页端 WorkersView 的批量停保同一套接口。
  startSelect() {
    if (!this.requireLogin()) return;
    const stopDate = new Date(); stopDate.setDate(stopDate.getDate() + 1);
    const minStopDate = stopDate.toISOString().slice(0, 10);
    // 停保只对"在保"的人有意义，进勾选模式时把状态筛选默认切到"在保"，
    // 列表直接收窄成可选范围，不用用户自己再点一次筛选 chip。
    this.setData({ selectMode: 'stopped', selectedIds: [], stopDate: minStopDate, minStopDate, status: 'active' });
    this.applyFilter();
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
    if (!this.data.stopDate) { wx.showToast({ title: '请选择停保时间', icon: 'none' }); return; }
    wx.showModal({
      title: '批量停保确认', content: `将对选中的 ${ids.length} 名员工统一在 ${this.data.stopDate} 停保`,
      success: (res) => { if (res.confirm) this.runBulk(ids); }
    });
  },
  runBulk(ids) {
    this.setData({ bulkSubmitting: true });
    const requests = ids.map((id) => app.request(`/insured/${id}`, { method: 'PATCH', data: { terminated_at: this.data.stopDate }, silent: true }));
    Promise.allSettled(requests).then((results) => {
      const failed = results.filter((r) => r.status === 'rejected');
      const successCount = results.length - failed.length;
      this.setData({ bulkSubmitting: false, selectMode: '', selectedIds: [] });
      // 批量请求用 silent:true 不会自动弹错误 toast，之前失败了只显示一个
      // 冷冰冰的数字，用户不知道为什么——比如停保时间选早了（后端要求最早
      // 次日 00:00），带上第一条失败的具体原因，比"失败 N 人"有用得多。
      const firstError = failed.length ? (failed[0].reason && failed[0].reason.message) : '';
      const suffix = failed.length ? `，失败 ${failed.length} 人${firstError ? '（' + firstError + '）' : ''}` : '';
      wx.showToast({ title: `停保完成：成功 ${successCount} 人${suffix}`, icon: 'none', duration: failed.length ? 4500 : 3000 });
      this.load();
    });
  },
  onShareAppMessage() { return app.share('/pages/employees/employees', 'from=share'); }
});
