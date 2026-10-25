import { describe, expect, it } from 'vitest'
import { DEFAULT_AUTH_REDIRECT, safeRedirect } from './authRedirect'

describe('safeRedirect', () => {
  it('keeps internal paths with query strings', () => {
    expect(safeRedirect('/monitors/4?page=2')).toBe('/monitors/4?page=2')
    expect(safeRedirect('/')).toBe('/')
  })

  it('rejects external and protocol-relative targets', () => {
    expect(safeRedirect('//evil.example')).toBe(DEFAULT_AUTH_REDIRECT)
    expect(safeRedirect('https://evil.example')).toBe(DEFAULT_AUTH_REDIRECT)
    expect(safeRedirect('/\\evil.example')).toBe(DEFAULT_AUTH_REDIRECT)
  })

  it('falls back for missing or non-string values', () => {
    expect(safeRedirect(undefined)).toBe(DEFAULT_AUTH_REDIRECT)
    expect(safeRedirect(null)).toBe(DEFAULT_AUTH_REDIRECT)
    expect(safeRedirect(['/monitors'])).toBe(DEFAULT_AUTH_REDIRECT)
  })
})
