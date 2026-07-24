const app = getApp();
Page({
  data: { person: null, loading: true, notFound: false, coveragePeriod: '', statusLabel: '', isActive: false },
  onLoad(options) {
    // 原生渲染在保证明，避免 web-view 业务域名限制（微信提示“不支持打开”）。
    const id = Number(options.id);
    app.request('/insured').then((people) => {
      const person = (people || []).find((p) => p.id === id);
      if (!person) { this.setData({ loading: false, notFound: true }); return; }
      const start = this.dateOnly(person.effective_at);
      const end = person.terminated_at ? this.dateOnly(person.terminated_at) : '';
      const coveragePeriod = start
        ? `${start} 零时起至 ${end ? end + ' 二十四时止' : '长期'}`
        : '—';
      this.setData({ person, loading: false, coveragePeriod, statusLabel: app.statusText(person.status), isActive: person.status === 'active' });
    }).catch(() => this.setData({ loading: false, notFound: true }));
  },
  dateOnly(v) { return v ? String(v).slice(0, 10) : ''; },
  onShareAppMessage() { return { title: '在保证明', path: `/pages/cert/cert?id=${this.data.person ? this.data.person.id : ''}` }; }
});
