import { http } from '@/api/http';
import type { QueryFormActionTargetResult } from '@/api/templateQueryBinding';

export interface NativeFormSourceField {
  sourceKey: string;
  selector: string;
  label: string;
  kind: string;
  required: boolean;
  maxLength: number | null;
  options: string[];
  status: string;
  reason: string | null;
}

export interface NativeFormInspection {
  sourcePath: string;
  sourceDigest: string;
  menuId: number;
  forms: { selector: string; name: string; category: 'SAMPLE' | 'INQUIRY' | 'MESSAGE' | 'FORM'; fields: NativeFormSourceField[] }[];
  warnings: string[];
}

export interface NativeFormReceipt {
  siteId: number;
  menuId: number;
  packageVersion: string;
  sourcePath: string;
  sourceDigest: string;
  categoryId: number;
  formSelector: string;
  fields: (Omit<NativeFormSourceField, 'status' | 'reason'> & { fieldName: string })[];
}

const endpoint = (siteId: number) => `/api/admin/v2/sites/${siteId}/native-form-import`;

export const inspectNativeForms = (siteId: number, menuId: number, sourcePath: string): Promise<NativeFormInspection> =>
  http.get(endpoint(siteId), { params: { menuId, sourcePath } });

export const importNativeForm = (siteId: number, body: {
  menuId: number; sourcePath: string; sourceDigest: string; formSelector: string;
  name: string; fieldKeys: string[]; confirmed: boolean;
}): Promise<NativeFormReceipt> => http.post(endpoint(siteId), body);

export const checkNativeFormTarget = (siteId:number, body:{menuId:number;formCategoryId:number}): Promise<QueryFormActionTargetResult> =>
  http.post(`/api/admin/v2/sites/${siteId}/template-query/native-form-target/preview`, {...body, confirmed:false});

export const applyNativeFormTarget = (siteId:number, body:{
  menuId:number;formCategoryId:number;expectedTemplate:string|null;confirmed:boolean;
}): Promise<QueryFormActionTargetResult> =>
  http.post(`/api/admin/v2/sites/${siteId}/template-query/native-form-target/apply`, body);

