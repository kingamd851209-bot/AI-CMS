import { http } from '@/api/http';

export interface AsyncFragmentCandidate {
  triggerSelector: string;
  label: string;
  attribute: string;
  rawTarget: string;
  targetPath: string | null;
  targetDigest: string | null;
  status: 'REVIEW' | 'PENDING';
  message: string;
}
export interface AsyncFragmentInspection {
  sourcePath: string;
  digest: string;
  candidates: AsyncFragmentCandidate[];
  warnings: string[];
}
export function inspectAsyncFragments(siteId: number, sourcePath: string): Promise<AsyncFragmentInspection> {
  return http.get<AsyncFragmentInspection>(`/api/admin/v2/sites/${siteId}/template-seed/async-fragments`, { params: { sourcePath } });
}
