const TASKS_KEY = 'xbb-daily-test-tasks';
const LOGS_KEY = 'xbb-daily-test-logs';

function today() {
  const now = new Date();
  const year = now.getFullYear();
  const month = String(now.getMonth() + 1).padStart(2, '0');
  const day = String(now.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
}

function seedTasks() {
  return [
    { id: 'task-1', title: '确认今日新增人员资料', category: '人员', priority: 'high', dueDate: today(), done: false, note: '检查姓名、证件和岗位信息是否完整' },
    { id: 'task-2', title: '跟进保司参保回执', category: '参保', priority: 'medium', dueDate: today(), done: false, note: '确认昨日批次是否全部受理' },
    { id: 'task-3', title: '整理本周客户回访记录', category: '客户', priority: 'low', dueDate: today(), done: true, note: '演示任务，可点击切换状态' },
    { id: 'task-4', title: '补充理赔材料提醒', category: '理赔', priority: 'high', dueDate: today(), done: false, note: '联系企业经办人补充发票材料' }
  ];
}

function seedLogs() {
  return [
    { id: 'log-1', content: '完成晨会，已同步今日重点任务。', mood: '顺利', createdAt: `${today()} 09:05` },
    { id: 'log-2', content: '已向保司发送参保批次测试数据。', mood: '进行中', createdAt: `${today()} 10:30` }
  ];
}

App({
  globalData: {
    version: '0.1.0-test',
    testMode: true
  },

  onLaunch() {
    if (!wx.getStorageSync(TASKS_KEY)) wx.setStorageSync(TASKS_KEY, seedTasks());
    if (!wx.getStorageSync(LOGS_KEY)) wx.setStorageSync(LOGS_KEY, seedLogs());
  },

  today,

  getTasks() {
    return wx.getStorageSync(TASKS_KEY) || [];
  },

  saveTasks(tasks) {
    wx.setStorageSync(TASKS_KEY, tasks);
  },

  addTask(input) {
    const tasks = this.getTasks();
    const task = {
      id: `task-${Date.now()}`,
      title: String(input.title || '').trim(),
      category: input.category || '其他',
      priority: input.priority || 'medium',
      dueDate: input.dueDate || today(),
      note: String(input.note || '').trim(),
      done: false
    };
    tasks.unshift(task);
    this.saveTasks(tasks);
    return task;
  },

  toggleTask(id) {
    const tasks = this.getTasks().map((item) => item.id === id ? { ...item, done: !item.done } : item);
    this.saveTasks(tasks);
    return tasks;
  },

  deleteTask(id) {
    const tasks = this.getTasks().filter((item) => item.id !== id);
    this.saveTasks(tasks);
    return tasks;
  },

  getLogs() {
    return wx.getStorageSync(LOGS_KEY) || [];
  },

  addLog(content, mood) {
    const now = new Date();
    const time = `${today()} ${String(now.getHours()).padStart(2, '0')}:${String(now.getMinutes()).padStart(2, '0')}`;
    const logs = this.getLogs();
    logs.unshift({ id: `log-${Date.now()}`, content: String(content || '').trim(), mood: mood || '顺利', createdAt: time });
    wx.setStorageSync(LOGS_KEY, logs);
    return logs;
  },

  resetDemo() {
    wx.setStorageSync(TASKS_KEY, seedTasks());
    wx.setStorageSync(LOGS_KEY, seedLogs());
  }
});
