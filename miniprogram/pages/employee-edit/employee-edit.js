const app = getApp();

Page({
  data: {
    id: 0,
    form: { name: '', id_number: '', phone: '', enterprise_id: 0, position_id: 0, effective_at: '', terminated_at: '' },
    originalEffectiveAt: '',
    originalTerminatedAt: '',
    effectiveTime: '00:00',
    terminatedTime: '00:00',
    plans: [],
    effectiveRuleText: '月单：最早为操作日次日 00:00',
    enterprises: [],
    allPositions: [],
    positions: [],
    enterpriseIndex: 0,
    positionIndex: 0,
    selectedPosition: null,
    saving: false,
    loading: true
  },
  onLoad(options) {
    const id = Number(options.id || 0); this.setData({ id });
    Promise.all([app.request('/enterprises'), app.request('/positions'), app.request('/plans'), id ? app.request('/insured') : Promise.resolve([])])
      .then(([enterprises, allPositions, plans, people]) => {
        const approved = allPositions.filter((item) => item.status === 'approved');
        const current = people.find((item) => item.id === id);
        const enterpriseId = current ? current.enterprise_id : (enterprises[0] && enterprises[0].id) || 0;
        const positions = approved.filter((item) => item.enterprise_id === enterpriseId);
        const positionIndex = Math.max(0, positions.findIndex((item) => current && item.id === current.position_id));
        const enterpriseIndex = Math.max(0, enterprises.findIndex((item) => item.id === enterpriseId));
        const effectiveAt = current && current.effective_at ? current.effective_at.slice(0, 10) : '';
        const terminatedAt = current && current.terminated_at ? current.terminated_at.slice(0, 10) : '';
        const effectiveTime = current && current.effective_at && current.effective_at.length >= 16 ? current.effective_at.slice(11, 16) : '00:00';
        const terminatedTime = current && current.terminated_at && current.terminated_at.length >= 16 ? current.terminated_at.slice(11, 16) : '00:00';
        const selectedPosition = positions[positionIndex] || null;
        const selectedPlan = plans.find((item) => selectedPosition && item.id === selectedPosition.plan_id);
        const form = current
          ? { name: current.name, id_number: current.id_number, phone: current.phone || '', enterprise_id: current.enterprise_id, position_id: current.position_id || 0, effective_at: effectiveAt, terminated_at: terminatedAt }
          : { ...this.data.form, enterprise_id: enterpriseId, position_id: (positions[0] && positions[0].id) || 0 };
        this.setData({ enterprises, allPositions: approved, plans, positions, enterpriseIndex, positionIndex, selectedPosition, form, effectiveTime, terminatedTime, originalEffectiveAt: effectiveAt ? `${effectiveAt}T${effectiveTime}:00` : '', originalTerminatedAt: terminatedAt ? `${terminatedAt}T${terminatedTime}:00` : '', effectiveRuleText: selectedPlan && selectedPlan.effective_mode === 'immediate' ? '即时单：最早为操作时间后 1 小时' : '月单：最早为操作日次日 00:00', loading: false });
      }).catch(() => this.setData({ loading: false }));
  },
  input(e) { this.setData({ [`form.${e.currentTarget.dataset.key}`]: e.detail.value }); },
  dateChange(e) { this.setData({ [`form.${e.currentTarget.dataset.key}`]: e.detail.value }); },
  timeChange(e) { this.setData({ [e.currentTarget.dataset.key]: e.detail.value }); },
  enterpriseChange(e) {
    const enterpriseIndex = Number(e.detail.value), enterprise = this.data.enterprises[enterpriseIndex];
    const positions = this.data.allPositions.filter((item) => item.enterprise_id === enterprise.id);
    const selectedPosition = positions[0] || null, selectedPlan = this.data.plans.find((item) => selectedPosition && item.id === selectedPosition.plan_id);
    this.setData({ enterpriseIndex, positions, positionIndex: 0, selectedPosition, effectiveRuleText: selectedPlan && selectedPlan.effective_mode === 'immediate' ? '即时单：最早为操作时间后 1 小时' : '月单：最早为操作日次日 00:00', 'form.enterprise_id': enterprise.id, 'form.position_id': (positions[0] && positions[0].id) || 0 });
  },
  positionChange(e) {
    const positionIndex = Number(e.detail.value), position = this.data.positions[positionIndex];
    const plan = this.data.plans.find((item) => position && item.id === position.plan_id);
    this.setData({ positionIndex, selectedPosition: position || null, effectiveRuleText: plan && plan.effective_mode === 'immediate' ? '即时单：最早为操作时间后 1 小时' : '月单：最早为操作日次日 00:00', 'form.position_id': (position && position.id) || 0 });
  },
  save() {
    const form = this.data.form;
    if (!form.name.trim() || !form.id_number.trim()) { wx.showToast({ title: '姓名和身份证号必填', icon: 'none' }); return; }
    if (!form.position_id) { wx.showToast({ title: '暂无审核通过的可投保岗位', icon: 'none' }); return; }
    this.setData({ saving: true });
    const payload = { ...form, name: form.name.trim(), id_number: form.id_number.trim(), phone: form.phone.trim() };
    const effectiveAt = payload.effective_at ? `${payload.effective_at}T${this.data.effectiveTime}:00` : '';
    const terminatedAt = payload.terminated_at ? `${payload.terminated_at}T${this.data.terminatedTime}:00` : '';
    let request;
    if (this.data.id) {
      const data = { name: payload.name, id_number: payload.id_number, phone: payload.phone, position_id: payload.position_id };
      // 生效时间/停保时间为空表示"不修改"；只有当值发生变化时才带上，避免用空值误清空后端记录
      if (effectiveAt && effectiveAt !== this.data.originalEffectiveAt) data.effective_at = effectiveAt;
      if (terminatedAt && terminatedAt !== this.data.originalTerminatedAt) data.terminated_at = terminatedAt;
      request = app.request(`/insured/${this.data.id}`, { method: 'PATCH', data });
    } else {
      const data = { name: payload.name, id_number: payload.id_number, phone: payload.phone, enterprise_id: payload.enterprise_id, position_id: payload.position_id };
      if (effectiveAt) data.effective_at = effectiveAt;
      if (terminatedAt) data.terminated_at = terminatedAt;
      request = app.request('/insured', { method: 'POST', data });
    }
    request.then(() => { wx.showToast({ title: this.data.id ? '已保存' : '已提交审核' }); setTimeout(() => wx.navigateBack(), 500); }).catch(() => this.setData({ saving: false }));
  }
});
