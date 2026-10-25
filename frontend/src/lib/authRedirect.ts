export const DEFAULT_AUTH_REDIRECT = '/monitors'

export function safeRedirect(value: unknown): string {
  if (typeof value !== 'string') {
    return DEFAULT_AUTH_REDIRECT
  }
  if (!value.startsWith('/') || value.startsWith('//') || value.includes('\\')) {
    return DEFAULT_AUTH_REDIRECT
  }
  return value
}
