import type { AiUsage } from './aiGenerationClient'

export interface AiModelStatus {
  status: 'running' | 'starting' | 'available' | 'stopped' | 'failed'
  url: string
  message: string
  error: string
  externalManagementUnsupported?: boolean
}

/** 仅检查普通翻译模型配置，不调用模型。 */
export async function getAiStatus(usage: AiUsage = 'CONTENT_TRANSLATION'): Promise<AiModelStatus> {
  const response = await fetch(`/api/admin/v2/ai-generation/status?usage=${usage}`)
  if (!response.ok) throw new Error('获取模型配置状态失败')
  return response.json()
}

