const app = getApp();

Page({
  data: {
    loading: true,
    totalActiveCount: 0,
    totalPendingCount: 0,
    user: {},
    enterprise: {},
    greeting: '你好',
    today: '',
    positionCards: [],
    filteredPositionCards: [],
    positionSearchVisible: false,
    positionSearchQuery: ''
  },
  // 小程序必须先打开首页，登录是进入后的授权行为，不允许首次打开就强制
  // 拦截跳登录页——未登录时首页照常渲染（看板、参保方案卡片都在），只是
  // 没有数据，不请求任何需要鉴权的接口；真正会调接口的操作（去充值、
  // 查名单、新增参保/岗位）各自调 requireLogin() 拦截。
  requireLogin() {
    if (app.globalData.token) return true;
    wx.navigateTo({ url: '/pages/login/login' });
    return false;
  },
  onShow() {
    if (!app.globalData.token) { this.setData({ loading: false }); return; }
    this.load();
  },
  onPullDownRefresh() {
    if (!app.globalData.token) { wx.stopPullDownRefresh(); return; }
    this.load().finally(() => wx.stopPullDownRefresh());
  },
  // 每张卡片对应一个已审核通过（status==='approved'）的岗位——职业类别、关联
  // 方案在审核时已经定好，这里只负责把三份已有接口的数据拼到一起，不新增
  // 后端字段。在保/待生效人数按 position_id 对 /insured 全量列表分组统计，
  // 已停保的人不计入。之前把两者合并成一个数直接加总，口径本身就漏了一种
  // 情况：员工 status 是 'active' 但 effective_at 还没到（月单最早次日生
  // 效），这批人其实也该算"待生效"——和 pages/employees/employees.js 的
  // isPendingEffective() 判断口径保持一致，不能只看 status==='pending'。
  isPendingEffective(person) {
    return person.status === 'active' && person.effective_at && new Date(person.effective_at) > new Date();
  },
  buildPositionCards(positions, plans, people) {
    const planById = new Map(plans.map((plan) => [plan.id, plan]));
    const positionById = new Map(positions.map((position) => [position.id, position]));
    const activeByPosition = new Map();
    const pendingByPosition = new Map();
    people.forEach((person) => {
      if (!person.position_id) return;
      const pending = person.status === 'pending' || this.isPendingEffective(person);
      if (pending) {
        pendingByPosition.set(person.position_id, (pendingByPosition.get(person.position_id) || 0) + 1);
      } else if (person.status === 'active') {
        activeByPosition.set(person.position_id, (activeByPosition.get(person.position_id) || 0) + 1);
      }
    });
    // 卡片默认只展示已审核通过（status==='approved'）的岗位——那才是真正
    // 能继续新增/减保的入口。但如果某个岗位当前有在保/待生效的人（哪怕岗位
    // 后来因为编辑被打回待审核，生产数据里已经发现过这种情况），这些人依
    // 然是真实在保人数，不能因为过滤掉了岗位卡片就从"在保人数"里凭空消
    // 失——不然几张卡片加起来会比企业实际在保总人数少。这类岗位也一起展示。
    const positionIds = new Set(positions.filter((position) => position.status === 'approved').map((position) => position.id));
    activeByPosition.forEach((_count, id) => positionIds.add(id));
    pendingByPosition.forEach((_count, id) => positionIds.add(id));
    return Array.from(positionIds)
      .map((id) => positionById.get(id))
      .filter(Boolean)
      .map((position) => {
        const plan = position.plan_id ? planById.get(position.plan_id) : null;
        const priceText = plan ? `${plan.insurer} · ${plan.name}` : '尚未关联保司产品';
        return {
          id: position.id,
          name: position.name,
          actual_employer_name: position.actual_employer_name || '',
          occupation_class: position.occupation_class || '待定',
          plan_text: priceText,
          active_count: activeByPosition.get(position.id) || 0,
          pending_count: pendingByPosition.get(position.id) || 0
        };
      });
  },
  load() {
    const hour = new Date().getHours();
    const greeting = hour < 6 ? '夜深了' : hour < 12 ? '上午好' : hour < 18 ? '下午好' : '晚上好';
    const today = new Date().toLocaleDateString('zh-CN', { month: 'long', day: 'numeric', weekday: 'long' });
    this.setData({ loading: true, greeting, today });
    return Promise.all([
      app.loadProfile(),
      app.request('/positions', { silent: true }),
      app.request('/plans', { silent: true }),
      app.request('/insured', { silent: true })
    ])
      .then(([user, positions, plans, people]) => {
        const positionCards = this.buildPositionCards(positions || [], plans || [], people || []);
        // 首页看板"在保人数"/"待生效人数"是全公司口径，不分岗位——直接把
        // 每张岗位卡片已经算好的 active_count/pending_count 加总，不用另外
        // 请求 /dashboard 或重新遍历一遍 people（岗位卡片的口径已经包含了
        // "岗位被打回待审核但仍有在保/待生效人员"这类边界情况，见
        // buildPositionCards 里的说明，加总天然也是对的）。
        const totalActiveCount = positionCards.reduce((sum, item) => sum + item.active_count, 0);
        const totalPendingCount = positionCards.reduce((sum, item) => sum + item.pending_count, 0);
        this.setData({ user, enterprise: app.globalData.enterprise || {}, positionCards, totalActiveCount, totalPendingCount, loading: false });
        this.applyPositionSearch();
      })
      .catch((error) => { this.setData({ loading: false }); wx.showToast({ title: error.message, icon: 'none' }); });
  },
  // 按企业（用工单位）、保险（保司/产品）、岗位名称三个字段做本地过滤，数据
  // 已经在 load() 里一次性取回，不需要额外请求接口。
  applyPositionSearch() {
    const q = (this.data.positionSearchQuery || '').trim().toLowerCase();
    const filteredPositionCards = q
      ? this.data.positionCards.filter((item) => `${item.name}${item.actual_employer_name}${item.plan_text}`.toLowerCase().includes(q))
      : this.data.positionCards;
    this.setData({ filteredPositionCards });
  },
  togglePositionSearch() {
    const positionSearchVisible = !this.data.positionSearchVisible;
    this.setData({ positionSearchVisible, positionSearchQuery: '' });
    this.applyPositionSearch();
  },
  searchPositions(e) {
    this.setData({ positionSearchQuery: e.detail.value });
    this.applyPositionSearch();
  },
  // 保费/服务费余额卡片（含去充值跳转）搬到"我的"页面了，首页看板换成
  // 在保/待生效人数，点击直接跳到员工列表按对应状态筛选好——不带 position_id
  // （0 表示不限岗位），和 goPosition 用同一套 pendingEmployeesFilter 中转。
  goEmployeesByStatus(e) {
    if (!this.requireLogin()) return;
    app.globalData.pendingEmployeesFilter = { status: e.currentTarget.dataset.status, position_id: 0 };
    wx.switchTab({ url: '/pages/employees/employees' });
  },
  goPosition(e) {
    if (!this.requireLogin()) return;
    app.globalData.pendingEmployeesFilter = { status: '', position_id: Number(e.currentTarget.dataset.id) };
    wx.switchTab({ url: '/pages/employees/employees' });
  },
  // 直接跳到新增参保表单，岗位归属信息预锁定；employee-edit.js 已经支持连续
  // 添加（第一人提交成功后不返回、清空个人信息、留在原页面继续填下一人），
  // 不用像 goPosition 那样先经过参保人员列表这一层。employee-edit 不是
  // tabBar 页面，用 wx.navigateTo 没问题。
  addEnroll(e) {
    if (!this.requireLogin()) return;
    wx.navigateTo({ url: `/pages/employee-edit/employee-edit?positionId=${e.currentTarget.dataset.id}` });
  },
  addPosition() {
    if (!this.requireLogin()) return;
    wx.navigateTo({ url: '/pages/position-edit/position-edit' });
  },
  onShareAppMessage() { return app.share('/pages/home/home', 'from=share'); }
});
