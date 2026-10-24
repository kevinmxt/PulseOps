export function validateMonitorName(name: string): string | null {
  const trimmed = name.trim()
  if (!trimmed) {
    return '名称不能为空'
  }
  if (trimmed.length > 100) {
    return '名称最多 100 个字符'
  }
  return null
}

export function requireIntegerRange<K extends string>(
  errors: Partial<Record<K, string>>,
  field: K,
  value: number | null,
  min: number,
  max: number,
  label: string,
  unit = '',
): void {
  if (typeof value !== 'number' || !Number.isFinite(value)) {
    errors[field] = `${label}不能为空`
    return
  }
  if (!Number.isInteger(value) || value < min || value > max) {
    const suffix = unit ? ` ${unit}` : ''
    errors[field] = `${label}必须在 ${min}–${max}${suffix} 之间`
  }
}
