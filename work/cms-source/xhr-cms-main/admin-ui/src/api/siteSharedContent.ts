import { http } from '@/api/http';

export interface SharedContentSource {
  id: string;
  kind: 'TEXT' | 'IMAGE';
  rawValue: string;
  selector: string;
  attribute: string;
  publicRegion: 'HEADER' | 'FOOTER';
}

export interface SharedContentField {
  fieldKey: string;
  label: string;
  fieldType: string;
  hasValue?: boolean;
}

export interface SharedContentOwner {
  modelKey: string;
  menuId: number;
  label: string;
  contentStatus: 'DRAFT' | 'PENDING_REVIEW' | 'APPROVED' | 'PUBLISHED';
  fields: SharedContentField[];
}

export interface SharedContentSelection {
  sourceId: string;
  start: number;
  end: number;
  modelKey: string;
  fieldKey: string;
}

export interface SharedContentBinding extends SharedContentSelection {
  menuId: number;
  fieldName: string;
  fieldType: string;
  selector: string;
  publicRegion: 'HEADER' | 'FOOTER';
}

export interface SharedContentReview { digest: string; bindings: SharedContentBinding[] }
export interface SharedContentEvidence {
  revision: number;
  sourcePath: string;
  sourceDigest: string;
  sources: SharedContentSource[];
  owners: SharedContentOwner[];
  warnings?: string[];
  confirmed?: SharedContentReview | null;
}

export interface SharedContentRequest {
  packageVersion: string;
  revision: number;
  sourcePath: string;
  sourceDigest: string;
  selections: SharedContentSelection[];
  previousDigest?: string;
}

const endpoint = (siteId: number, planId: string) =>
  `/api/admin/v2/sites/${siteId}/template-seed/site-model/plans/${encodeURIComponent(planId)}/shared-content`;
export const readSharedContent = (siteId: number, planId: string, packageVersion: string, sourcePath: string) =>
  http.get<SharedContentEvidence>(endpoint(siteId, planId), { params: { packageVersion, sourcePath } });
export const previewSharedContent = (siteId: number, planId: string, body: SharedContentRequest) =>
  http.post<SharedContentReview>(endpoint(siteId, planId) + '/preview', body);
export const confirmSharedContent = (siteId: number, planId: string, body: SharedContentRequest, digest: string) =>
  http.post<SharedContentReview>(endpoint(siteId, planId) + '/confirm', { ...body, digest, confirmed: true });

export interface SharedContentSeedValue extends SharedContentSelection {
  menuId: number;
  fieldName: string;
  rawValue: string;
  status: 'READY' | 'PRESERVED' | 'PENDING';
  reason?: string | null;
}
export interface SharedContentSeedReview { digest: string; values: SharedContentSeedValue[] }
export const previewSharedContentSeed = (siteId: number, planId: string, body: SharedContentRequest) =>
  http.post<SharedContentSeedReview>(endpoint(siteId, planId) + '/seed/preview', body);
export const confirmSharedContentSeed = (siteId: number, planId: string, body: SharedContentRequest, digest: string) =>
  http.post<SharedContentSeedReview>(endpoint(siteId, planId) + '/seed/confirm', { ...body, digest, confirmed: true });
