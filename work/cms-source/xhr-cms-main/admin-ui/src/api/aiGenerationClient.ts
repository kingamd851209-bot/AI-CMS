/**
 * AI 模型 API 客户端（通过后端代理）
 * 通过 CMS 后端执行隔离的生成任务
 */

/**
 * 栏目信息
 */
export interface GenerationResult { taskId: string; text: string }
export interface TaskSnapshot extends GenerationResult {
  state: 'pending' | 'running' | 'completed' | 'failed' | 'cancelled'
  error: string
  timeoutMs: number
}
export interface SSECallbacks {
  onDelta?: (text: string) => void
  onProgress?: (text: string) => void
  onError?: (error: Error) => void
  onComplete?: () => void
  onDisconnect?: () => void
}

const API_BASE = '/api/admin/v2/ai-generation'
export type AiUsage = 'MENU_TRANSLATION' | 'CONTENT_TRANSLATION'
export type AiEngine = 'SPRING_AI'

async function failure(response: Response): Promise<Error> {
  const body = await response.json().catch(() => ({}))
  return new Error(body.detail || body.message || body.error || 'AI 请求失败')
}

async function taskRequest<T>(path: string, init: RequestInit = {}, signal?: AbortSignal): Promise<T> {
  const controller = new AbortController()
  const abort = () => controller.abort()
  if (signal?.aborted) abort()
  signal?.addEventListener('abort', abort, { once: true })
  const timer = setTimeout(abort, 15000)
  try {
    const response = await fetch(`${API_BASE}${path}`, { ...init, signal: controller.signal })
    if (!response.ok) throw await failure(response)
    return await response.json() as T
  } finally {
    clearTimeout(timer)
    signal?.removeEventListener('abort', abort)
  }
}

export async function createGenerationTask(siteId: number, usage: AiUsage, signal?: AbortSignal, engine?: AiEngine): Promise<{ id: string }> {
  if (!['MENU_TRANSLATION', 'CONTENT_TRANSLATION'].includes(usage)) throw new Error('该开发入口已停用')
  if (engine && engine !== 'SPRING_AI') throw new Error('旧引擎已停用，请选择SPRING_AI')
  return taskRequest('/tasks', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ siteId, usage, engine })
  }, signal)
}

export async function getGenerationTask(taskId: string, signal?: AbortSignal): Promise<TaskSnapshot> {
  return taskRequest(`/tasks/${encodeURIComponent(taskId)}`, {}, signal)
}

export async function waitForGenerationTask(taskId: string, signal?: AbortSignal, onSnapshot?: (task: TaskSnapshot) => void): Promise<TaskSnapshot> {
  let task = await getGenerationTask(taskId, signal)
  const deadline = Date.now() + task.timeoutMs + 30000
  while (task.state === 'pending' || task.state === 'running') {
    if (signal?.aborted) throw new DOMException('请求已取消', 'AbortError')
    if (Date.now() > deadline) throw new Error('AI 任务执行超时')
    onSnapshot?.(task)
    await new Promise<void>(resolve => setTimeout(resolve, 1000))
    task = await getGenerationTask(taskId, signal)
  }
  onSnapshot?.(task)
  return task
}

/** Submission is asynchronous; polling remains authoritative if the progress stream disconnects. */
export async function sendMessage(taskId: string, text: string, signal?: AbortSignal): Promise<GenerationResult> {
  const cancel = () => { void cancelGenerationTask(taskId).catch(() => undefined) }
  if (signal?.aborted) { cancel(); throw new DOMException('请求已取消', 'AbortError') }
  signal?.addEventListener('abort', cancel, { once: true })
  try {
    let task = await taskRequest<TaskSnapshot>(`/tasks/${encodeURIComponent(taskId)}/message`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ prompt: text })
    }, signal)
    task = await waitForGenerationTask(taskId, signal)
    if (task.state !== 'completed') throw new Error(task.error || 'AI 任务未完成')
    return { taskId: task.taskId, text: task.text }
  } catch (error) {
    cancel()
    throw error
  } finally {
    signal?.removeEventListener('abort', cancel)
  }
}

export function getTaskEvents(taskId: string, callbacks: SSECallbacks): EventSource {
  const source = new EventSource(`${API_BASE}/tasks/${encodeURIComponent(taskId)}/events`)
  for (const name of ['delta', 'progress']) {
    source.addEventListener(name, event => {
      try {
        const data = JSON.parse((event as MessageEvent).data) as { text: string }
        if (name === 'delta') callbacks.onDelta?.(data.text)
        else callbacks.onProgress?.(data.text)
      } catch { callbacks.onError?.(new Error('无法读取任务进度')) }
    })
  }
  source.addEventListener('done', () => { callbacks.onComplete?.(); source.close() })
  for (const name of ['failed', 'cancelled']) {
    source.addEventListener(name, event => {
      try {
        const data = JSON.parse((event as MessageEvent).data) as { text: string }
        callbacks.onError?.(new Error(data.text))
      } catch {
        callbacks.onError?.(new Error('无法读取任务状态，请查看最终结果'))
      } finally { source.close() }
    })
  }
  source.onerror = () => {
    callbacks.onDisconnect?.()
    callbacks.onError?.(new Error('进度连接已断开，正在继续查询任务结果'))
    source.close()
  }
  return source
}

export async function cancelGenerationTask(taskId: string): Promise<void> {
  await taskRequest(`/tasks/${encodeURIComponent(taskId)}/cancel`, { method: 'POST' })
}

export default { createGenerationTask, getGenerationTask, waitForGenerationTask, sendMessage, getTaskEvents, cancelGenerationTask }
