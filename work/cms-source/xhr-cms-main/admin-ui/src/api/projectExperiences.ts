import { http } from '@/api/http';
export interface ExperienceUnit { id: string; name: string; fieldType: string; ignored: boolean; doubts: string }
export interface ExperienceSource { id: string; rawValue: string; selector: string; context: string }
export interface ProjectExperience {
  id: string; version: number; enabled: boolean; operatorId: number; timestamp: string;
  packageVersion: string; sourcePath: string; digest: string;
  sources: ExperienceSource[]; before: ExperienceUnit[]; after: ExperienceUnit[];
  statusOperatorId?: number | null; statusTimestamp?: string | null;
  baselineId?: string | null; baselineKind?: string | null; baseRevision?: number | null;
}
export interface ExperienceReference { correction: ProjectExperience; targetSourceIds: string[]; conflict: boolean }
export interface ExperienceArchive { id: string; sha256: string; count: number; operatorId: number; timestamp: string | null }
export interface ProjectExperienceView { history: ProjectExperience[]; references: ExperienceReference[]; total: number; offset: number; activeCount: number; archives: ExperienceArchive[]; referenceWarning?: string | null; experienceEnabled: boolean }
export const projectExperiencesApi = {
  get: (siteId: number, sourcePath: string, offset = 0) => http.get<ProjectExperienceView>(`/api/admin/v2/sites/${siteId}/project-experiences`, { params: { sourcePath, offset, limit: 10 } }),
  status: (siteId: number, item: ProjectExperience, enabled: boolean) => http.put<void>(`/api/admin/v2/sites/${siteId}/project-experiences`, { packageVersion: item.packageVersion, sourcePath: item.sourcePath, id: item.id, version: item.version, enabled }),
  archive: (siteId: number, sourcePath: string) => http.post<ExperienceArchive>(`/api/admin/v2/sites/${siteId}/project-experiences/archive`, { sourcePath }),
};
