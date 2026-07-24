const app = getApp();
Page({
  data: { enterprises: [], allPeople: [], people: [], enterpriseIndex: 0, personIndex: 0, personQuery: '', accidentTypes: ['工作场所事故', '上下班交通事故', '职业病', '突发疾病', '其他工伤事故'], typeIndex: 0, payeeTypes: ['本人', '近亲属', '单位代收'], payeeTypeIndex: -1, form: { accident_date: '', accident_time: '', accident_place: '', accident_type: '工作场所事故', injury_part: '', payee_type: '', hospital: '', diagnosis: '', medical_cost: '0', contact_name: '', contact_phone: '', amount: '0', description: '' }, saving: false, preferredPersonId: 0 },
  onLoad(options) {
    const preferredPersonId = Number(options.personId || 0);
    this.setData({ preferredPersonId });
    Promise.all([app.request('/enterprises'), app.request('/insured')]).then(([enterprises, people]) => {
      // 从员工详情页带 personId 进来时，投保单位要跟着这个人走，不能默认
      // 用企业列表里的第一家——之前就是因为这里没跟着走，导致下面按
      // "在保" 过滤时如果这个人本身不在保（待生效/已停保历史工伤），
      // personIndex 兜底成 0，静默换成了另一个企业的第一个在保员工。
      const preferredPerson = preferredPersonId ? people.find((item) => item.id === preferredPersonId) : null;
      const enterpriseId = preferredPerson ? preferredPerson.enterprise_id : ((enterprises[0] && enterprises[0].id) || 0);
      const enterpriseIndex = Math.max(0, enterprises.findIndex((item) => item.id === enterpriseId));
      let filtered = people.filter((item) => item.enterprise_id === enterpriseId && item.status === 'active');
      // 报案针对的人本身可能不在保（比如工伤发生后才停保、或还待生效），
      // 也必须出现在候选列表里，不能被"在保"筛选条件排除掉。
      if (preferredPerson && !filtered.some((item) => item.id === preferredPersonId)) {
        filtered = [preferredPerson, ...filtered];
      }
      const personIndex = Math.max(0, filtered.findIndex((item) => item.id === preferredPersonId));
      this.setData({ enterprises, allPeople: people, people: filtered, enterpriseIndex, personIndex });
      this.fillContact(filtered[personIndex]);
    });
  },
  input(e) { this.setData({ [`form.${e.currentTarget.dataset.key}`]: e.detail.value }); },
  fillContact(person) { if (person) this.setData({ 'form.contact_name': person.name, 'form.contact_phone': person.phone || '' }); },
  // 受伤员工可能很多，姓名输入框做本地过滤，选完自动带入联系人/联系电话（默认本人信息，可再改）
  filterPeople(enterpriseId, query) {
    const q = (query || '').trim();
    return this.data.allPeople.filter((item) => item.enterprise_id === enterpriseId && item.status === 'active' && (!q || item.name.includes(q)));
  },
  personQueryInput(e) {
    const personQuery = e.detail.value, enterprise = this.data.enterprises[this.data.enterpriseIndex];
    const people = this.filterPeople(enterprise ? enterprise.id : 0, personQuery);
    this.setData({ personQuery, people, personIndex: 0 });
    this.fillContact(people[0]);
  },
  enterpriseChange(e) { const enterpriseIndex = Number(e.detail.value), enterprise = this.data.enterprises[enterpriseIndex]; const people = this.filterPeople(enterprise.id, this.data.personQuery); this.setData({ enterpriseIndex, people, personIndex: 0 }); this.fillContact(people[0]); },
  personChange(e) { const personIndex = Number(e.detail.value); this.setData({ personIndex }); this.fillContact(this.data.people[personIndex]); },
  typeChange(e) { const typeIndex = Number(e.detail.value); this.setData({ typeIndex, 'form.accident_type': this.data.accidentTypes[typeIndex] }); },
  payeeTypeChange(e) { const payeeTypeIndex = Number(e.detail.value); this.setData({ payeeTypeIndex, 'form.payee_type': this.data.payeeTypes[payeeTypeIndex] }); },
  dateChange(e) { this.setData({ 'form.accident_date': e.detail.value }); },
  timeChange(e) { this.setData({ 'form.accident_time': e.detail.value }); },
  save() { const enterprise = this.data.enterprises[this.data.enterpriseIndex], person = this.data.people[this.data.personIndex], form = this.data.form; if (!enterprise || !person || !form.accident_date || !form.accident_place.trim() || !form.description.trim()) { wx.showToast({ title: '请填写事故必填信息', icon: 'none' }); return; } this.setData({ saving: true }); app.request('/claims', { method: 'POST', data: { enterprise_id: enterprise.id, person_id: person.id, accident_at: `${form.accident_date} ${form.accident_time || '00:00'}`, accident_place: form.accident_place, accident_type: form.accident_type, injury_part: form.injury_part, payee_type: form.payee_type, hospital: form.hospital, diagnosis: form.diagnosis, medical_cost: Number(form.medical_cost || 0), contact_name: form.contact_name, contact_phone: form.contact_phone, amount: Number(form.amount || 0), description: form.description } }).then((claim) => { wx.showToast({ title: '报案成功' }); setTimeout(() => wx.redirectTo({ url: `/pages/claim-detail/claim-detail?id=${claim.id}` }), 500); }).catch(() => this.setData({ saving: false })); }
});
