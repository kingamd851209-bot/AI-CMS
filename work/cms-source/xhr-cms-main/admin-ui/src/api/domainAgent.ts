import { http } from '@/api/http';
export interface DomainUnit { id: string; name: string; fieldType: string; sources: {sourceId: string; start: number; end: number}[]; ignored: boolean; doubts: string }
export interface DomainStep { round: number; tool: string; status: string; message: string; at?: string }
export interface RecognitionSuggestion {
  kind: string; path: string; selector: string; targetPath?: string | null;
  facts: Record<string, unknown>; reason: string;
}
export interface PageRecognition { suggestions: RecognitionSuggestion[]; warnings: string[]; excludedSourceIds: string[] }
export interface DomainAgentProgress {
  message?: string; observedPages?: number; observedValues?: number; totalPages?: number;
  checkpointAvailable?: boolean; steps: DomainStep[];
}
export function parseDomainAgentProgress(value: string | null | undefined): DomainAgentProgress {
  const empty: DomainAgentProgress = { steps: [] };
  if (!value) return empty;
  try {
    const parsed: unknown = JSON.parse(value);
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return empty;
    const progress = parsed as Record<string, unknown>;
    const result: DomainAgentProgress = { steps: [] };
    if (typeof progress.message === 'string') result.message = progress.message.slice(0, 500);
    for (const field of ['observedPages', 'observedValues', 'totalPages'] as const) {
      if (typeof progress[field] === 'number' && Number.isFinite(progress[field]) && progress[field] >= 0) result[field] = progress[field];
    }
    if (typeof progress.checkpointAvailable === 'boolean') result.checkpointAvailable = progress.checkpointAvailable;
    if (Array.isArray(progress.steps)) {
      result.steps = progress.steps.filter((step): step is DomainStep => !!step && typeof step === 'object' &&
        Number.isInteger(step.round) && step.round >= 0 && typeof step.tool === 'string' &&
        typeof step.status === 'string' && typeof step.message === 'string').slice(-20).map(step => ({
        round: step.round, tool: step.tool, status: step.status, message: step.message.slice(0, 500),
        ...(typeof step.at === 'string' && Number.isFinite(Date.parse(step.at)) ? { at: step.at } : {}),
      }));
    }
    return result;
  } catch {
    return /^[\s]*[\[{]/.test(value) ? empty : { message: value.slice(0, 500), steps: [] };
  }
}
export interface DomainAgentResult {
  sourcePath: string; packageVersion: string; digest: string; revision: number; menuId: number | null; pageRole: string; summary: string;
  units: DomainUnit[];
  mappings: {unitId: string; fieldName: string | null; action: 'REUSE' | 'CREATE' | 'PENDING' | 'MENU_NAME'; menuId?:number|null; reason: string}[];
  steps: DomainStep[]; warnings: string[]; observedSources: number; totalSources: number;
  recognition?: PageRecognition | null;
}
export interface DomainAgentTask { taskId: string; state: string; text: string; error: string; timeoutMs: number; progress: string; sequence?: number; startedAt?: string }
export interface AgentRunReference { taskId: string; kind: 'site' | 'page'; sourcePath?: string; startedAt: number; endedAt?: number; state?: string }
export interface AgentChatScope { sourcePath: string; packageVersion: string; digest: string; revision: number }
export interface AgentChatTaskRef { kind: 'site' | 'page'; id: string }
export interface AgentChatMessage { role: 'user' | 'assistant'; content: string }
export interface AgentChatProposal { before: DomainUnit; after: DomainUnit; reason: string }
export interface AgentChatReply { reply: string; proposal: AgentChatProposal | null }
const route = (siteId: number) => `/api/admin/v2/sites/${siteId}/template-seed/domain-agent-tasks`;
export const domainAgentApi = {
  recognition: (siteId: number, sourcePath: string, menuId?: number, packageVersion?: string | null, digest?: string) =>
    http.get<PageRecognition>(`${route(siteId)}/recognition-evidence`, { params: { sourcePath, menuId, packageVersion, digest } }),
  start: (siteId: number, sourcePath: string, menuId: number | null, requestId: string) => http.post<DomainAgentTask>(route(siteId), {sourcePath, menuId, requestId}),
  get: (siteId: number, taskId: string) => http.get<DomainAgentTask>(`${route(siteId)}/${encodeURIComponent(taskId)}`),
  cancel: (siteId: number, taskId: string) => http.delete<DomainAgentTask>(`${route(siteId)}/${encodeURIComponent(taskId)}`),
  chat: (siteId: number, scope: { [Key in keyof AgentChatScope]: AgentChatScope[Key] | null }, unit: DomainUnit | null,
    taskRef: AgentChatTaskRef | null, messages: AgentChatMessage[], signal: AbortSignal) =>
    http.post<AgentChatReply>(`/api/admin/v2/sites/${siteId}/template-seed/domain-agent-chat`, { ...scope, unit, taskRef, messages }, { signal, timeout: 65000 }),
};
