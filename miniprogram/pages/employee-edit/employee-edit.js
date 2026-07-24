const app = getApp();

Page({
  data: {
    id: 0,
    form: { name: '', id_number: '', phone: '', enterprise_id: 0, position_id: 0, effective_at: '', terminated_at: '' },
    originalEffectiveAt: '',
    originalTerminatedAt: '',
    minTerminatedDate: '',
    effectiveTime: '00:00',
    terminatedTime: '00:00',
    plans: [],
    effectiveRuleText: '月单：最早为操作日次日 00:00',
    isDailyBilling: false,
    dailyMode: 'temporary',
    enterprises: [],
    allPositions: [],
    employers: [],
    employerIndex: 0,
    positions: [],
    enterpriseIndex: 0,
    positionIndex: 0,
    selectedPosition: null,
    saving: false,
    idNumberInvalid: false,
    loading: true,
    // 连续添加：第一次新增成功后归属信息（投保单位/实际用工单位/岗位）锁定，
    // 不返回列表页，可以连续手工填写或连续拍照 OCR 添加，直到点"完成"。
    locked: false,
    addedCount: 0,
    // 新增模式只在 !id 时出现三个入口：手工添加/拍照添加/批量导入。批量导入
    // 是完全独立的另一个页面（自己管投保单位/岗位选择，不依赖这里的 form），
    // 点它直接跳转，不算这里的一种"模式"；手工/拍照才是本页内切换显示区域。
    addMode: 'manual',
    // 拍照添加：一次选多张身份证照片（最多 9 张），逐张 OCR 后堆到这个待确认
    // 列表里，人工核对/改错后一次性批量提交，取代了之前的单张拍照识别。
    batchItems: [],
    batchScanning: false,
    batchSubmitting: false,
    batchKeySeq: 0,
    // 生效/停保时间收进可展开的"更多选项"，onLoad 里按新增/编辑重新设默认值。
    moreOptionsExpanded: false
  },
  // 7.15-3：先选实际用工单位，再选岗位（岗位名会跨单位重复）。按投保单位下的
  // 已审核岗位聚合出实际用工单位列表，再按所选单位过滤岗位。
  buildScope(approved, enterpriseId, employerId, positionId) {
    const inEnterprise = approved.filter((p) => p.enterprise_id === enterpriseId);
    const seen = {};
    const employers = [];
    inEnterprise.forEach((p) => {
      const eid = p.actual_employer_id || 0;
      if (!seen[eid]) { seen[eid] = true; employers.push({ id: eid, name: p.actual_employer_name || '未指定用工单位' }); }
    });
    let employerIndex = Math.max(0, employers.findIndex((e) => e.id === employerId));
    const curEmployerId = (employers[employerIndex] && employers[employerIndex].id) || 0;
    const positions = inEnterprise.filter((p) => (p.actual_employer_id || 0) === curEmployerId);
    let positionIndex = Math.max(0, positions.findIndex((p) => p.id === positionId));
    return { employers, employerIndex, positions, positionIndex, selectedPosition: positions[positionIndex] || null };
  },
  planText(position) {
    const plan = this.data.plans.find((item) => position && item.id === position.plan_id);
    return {
      effectiveRuleText: plan && plan.effective_mode === 'immediate' ? '即时单：最早为操作时间后 1 小时' : '月单：最早为操作日次日 00:00',
      isDailyBilling: !!(plan && plan.billing_mode === 'daily'),
    };
  },
  onLoad(options) {
    const id = Number(options.id || 0); const presetPositionId = Number(options.positionId || 0);
    // 停保时间选择器的最早可选日——后端硬性要求"最早操作日次日 00:00"（月单）
    // 或"生效时间往后 24 小时"（日结即时生效），这里用较宽松的那条（次日）
    // 兜底，提前拦掉最常见的"选今天/选过去"这类必然被后端拒绝的输入；后端
    // 仍然是最终权威，真正精确的规则（比如按生效时间算的 24 小时）还是由
    // 提交时的校验兜底。
    const minTerminatedDate = new Date(); minTerminatedDate.setDate(minTerminatedDate.getDate() + 1);
    this.setData({ id, minTerminatedDate: minTerminatedDate.toISOString().slice(0, 10) });
    Promise.all([app.request('/enterprises'), app.request('/positions'), app.request('/plans'), id ? app.request('/insured') : Promise.resolve([])])
      .then(([enterprises, allPositions, plans, people]) => {
        const approved = allPositions.filter((item) => item.status === 'approved');
        const current = people.find((item) => item.id === id);
        // 从「方案管理」列表点"去投保"直接进来：岗位已经定好了，投保单位/实际用工单位/岗位
        // 三级选择直接跳过、立刻锁定，不用再选一遍（保经云 7-23 反馈，参考竞品首页的
        // "今日投保(点击加减人)"体验）。
        const presetPos = presetPositionId ? approved.find((p) => p.id === presetPositionId) : null;
        const currentPos = current ? approved.find((p) => p.id === current.position_id) : presetPos;
        const enterpriseId = current ? current.enterprise_id : (presetPos ? presetPos.enterprise_id : (enterprises[0] && enterprises[0].id) || 0);
        const employerId = currentPos ? (currentPos.actual_employer_id || 0) : 0;
        const scope = this.buildScope(approved, enterpriseId, employerId, current ? current.position_id : (presetPos ? presetPos.id : 0));
        const enterpriseIndex = Math.max(0, enterprises.findIndex((item) => item.id === enterpriseId));
        const effectiveAt = current && current.effective_at ? current.effective_at.slice(0, 10) : '';
        const terminatedAt = current && current.terminated_at ? current.terminated_at.slice(0, 10) : '';
        const effectiveTime = current && current.effective_at && current.effective_at.length >= 16 ? current.effective_at.slice(11, 16) : '00:00';
        const terminatedTime = current && current.terminated_at && current.terminated_at.length >= 16 ? current.terminated_at.slice(11, 16) : '00:00';
        this.setData({ enterprises, allPositions: approved, plans, ...scope, enterpriseIndex,
          locked: !!presetPos,
          // 编辑已有记录时生效/停保时间是常改的字段，默认展开；新增时大多数场景
          // 用不到，默认收起，减少视觉干扰。
          moreOptionsExpanded: !!id,
          form: current
            ? { name: current.name, id_number: current.id_number, phone: current.phone || '', enterprise_id: current.enterprise_id, position_id: current.position_id || 0, effective_at: effectiveAt, terminated_at: terminatedAt }
            : { ...this.data.form, enterprise_id: enterpriseId, position_id: (scope.selectedPosition && scope.selectedPosition.id) || 0 },
          effectiveTime, terminatedTime, originalEffectiveAt: effectiveAt ? `${effectiveAt}T${effectiveTime}:00` : '', originalTerminatedAt: terminatedAt ? `${terminatedAt}T${terminatedTime}:00` : '',
          ...this.planText(scope.selectedPosition), loading: false });
      }).catch(() => this.setData({ loading: false }));
  },
  input(e) {
    const key = e.currentTarget.dataset.key, value = e.detail.value;
    const patch = { [`form.${key}`]: value };
    if (key === 'id_number') patch.idNumberInvalid = !!value.trim() && !this.isValidIdNumber(value);
    this.setData(patch);
  },
  // 与 backend/core/id_number.py 的 is_valid_id_number 同一套 GB 11643 校验位算法，
  // 手工输入和 OCR 识别都实时校验，不用等提交才发现号码打错/拍错。
  isValidIdNumber(value) {
    const v = String(value || '').trim().toUpperCase();
    if (!/^\d{17}[\dX]$/.test(v)) return false;
    const y = +v.slice(6, 10), m = +v.slice(10, 12), d = +v.slice(12, 14);
    const birth = new Date(y, m - 1, d);
    if (birth.getFullYear() !== y || birth.getMonth() !== m - 1 || birth.getDate() !== d) return false;
    if (birth.getTime() > Date.now()) return false;
    const weights = [7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2];
    const checkCodes = '10X98765432';
    let total = 0;
    for (let i = 0; i < 17; i++) total += Number(v[i]) * weights[i];
    return checkCodes[total % 11] === v[17];
  },
  // 三个新增入口的切换：手工/拍照是本页内两块区域的显示切换；批量导入是完全
  // 独立的另一个页面，直接跳转，不停留在这个 tab 上（不然用户会以为点错了，
  // 页面看起来没反应）。
  setAddMode(e) {
    const mode = e.currentTarget.dataset.mode;
    if (mode === 'import') { wx.navigateTo({ url: '/pages/import/import' }); return; }
    this.setData({ addMode: mode });
  },
  // 一次选多张身份证照片，逐张调用同一个 OCR 接口，识别结果堆进 batchItems
  // 待人工核对/改错，不直接写进 form/提交——姓名或身份证号识别错了，先在这
  // 个列表里改，避免脏数据直接进参保申请。
  batchScan() {
    if (this.data.batchScanning) return;
    wx.chooseMedia({
      count: 9, mediaType: ['image'], sourceType: ['camera', 'album'], sizeType: ['compressed'],
      success: (res) => {
        this.setData({ batchScanning: true });
        const uploadOne = (filePath) => new Promise((resolve) => {
          wx.uploadFile({
            url: `${app.globalData.apiBase}/ocr/id-card`,
            filePath, name: 'file',
            header: { Authorization: `Bearer ${app.globalData.token}` },
            success: (up) => {
              let data = {};
              try { data = JSON.parse(up.data || '{}'); } catch (e) { data = {}; }
              resolve(up.statusCode === 200 ? { name: data.name || '', id_number: data.id_number || '' } : { name: '', id_number: '' });
            },
            fail: () => resolve({ name: '', id_number: '' })
          });
        });
        Promise.all(res.tempFiles.map((file) => uploadOne(file.tempFilePath))).then((results) => {
          let keySeq = this.data.batchKeySeq;
          const items = results.map((r) => {
            keySeq += 1;
            return { key: keySeq, name: r.name, id_number: r.id_number, invalid: !!r.id_number && !this.isValidIdNumber(r.id_number) };
          });
          this.setData({ batchItems: this.data.batchItems.concat(items), batchKeySeq: keySeq, batchScanning: false });
          wx.showToast({ title: `已识别 ${items.length} 张，请核对后提交`, icon: 'none', duration: 2200 });
        });
      },
      fail: () => {}
    });
  },
  batchInput(e) {
    const index = Number(e.currentTarget.dataset.index), key = e.currentTarget.dataset.key, value = e.detail.value;
    const items = this.data.batchItems.slice();
    items[index] = { ...items[index], [key]: value };
    if (key === 'id_number') items[index].invalid = !!value && !this.isValidIdNumber(value);
    this.setData({ batchItems: items });
  },
  batchRemove(e) {
    const index = Number(e.currentTarget.dataset.index);
    const items = this.data.batchItems.slice();
    items.splice(index, 1);
    this.setData({ batchItems: items });
  },
  batchClear() { this.setData({ batchItems: [] }); },
  // 批量提交复用当前已锁定/已选好的投保单位+岗位（和单人新增同一份 form.
  // enterprise_id/position_id），不支持临时日结/自定义生效停保时间——那两个
  // 是单人添加里的细粒度选项，OCR 批量场景默认走最简单的"直接参保"路径。
  submitBatch() {
    if (this.data.batchSubmitting) return;
    if (!this.data.form.position_id) { wx.showToast({ title: '暂无审核通过的可投保岗位', icon: 'none' }); return; }
    const items = this.data.batchItems;
    if (!items.length) return;
    const badIndex = items.findIndex((item) => !item.name.trim() || !item.id_number.trim() || item.invalid);
    if (badIndex !== -1) { wx.showToast({ title: `第 ${badIndex + 1} 行姓名/身份证号有问题，请先修正`, icon: 'none' }); return; }
    this.setData({ batchSubmitting: true });
    const enterpriseId = this.data.form.enterprise_id, positionId = this.data.form.position_id;
    let successCount = 0, failCount = 0;
    const submitNext = (index) => {
      if (index >= items.length) {
        this.setData({ batchSubmitting: false, batchItems: [], locked: true, addedCount: this.data.addedCount + successCount });
        wx.showToast({ title: `批量添加完成：成功 ${successCount} 人${failCount ? '，失败 ' + failCount + ' 人' : ''}`, icon: 'none', duration: 3000 });
        return;
      }
      const item = items[index];
      app.request('/insured', { method: 'POST', silent: true, data: { name: item.name.trim(), id_number: item.id_number.trim(), phone: '', enterprise_id: enterpriseId, position_id: positionId } })
        .then(() => { successCount += 1; submitNext(index + 1); })
        .catch(() => { failCount += 1; submitNext(index + 1); });
    };
    submitNext(0);
  },
  dateChange(e) { this.setData({ [`form.${e.currentTarget.dataset.key}`]: e.detail.value }); },
  timeChange(e) { this.setData({ [e.currentTarget.dataset.key]: e.detail.value }); },
  enterpriseChange(e) {
    if (this.data.locked) return;
    const enterpriseIndex = Number(e.detail.value), enterprise = this.data.enterprises[enterpriseIndex];
    const scope = this.buildScope(this.data.allPositions, enterprise.id, 0, 0);
    this.setData({ enterpriseIndex, ...scope, ...this.planText(scope.selectedPosition), 'form.enterprise_id': enterprise.id, 'form.position_id': (scope.selectedPosition && scope.selectedPosition.id) || 0 });
  },
  employerChange(e) {
    if (this.data.locked) return;
    const employerIndex = Number(e.detail.value), employer = this.data.employers[employerIndex];
    const scope = this.buildScope(this.data.allPositions, this.data.form.enterprise_id, employer.id, 0);
    this.setData({ ...scope, ...this.planText(scope.selectedPosition), 'form.position_id': (scope.selectedPosition && scope.selectedPosition.id) || 0 });
  },
  positionChange(e) {
    if (this.data.locked) return;
    const positionIndex = Number(e.detail.value), position = this.data.positions[positionIndex];
    this.setData({ positionIndex, selectedPosition: position || null, ...this.planText(position), 'form.position_id': (position && position.id) || 0 });
  },
  dailyModeChange(e) { this.setData({ dailyMode: e.currentTarget.dataset.value }); },
  toggleMoreOptions() { this.setData({ moreOptionsExpanded: !this.data.moreOptionsExpanded }); },
  ageFromId(id) {
    const v = String(id || '').trim();
    if (!/^\d{17}[\dXx]$/.test(v)) return null;
    const y = +v.slice(6, 10), m = +v.slice(10, 12), d = +v.slice(12, 14);
    const now = new Date();
    let age = now.getFullYear() - y;
    if (now.getMonth() + 1 < m || (now.getMonth() + 1 === m && now.getDate() < d)) age -= 1;
    return age;
  },
  save() {
    const form = this.data.form;
    if (!form.name.trim() || !form.id_number.trim()) { wx.showToast({ title: '姓名和身份证号必填', icon: 'none' }); return; }
    if (!this.isValidIdNumber(form.id_number)) { wx.showToast({ title: '身份证号校验位不正确，请核对', icon: 'none' }); return; }
    const age = this.ageFromId(form.id_number);
    if (age !== null && age < 16) { wx.showToast({ title: '被保险人未满16周岁，不可参保', icon: 'none' }); return; }
    if (!form.position_id) { wx.showToast({ title: '暂无审核通过的可投保岗位', icon: 'none' }); return; }
    this.setData({ saving: true });
    const payload = { ...form, name: form.name.trim(), id_number: form.id_number.trim(), phone: form.phone.trim() };
    const effectiveAt = payload.effective_at ? `${payload.effective_at}T${this.data.effectiveTime}:00` : '';
    const terminatedAt = payload.terminated_at ? `${payload.terminated_at}T${this.data.terminatedTime}:00` : '';
    const useTemporaryDaily = !this.data.id && this.data.isDailyBilling && this.data.dailyMode === 'temporary';
    let request;
    if (this.data.id) {
      const data = { name: payload.name, id_number: payload.id_number, phone: payload.phone, position_id: payload.position_id };
      // 生效时间/停保时间为空表示"不修改"；只有当值发生变化时才带上，避免用空值误清空后端记录
      if (effectiveAt && effectiveAt !== this.data.originalEffectiveAt) data.effective_at = effectiveAt;
      if (terminatedAt && terminatedAt !== this.data.originalTerminatedAt) data.terminated_at = terminatedAt;
      request = app.request(`/insured/${this.data.id}`, { method: 'PATCH', data });
    } else if (useTemporaryDaily) {
      // 临时日结：不预先算日期，创建后依次调用"参保"（生效时间取服务端默认
      // 的"参保时间本身"）和"停保"（默认停保时间=生效时间+24小时），两步
      // 都复用后端已经校正过的默认规则，避免小程序端时钟和服务端不一致。
      const data = { name: payload.name, id_number: payload.id_number, phone: payload.phone, enterprise_id: payload.enterprise_id, position_id: payload.position_id };
      request = app.request('/insured', { method: 'POST', data })
        .then((created) => app.request(`/insured/${created.id}/status?status=active`, { method: 'PATCH' }))
        .then((activated) => app.request(`/insured/${activated.id}/status?status=stopped`, { method: 'PATCH' }));
    } else {
      const data = { name: payload.name, id_number: payload.id_number, phone: payload.phone, enterprise_id: payload.enterprise_id, position_id: payload.position_id };
      if (effectiveAt) data.effective_at = effectiveAt;
      if (terminatedAt) data.terminated_at = terminatedAt;
      request = app.request('/insured', { method: 'POST', data });
    }
    request.then(() => {
      if (this.data.id) {
        wx.showToast({ title: '已保存' });
        setTimeout(() => wx.navigateBack(), 500);
        return;
      }
      // 新增模式：不返回列表，归属信息锁定，清空本人信息，留在本页连续添加下一人。
      const addedCount = this.data.addedCount + 1;
      this.setData({
        saving: false,
        locked: true,
        addedCount,
        idNumberInvalid: false,
        'form.name': '', 'form.id_number': '', 'form.phone': '', 'form.effective_at': '', 'form.terminated_at': '',
        effectiveTime: '00:00', terminatedTime: '00:00', originalEffectiveAt: '', originalTerminatedAt: ''
      });
      wx.showToast({ title: `已添加第 ${addedCount} 人，可继续添加`, icon: 'none', duration: 2200 });
    }).catch(() => this.setData({ saving: false }));
  },
  finish() {
    wx.navigateBack();
  }
});
