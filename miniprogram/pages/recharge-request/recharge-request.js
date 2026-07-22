const app = getApp();
const { payWithWeChat } = require('../../utils/wechatPay.js');

const STATUS_TEXT = { pending: '待确认', confirmed: '已到账', rejected: '已驳回' };

Page({
  data: {
    tab: 'submit',
    enterpriseId: 0,
    accountType: 'usage',
    method: 'wechat',
    insurerOptions: [],
    insurerIndex: -1,
    amount: '',
    paymentAccount: null,
    paymentLoading: false,
    receiptPath: '',
    ocrLoading: false,
    ocrHint: '',
    submitting: false,
    records: [],
    recordsLoading: false
  },
  onLoad(options) {
    const enterpriseId = Number(options.enterpriseId || (app.globalData.user && app.globalData.user.enterprise_id) || 0);
    const accountType = options.accountType === 'premium' ? 'premium' : 'usage';
    this.setData({ enterpriseId, accountType, method: accountType === 'premium' ? 'bank' : 'wechat', tab: options.tab === 'records' ? 'records' : 'submit' });
    if (accountType === 'premium') this.loadInsurerOptions();
    this.refreshPaymentAccount();
    if (this.data.tab === 'records') this.loadRecords();
  },
  switchTab(e) {
    const tab = e.currentTarget.dataset.tab;
    this.setData({ tab });
    if (tab === 'records' && !this.data.records.length) this.loadRecords();
  },
  loadInsurerOptions() {
    app.request('/recharge/payment-accounts', { silent: true })
      .then((options) => this.setData({ insurerOptions: options || [] }))
      .catch(() => this.setData({ insurerOptions: [] }));
  },
  accountTypeChange(e) {
    const accountType = e.currentTarget.dataset.value;
    if (accountType === this.data.accountType) return;
    this.setData({ accountType, method: accountType === 'premium' ? 'bank' : 'wechat', insurerIndex: -1, paymentAccount: null });
    if (accountType === 'premium' && !this.data.insurerOptions.length) this.loadInsurerOptions();
    this.refreshPaymentAccount();
  },
  methodChange(e) {
    this.setData({ method: e.currentTarget.dataset.value });
  },
  insurerChange(e) {
    this.setData({ insurerIndex: Number(e.detail.value) });
    this.refreshPaymentAccount();
  },
  currentInsurer() {
    const opt = this.data.insurerOptions[this.data.insurerIndex];
    return opt ? opt.insurer : '';
  },
  refreshPaymentAccount() {
    if (this.data.accountType === 'premium' && !this.currentInsurer()) { this.setData({ paymentAccount: null }); return; }
    this.setData({ paymentLoading: true });
    const insurer = this.data.accountType === 'premium' ? this.currentInsurer() : '';
    app.request(`/recharge/payment-account?account_type=${this.data.accountType}&insurer=${encodeURIComponent(insurer)}`, { silent: true })
      .then((account) => this.setData({ paymentAccount: account || null, paymentLoading: false }))
      .catch(() => this.setData({ paymentAccount: null, paymentLoading: false }));
  },
  amountInput(e) {
    this.setData({ amount: e.detail.value });
  },
  // 银行转账回单：拍照或从相册选图，选完先尝试 OCR 自动识别金额（失败静默，不影响手工填写），
  // 与电脑后台上传回单自动识别金额的体验保持一致。
  chooseReceipt() {
    wx.chooseMedia({
      count: 1, mediaType: ['image'], sourceType: ['camera', 'album'], sizeType: ['compressed'],
      success: (res) => {
        const filePath = res.tempFiles[0].tempFilePath;
        this.setData({ receiptPath: filePath, ocrHint: '' });
        this.setData({ ocrLoading: true });
        app.upload('/ocr/receipt-amount', filePath, 'file')
          .then((data) => {
            if (data && data.amount > 0) {
              this.setData({ amount: String(data.amount), ocrHint: data.mock ? `已识别金额 ¥${data.amount}（模拟，请核对）` : `已识别金额 ¥${data.amount}，请核对` });
            }
          })
          .catch(() => { /* OCR 未启用或识别失败：静默，用户手工填写 */ })
          .finally(() => this.setData({ ocrLoading: false }));
      }
    });
  },
  submitWeChat() {
    const amount = Number(this.data.amount);
    if (!amount || amount <= 0) { wx.showToast({ title: '请输入有效金额', icon: 'none' }); return; }
    payWithWeChat(app, this.data.enterpriseId, 'usage', amount, {
      onSuccess: () => { this.setData({ tab: 'records' }); this.loadRecords(); }
    });
  },
  submitBankTransfer() {
    const amount = Number(this.data.amount);
    if (!amount || amount <= 0) { wx.showToast({ title: '请输入有效金额', icon: 'none' }); return; }
    if (this.data.accountType === 'premium' && !this.currentInsurer()) { wx.showToast({ title: '请选择保司', icon: 'none' }); return; }
    if (!this.data.receiptPath) { wx.showToast({ title: '请上传转账回单', icon: 'none' }); return; }
    this.setData({ submitting: true });
    app.upload('/recharge-requests', this.data.receiptPath, 'file', {
      enterprise_id: this.data.enterpriseId,
      account_type: this.data.accountType,
      insurer: this.data.accountType === 'premium' ? this.currentInsurer() : '',
      amount
    })
      .then(() => {
        wx.showToast({ title: '充值申请已提交，等待平台确认到账' });
        this.setData({ amount: '', receiptPath: '', ocrHint: '', tab: 'records' });
        this.loadRecords();
      })
      .catch(() => {})
      .finally(() => this.setData({ submitting: false }));
  },
  loadRecords() {
    this.setData({ recordsLoading: true });
    app.request('/recharge-requests', { silent: true })
      .then((rows) => this.setData({
        records: (rows || []).map((row) => ({ ...row, amount_text: Number(row.amount || 0).toFixed(2), status_text: STATUS_TEXT[row.status] || row.status, account_text: row.account_type === 'premium' ? '保费' : '系统服务费' })),
        recordsLoading: false
      }))
      .catch(() => this.setData({ recordsLoading: false }));
  }
});
