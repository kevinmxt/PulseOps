import { describe, expect, it } from 'vitest'
import { buildHeartbeatCommand } from './heartbeatCommand'

describe('buildHeartbeatCommand', () => {
  it('builds a curl report command from the current origin', () => {
    expect(buildHeartbeatCommand('https://pulse.example.com', 'tok-123')).toBe(
      'curl -fsS -X POST https://pulse.example.com/api/v1/heartbeats/tok-123',
    )
  })

  it('tolerates a trailing slash in the origin', () => {
    expect(buildHeartbeatCommand('http://localhost:5173/', 'tok-123')).toBe(
      'curl -fsS -X POST http://localhost:5173/api/v1/heartbeats/tok-123',
    )
  })
})
