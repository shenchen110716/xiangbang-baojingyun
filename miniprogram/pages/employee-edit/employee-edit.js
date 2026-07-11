const app = getApp();

Page({
  data: {
    id: 0,
    form: { name: '', id_number: '', phone: '', enterprise_id: 0, position_id: 0 },
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
    Promise.all([app.request('/enterprises'), app.request('/positions'), id ? app.request('/insured') : Promise.resolve([])])
      .then(([enterprises, allPositions, people]) => {
        const approved = allPositions.filter((item) => item.status === 'approved');
        const current = people.find((item) => item.id === id);
        const enterpriseId = current ? current.enterprise_id : (enterprises[0] && enterprises[0].id) || 0;
        const positions = approved.filter((item) => item.enterprise_id === enterpriseId);
        const positionIndex = Math.max(0, positions.findIndex((item) => current && item.id === current.position_id));
        const enterpriseIndex = Math.max(0, enterprises.findIndex((item) => item.id === enterpriseId));
        const form = current ? { name: current.name, id_number: current.id_number, phone: current.phone || '', enterprise_id: current.enterprise_id, position_id: current.position_id || 0 } : { ...this.data.form, enterprise_id: enterpriseId, position_id: (positions[0] && positions[0].id) || 0 };
        this.setData({ enterprises, allPositions: approved, positions, enterpriseIndex, positionIndex, selectedPosition: positions[positionIndex] || null, form, loading: false });
      }).catch(() => this.setData({ loading: false }));
  },
  input(e) { this.setData({ [`form.${e.currentTarget.dataset.key}`]: e.detail.value }); },
  enterpriseChange(e) {
    const enterpriseIndex = Number(e.detail.value), enterprise = this.data.enterprises[enterpriseIndex];
    const positions = this.data.allPositions.filter((item) => item.enterprise_id === enterprise.id);
    this.setData({ enterpriseIndex, positions, positionIndex: 0, selectedPosition: positions[0] || null, 'form.enterprise_id': enterprise.id, 'form.position_id': (positions[0] && positions[0].id) || 0 });
  },
  positionChange(e) {
    const positionIndex = Number(e.detail.value), position = this.data.positions[positionIndex];
    this.setData({ positionIndex, selectedPosition: position || null, 'form.position_id': (position && position.id) || 0 });
  },
  save() {
    const form = this.data.form;
    if (!form.name.trim() || !form.id_number.trim()) { wx.showToast({ title: '姓名和身份证号必填', icon: 'none' }); return; }
    if (!form.position_id) { wx.showToast({ title: '暂无审核通过的可投保岗位', icon: 'none' }); return; }
    this.setData({ saving: true });
    const payload = { ...form, name: form.name.trim(), id_number: form.id_number.trim(), phone: form.phone.trim() };
    const request = this.data.id ? app.request(`/insured/${this.data.id}`, { method: 'PATCH', data: { name: payload.name, id_number: payload.id_number, phone: payload.phone, position_id: payload.position_id } }) : app.request('/insured', { method: 'POST', data: payload });
    request.then(() => { wx.showToast({ title: this.data.id ? '已保存' : '已提交审核' }); setTimeout(() => wx.navigateBack(), 500); }).catch(() => this.setData({ saving: false }));
  }
});
