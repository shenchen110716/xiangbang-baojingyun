// 与 backend/core/credit_code.py 的 is_valid_credit_code 保持一致的 18 位统一
// 社会信用代码校验（GB 32100-2015 校验位算法），供前端在提交前就地提示，而不是
// 等后端 400 才发现（用户反馈 2026-07-30 第 2 条）。
const CHARSET = '0123456789ABCDEFGHJKLMNPQRTUWXY'
const WEIGHTS = [1, 3, 9, 27, 19, 26, 16, 17, 20, 29, 25, 13, 8, 24, 10, 30, 28]

export function isValidCreditCode(value: string): boolean {
  const v = (value || '').trim().toUpperCase()
  if (!/^[0-9A-Z]{18}$/.test(v)) return false
  if ([...v].some((ch) => !CHARSET.includes(ch))) return false
  let total = 0
  for (let i = 0; i < 17; i++) total += CHARSET.indexOf(v[i]) * WEIGHTS[i]
  const check = (31 - (total % 31)) % 31
  return CHARSET[check] === v[17]
}
