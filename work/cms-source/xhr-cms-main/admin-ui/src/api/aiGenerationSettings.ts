import {http} from '@/api/http';
export interface AiGenerationSettings {enabled:boolean;baseUrl:string;modelName:string;configured:boolean;timeoutMs:number;maxRetries:number;source:'SYSTEM'|'ENVIRONMENT'}
export interface AiGenerationSettingsRequest {enabled:boolean;baseUrl:string;modelName:string;apiKey:string;clearApiKey:boolean;timeoutMs:number;maxRetries:number}
const base='/api/admin/v2/ai-generation/settings';
export const readAiGenerationSettings=():Promise<AiGenerationSettings>=>http.get(base);
export const saveAiGenerationSettings=(body:AiGenerationSettingsRequest):Promise<AiGenerationSettings>=>http.post(base,body);
export const testAiGenerationSettings=():Promise<{success:boolean;message:string;durationMs:number;statusCode:number|null}>=>http.post(base+'/test');
