// 与 backend/core/id_number.py 的 is_valid_id_number 保持一致的 18 位身份证号校验
// （GB 11643 校验位算法），供前端在提交前就地提示，而不是等后端 400 才发现。
const WEIGHTS = [7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2]
const CHECK_CODES = '10X98765432'

export function isValidIdNumber(value: string): boolean {
  const v = (value || '').trim().toUpperCase()
  if (!/^\d{17}[\dX]$/.test(v)) return false
  const year = Number(v.slice(6, 10))
  const month = Number(v.slice(10, 12))
  const day = Number(v.slice(12, 14))
  const birth = new Date(year, month - 1, day)
  if (birth.getFullYear() !== year || birth.getMonth() !== month - 1 || birth.getDate() !== day) return false
  if (birth.getTime() > Date.now()) return false
  let total = 0
  for (let i = 0; i < 17; i++) total += Number(v[i]) * WEIGHTS[i]
  return CHECK_CODES[total % 11] === v[17]
}
