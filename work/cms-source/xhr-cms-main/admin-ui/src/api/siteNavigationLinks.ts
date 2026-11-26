import { http } from '@/api/http';

export interface NavigationLinkSelection { selector:string; rawHref:string; nodeKey:string }
export interface NavigationLinkReview { digest:string; changes:(NavigationLinkSelection & {menuId:number;label:string;url:string})[] }
export interface NavigationLinkEvidence {
  sourcePath:string; sourceDigest:string; navigationRevision:number;
  navigationStatus?:'DRAFT'|'CONFIRMED'|'APPLIED';
  anchors:{selector:string;rawHref:string;label:string;region:string;suggestedNodeKey?:string|null;eligibleTargetKeys?:string[];problem?:string|null;repair?:{sourcePath:string;reason:string}|null}[];
  targets:{nodeKey:string;menuId:number;label:string;url:string;kind?:'CONTENT_LIST'|'NATIVE_FORM'|'PARENT_ENTRY'|'FRAGMENT'}[];
  confirmed?:NavigationLinkReview|null;
}
export interface NavigationLinkRequest {
  packageVersion:string; sourcePath:string; sourceDigest:string; navigationRevision:number;
  selections:NavigationLinkSelection[]; previousDigest?:string;
}
const endpoint=(siteId:number,planId:string)=>
  `/api/admin/v2/sites/${siteId}/template-seed/site-model/plans/${encodeURIComponent(planId)}/navigation-links`;
export const readNavigationLinks=(siteId:number,planId:string,packageVersion:string,sourcePath:string)=>
  http.get<NavigationLinkEvidence>(endpoint(siteId,planId),{params:{packageVersion,sourcePath}});
export const previewNavigationLinks=(siteId:number,planId:string,body:NavigationLinkRequest)=>
  http.post<NavigationLinkReview>(endpoint(siteId,planId)+'/preview',body);
export const confirmNavigationLinks=(siteId:number,planId:string,body:NavigationLinkRequest,digest:string)=>
  http.post<NavigationLinkReview>(endpoint(siteId,planId)+'/confirm',{...body,digest,confirmed:true});
