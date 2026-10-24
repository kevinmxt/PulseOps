import { onMounted, onUnmounted } from 'vue'

export function useVisiblePolling(callback: () => void, intervalMs: number): void {
  let timer: number | undefined

  onMounted(() => {
    timer = window.setInterval(() => {
      if (document.visibilityState === 'visible') {
        callback()
      }
    }, intervalMs)
  })

  onUnmounted(() => {
    if (timer !== undefined) {
      window.clearInterval(timer)
    }
  })
}
