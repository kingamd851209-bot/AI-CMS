export interface WorkbenchScope { admin: string; site: string; version: string }
export interface WorkbenchLocation { sourcePath: string; menuId?: number; step: number; planId?: string }
export interface WorkbenchDraft<T> { digest: string; revision: number; units: T[]; unitsDirty: boolean; menuId?: number; planId?: string; fieldChoices: Record<string,string>; choicesDirty: boolean }
const prefix = (scope: WorkbenchScope) => `cms-build:v1:${encodeURIComponent(scope.admin)}:${encodeURIComponent(scope.site)}:`;
const scopeKey = (scope: WorkbenchScope) => prefix(scope) + encodeURIComponent(scope.version) + ':';
export const draftKey = (scope: WorkbenchScope, path: string) => scopeKey(scope) + 'draft:' + encodeURIComponent(path);
export const analysisKey = (scope: WorkbenchScope, path: string, digest: string) => scopeKey(scope) + 'analysis:' + encodeURIComponent(path) + ':' + digest;
export function saveWorkbenchLocation(storage: Storage, scope: WorkbenchScope, state: WorkbenchLocation) {
  storage.setItem(scopeKey(scope)+'location',JSON.stringify(state));
}
export function readWorkbenchLocation(storage: Storage, scope: WorkbenchScope, pages: string[], menus: number[]) {
  const notices: string[]=[];
  const keys=Array.from({length:storage.length},(_,index)=>storage.key(index)).filter((key):key is string=>typeof key==='string');
  for(const key of keys) if(key.startsWith(prefix(scope))&&!key.startsWith(scopeKey(scope))){
    if(!key.includes(':draft:'))storage.removeItem(key);
    if(!notices.length)notices.push('页面包已变更，旧继续位置不再应用；未保存的旧版修正仍保留在此浏览器，未删除或覆盖。');
  }
  let state: WorkbenchLocation | null=null;
  const key=scopeKey(scope)+'location';
  try { const raw=storage.getItem(key); if(raw){const value=JSON.parse(raw);if(typeof value.sourcePath!=='string'||!Number.isInteger(value.step)||value.step<0||value.step>7)throw Error();state=value;} }
  catch {storage.removeItem(key);notices.push('本机继续位置已损坏，已清除；服务端方案不受影响。');}
  if(state?.sourcePath&&!pages.includes(state.sourcePath)){state={sourcePath:'',step:5};notices.push('上次页面已不在当前包中，已清除其继续位置；旧临时修正仍保留供人工找回。');}
  if(state?.menuId&&!menus.includes(state.menuId)){state={...state,menuId:undefined,planId:undefined};notices.push('上次目标栏目已删除或不可访问，已清除栏目与计划引用。');}
  return {state,notices};
}
export function readWorkbenchDraft<T>(storage:Storage,scope:WorkbenchScope,path:string):WorkbenchDraft<T>|null {
 const raw=storage.getItem(draftKey(scope,path));if(!raw)return null;
 try{if(raw.length>1_000_000)throw Error();const value=JSON.parse(raw);if(typeof value.digest!=='string'||!Number.isInteger(value.revision)||!Array.isArray(value.units)||value.units.length>200)throw Error();return value;}
 catch {throw Error('本机临时修正不可读取，原数据仍保留；服务端方案保持原样。');}
}
export function saveWorkbenchDraft<T>(storage:Storage,scope:WorkbenchScope,path:string,draft:WorkbenchDraft<T>){
 const key=draftKey(scope,path);if(!draft.unitsDirty&&!draft.choicesDirty){storage.removeItem(key);return;}
 const json=JSON.stringify(draft);if(json.length>1_000_000)throw Error('临时修正超过本机保存上限，请先保存人工修正。');storage.setItem(key,json);
}
