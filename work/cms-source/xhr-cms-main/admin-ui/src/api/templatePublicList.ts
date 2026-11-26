import { http } from '@/api/http';
export interface PublicListResult { applied:boolean;templatePath:string|null;publicUrl:string;requiresRouteReload:boolean;message:string }
const base=(site:number,id:string)=>`/api/admin/v2/sites/${site}/template-seed/query-bindings/plans/${encodeURIComponent(id)}/public-application`;
export const inspectPublicList=(site:number,id:string):Promise<PublicListResult>=>http.get(base(site,id));
export const applyPublicList=(site:number,id:string):Promise<PublicListResult>=>http.post(base(site,id),{confirmed:true});
export const withdrawPublicList=(site:number,id:string,packageVersion:string):Promise<PublicListResult>=>http.post(base(site,id)+'/withdraw',{confirmed:true,packageVersion});

export interface PublicListReadiness {
  publishedRecords:number;
  moreRecords:boolean;
  detailConfigured:boolean;
  message:string;
}
export interface SharedDetailLayout {
  token:string;
  sourceMenuId:number;
  sourceTemplate:string;
  targetTemplate:string;
  targetRoute:string;
  applied:boolean;
  omittedRegions?:{selector:string;preview:string}[];
  returnUrl?:string|null;
  replacementRequiresWithdrawal?:boolean;
}
export const readPublicListReadiness=(site:number,id:string):Promise<PublicListReadiness>=>
  http.get(base(site,id)+'/readiness');
export const previewSharedDetailLayout=(site:number,id:string):Promise<SharedDetailLayout>=>
  http.get(base(site,id)+'/shared-detail');
export const applySharedDetailLayout=(site:number,id:string,expectedToken:string):Promise<SharedDetailLayout>=>
  http.post(base(site,id)+'/shared-detail',{confirmed:true,expectedToken});
export const withdrawSharedDetailLayout=(site:number,id:string,packageVersion?:string):Promise<boolean>=>
  http.post(base(site,id)+'/shared-detail/withdraw',{confirmed:true,packageVersion});
