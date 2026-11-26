import { http } from '@/api/http';
export interface PageAnalysisTask { taskId: string; state: string; text: string; error: string; timeoutMs: number; progress: string }
const route = (siteId: number) => `/api/admin/v2/sites/${siteId}/template-seed/analysis-tasks`;
export const pageAnalysisTasksApi = {
  start: (siteId: number, sourcePath: string, requestId: string) => http.post<PageAnalysisTask>(route(siteId), { sourcePath, requestId }),
  get: (siteId: number, id: string) => http.get<PageAnalysisTask>(`${route(siteId)}/${id}`),
  cancel: (siteId: number, id: string) => http.delete<PageAnalysisTask>(`${route(siteId)}/${id}`),
};
