const app = getApp();
Page({
  data: { kinds: ['批量参保', '批量停保'], kindValues: ['enrollment', 'termination'], kindIndex: 0, enterprises: [], allPositions: [], positions: [], enterpriseIndex: 0, positionIndex: 0, rows: [], errors: [], fileName: '', filePath: '', loading: false },
  onLoad() { Promise.all([app.request('/enterprises'), app.request('/positions')]).then(([enterprises, allPositions]) => { const approved = allPositions.filter((item) => item.status === 'approved'), enterpriseId = (enterprises[0] && enterprises[0].id) || 0; this.setData({ enterprises, allPositions: approved, positions: approved.filter((item) => item.enterprise_id === enterpriseId) }); }); },
  kindChange(e) { this.setData({ kindIndex: Number(e.detail.value), errors: [], rows: [] }); },
  enterpriseChange(e) { const enterpriseIndex = Number(e.detail.value), enterprise = this.data.enterprises[enterpriseIndex], positions = this.data.allPositions.filter((item) => item.enterprise_id === enterprise.id); this.setData({ enterpriseIndex, positions, positionIndex: 0 }); },
  positionChange(e) { this.setData({ positionIndex: Number(e.detail.value) }); },
  template() {
    app.downloadAndOpen('/insured/import-template', { filename: '响帮帮批量导入标准模板.xlsx', fileType: 'xlsx', loadingTitle: '正在生成模板' }).catch(() => {});
  },
  choose() { wx.chooseMessageFile({ count: 1, type: 'file', extension: ['csv', 'xlsx'], success: (res) => { const file = res.tempFiles[0], isCsv = file.name.toLowerCase().endsWith('.csv'); if (file.size > 10 * 1024 * 1024) { wx.showModal({ title: '文件过大', content: '单个导入文件不能超过 10MB，请拆分后重试。', showCancel: false }); return; } this.setData({ fileName: file.name, filePath: file.path, rows: [], errors: [] }); if (isCsv) wx.getFileSystemManager().readFile({ filePath: file.path, encoding: 'utf-8', success: (data) => this.parseCsv(data.data), fail: () => wx.showToast({ title: '文件读取失败，请重新选择', icon: 'none' }) }); }, fail: (error) => { const message = (error && error.errMsg) || ''; if (message.includes('cancel')) return; wx.showToast({ title: '选择文件失败，请从聊天记录中选择 CSV 或 XLSX 文件', icon: 'none' }); } }); },
  csvRows(text) {
    const result = []; let row = [], value = '', quoted = false;
    const source = String(text || '').replace(/^\ufeff/, '');
    for (let index = 0; index < source.length; index += 1) {
      const char = source[index];
      if (quoted) {
        if (char === '"' && source[index + 1] === '"') { value += '"'; index += 1; }
        else if (char === '"') quoted = false;
        else value += char;
      } else if (char === '"') quoted = true;
      else if (char === ',') { row.push(value.trim()); value = ''; }
      else if (char === '\n') { row.push(value.trim()); if (row.some(Boolean)) result.push(row); row = []; value = ''; }
      else if (char !== '\r') value += char;
    }
    row.push(value.trim()); if (row.some(Boolean)) result.push(row);
    return result;
  },
  parseCsv(text) {
    const table = this.csvRows(text), rows = [], errors = [];
    if (!table.length) { this.setData({ rows: [], errors: [{ row: 1, message: '文件中没有可导入的数据' }] }); return; }
    const headerCells = table[0].map((value) => value.replace(/\s/g, ''));
    const colIndex = (label) => headerCells.indexOf(label);
    const nameCol = colIndex('姓名'), idCol = colIndex('身份证号'), phoneCol = colIndex('手机号');
    const enterpriseCol = colIndex('投保单位'), employerCol = colIndex('实际工作单位'), positionCol = colIndex('岗位名称');
    const effectiveCol = colIndex('生效日期'), terminatedCol = colIndex('停保日期');
    if (idCol < 0 || (this.data.kindIndex === 0 && nameCol < 0)) { this.setData({ rows: [], errors: [{ row: 1, message: '模板必须包含姓名、身份证号；停保模板至少包含身份证号' }] }); return; }
    table.slice(1).forEach((cells, index) => {
      const at = (col) => (col >= 0 ? cells[col] || '' : '');
      const row = { name: at(nameCol), id_number: at(idCol), phone: at(phoneCol), enterprise: at(enterpriseCol), actual_employer: at(employerCol), position: at(positionCol), effective_at: at(effectiveCol), terminated_at: at(terminatedCol) };
      if (!row.id_number || (this.data.kindIndex === 0 && !row.name)) errors.push({ row: index + 2, message: '参保需姓名和身份证号，停保需身份证号' });
      else rows.push(row);
    });
    this.setData({ rows, errors });
  },
  submit() { if (!this.data.filePath) { wx.showToast({ title: '请先选择电子表格', icon: 'none' }); return; } if (this.data.errors.length) { wx.showToast({ title: '请先修正表格错误', icon: 'none' }); return; } const enterprise = this.data.enterprises[this.data.enterpriseIndex], position = this.data.positions[this.data.positionIndex], kind = this.data.kindValues[this.data.kindIndex]; if (!enterprise || (kind === 'enrollment' && !position)) { wx.showToast({ title: '请选择单位和已审核岗位', icon: 'none' }); return; } this.setData({ loading: true }); app.upload('/insured/import-file', this.data.filePath, 'file', { kind, enterprise_id: String(enterprise.id), position_id: String((position && position.id) || 0) }).then((data) => { if (!data.ok) { this.setData({ rows: [], errors: data.errors || [], loading: false }); return; } wx.showToast({ title: `成功 ${data.success} 人` }); this.setData({ fileName: '', filePath: '', rows: [], errors: [], loading: false }); }).catch(() => this.setData({ loading: false })); },
  onShareAppMessage() { return app.share('/pages/import/import', 'from=share'); }
});
