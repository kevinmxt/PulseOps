export function buildHeartbeatCommand(origin: string, token: string): string {
  const base = origin.replace(/\/+$/, '')
  return `curl -fsS -X POST ${base}/api/v1/heartbeats/${token}`
}
