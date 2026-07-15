const app = getApp();
Page({
  data: { kinds: ['批量参保', '批量停保'], kindValues: ['enrollment', 'termination'], kindIndex: 0, enterprises: [], allPositions: [], positions: [], enterpriseIndex: 0, positionIndex: 0, rows: [], errors: [], fileName: '', filePath: '', loading: false },
  onLoad() { Promise.all([app.request('/enterprises'), app.request('/positions')]).then(([enterprises, allPositions]) => { const approved = allPositions.filter((item) => item.status === 'approved'), enterpriseId = (enterprises[0] && enterprises[0].id) || 0; this.setData({ enterprises, allPositions: approved, positions: approved.filter((item) => item.enterprise_id === enterpriseId) }); }); },
  kindChange(e) { this.setData({ kindIndex: Number(e.detail.value), errors: [], rows: [] }); },
  enterpriseChange(e) { const enterpriseIndex = Number(e.detail.value), enterprise = this.data.enterprises[enterpriseIndex], positions = this.data.allPositions.filter((item) => item.enterprise_id === enterprise.id); this.setData({ enterpriseIndex, positions, positionIndex: 0 }); },
  positionChange(e) { this.setData({ positionIndex: Number(e.detail.value) }); },
  template() {
    if (!app.globalData.token) { app.logout(true); return; }
    wx.showLoading({ title: '正在生成模板', mask: true });
    wx.request({
      url: `${app.globalData.apiBase}/insured/import-template`,
      method: 'GET',
      header: { Authorization: `Bearer ${app.globalData.token}` },
      responseType: 'arraybuffer',
      timeout: 30000,
      success: (res) => {
        if (res.statusCode === 401) {
          wx.hideLoading();
          app.logout(true);
          return;
        }
        if (res.statusCode !== 200 || !(res.data instanceof ArrayBuffer) || !res.data.byteLength) {
          wx.hideLoading();
          wx.showModal({ title: '下载失败', content: `模板服务返回异常（${res.statusCode}），请稍后重试。`, showCancel: false });
          return;
        }
        const filePath = `${wx.env.USER_DATA_PATH}/响帮帮批量导入标准模板.xlsx`;
        wx.getFileSystemManager().writeFile({
          filePath,
          data: res.data,
          success: () => {
            wx.hideLoading();
            wx.openDocument({
              filePath,
              fileType: 'xlsx',
              showMenu: true,
              fail: () => wx.showModal({ title: '模板已下载', content: '微信无法直接打开文件，请重试或升级微信后再试。', showCancel: false })
            });
          },
          fail: () => {
            wx.hideLoading();
            wx.showModal({ title: '保存失败', content: '无法保存标准模板，请清理微信存储空间后重试。', showCancel: false });
          }
        });
      },
      fail: (error) => {
        wx.hideLoading();
        const detail = (error && error.errMsg) || '';
        const content = detail.includes('timeout') ? '下载超时，请检查网络后重试。' : '无法连接模板服务，请检查网络后重试。';
        wx.showModal({ title: '下载失败', content, showCancel: false });
      }
    });
  },
  choose() { wx.chooseMessageFile({ count: 1, type: 'file', extension: ['csv', 'xlsx'], success: (res) => { const file = res.tempFiles[0], isCsv = file.name.toLowerCase().endsWith('.csv'); this.setData({ fileName: file.name, filePath: file.path, rows: [], errors: [] }); if (isCsv) wx.getFileSystemManager().readFile({ filePath: file.path, encoding: 'utf-8', success: (data) => this.parseCsv(data.data), fail: () => wx.showToast({ title: '文件读取失败，请重新选择', icon: 'none' }) }); }, fail: (error) => { const message = (error && error.errMsg) || ''; if (message.includes('cancel')) return; wx.showToast({ title: '选择文件失败，请从聊天记录中选择 CSV 或 XLSX 文件', icon: 'none' }); } }); },
  parseCsv(text) {
    const lines = text.replace(/^\ufeff/, '').split(/\r?\n/).filter(Boolean), rows = [], errors = [];
    if (!lines.length) { this.setData({ rows: [], errors: [] }); return; }
    const headerCells = lines[0].split(',').map((value) => value.trim().replace(/^"|"$/g, '').replace(/\s/g, ''));
    const colIndex = (label) => headerCells.indexOf(label);
    const nameCol = colIndex('姓名'), idCol = colIndex('身份证号'), phoneCol = colIndex('手机号');
    const enterpriseCol = colIndex('投保单位'), employerCol = colIndex('实际工作单位'), positionCol = colIndex('岗位名称');
    const effectiveCol = colIndex('生效日期'), terminatedCol = colIndex('停保日期');
    lines.slice(1).forEach((line, index) => {
      const cells = line.split(',').map((value) => value.trim().replace(/^"|"$/g, ''));
      const at = (col) => (col >= 0 ? cells[col] || '' : '');
      const row = { name: at(nameCol), id_number: at(idCol), phone: at(phoneCol), enterprise: at(enterpriseCol), actual_employer: at(employerCol), position: at(positionCol), effective_at: at(effectiveCol), terminated_at: at(terminatedCol) };
      if (!row.id_number || (this.data.kindIndex === 0 && !row.name)) errors.push({ row: index + 2, message: '参保需姓名和身份证号，停保需身份证号' });
      else rows.push(row);
    });
    this.setData({ rows, errors });
  },
  submit() { if (!this.data.filePath) { wx.showToast({ title: '请先选择电子表格', icon: 'none' }); return; } if (this.data.errors.length) { wx.showToast({ title: '请先修正表格错误', icon: 'none' }); return; } const enterprise = this.data.enterprises[this.data.enterpriseIndex], position = this.data.positions[this.data.positionIndex], kind = this.data.kindValues[this.data.kindIndex]; if (!enterprise || (kind === 'enrollment' && !position)) { wx.showToast({ title: '请选择单位和已审核岗位', icon: 'none' }); return; } this.setData({ loading: true }); app.upload('/insured/import-file', this.data.filePath, 'file', { kind, enterprise_id: String(enterprise.id), position_id: String((position && position.id) || 0) }).then((data) => { if (!data.ok) { this.setData({ errors: data.errors || [], loading: false }); return; } wx.showToast({ title: `成功 ${data.success} 人` }); this.setData({ fileName: '', filePath: '', rows: [], errors: [], loading: false }); }).catch(() => this.setData({ loading: false })); },
  onShareAppMessage() { return app.share('/pages/import/import', 'from=share'); }
});
