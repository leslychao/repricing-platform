import { HttpErrorResponse, HttpInterceptorFn, HttpRequest, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { catchError, from, switchMap, tap, throwError } from 'rxjs';
import { ContextService } from './context.service';

@Injectable({providedIn:'root'})
export class MutationIntents {
  private readonly pending=new Map<string,string>();
  private subject='';private generation=0;
  async prepare(request:HttpRequest<unknown>,subject=''):Promise<{request:HttpRequest<unknown>;key?:string}>{
    const body=request.body;
    if(!request.url.startsWith('/api/v1')||!['POST','PUT','PATCH','DELETE'].includes(request.method)||typeof body!=='object'||body===null||!('clientRequestId' in body)||typeof body.clientRequestId!=='string')return{request};
    if(subject!==this.subject){this.clear();this.subject=subject;}
    const generation=this.generation;
    const {clientRequestId,...intent}=body;
    const input=JSON.stringify([subject,request.method,request.urlWithParams,request.headers.get('X-Organization-Id'),request.headers.get('X-Account-Id'),intent],(_key,value:unknown)=>typeof value==='object'&&value!==null&&!Array.isArray(value)?Object.fromEntries(Object.entries(value).sort(([left],[right])=>left.localeCompare(right))):value);
    const digest=await crypto.subtle.digest('SHA-256',new TextEncoder().encode(input));
    if(generation!==this.generation)throw new Error('Контекст входа изменился. Повторите действие в текущей сессии.');
    const key=Array.from(new Uint8Array(digest),value=>value.toString(16).padStart(2,'0')).join('');
    let stable=this.pending.get(key);
    if(!stable){if(this.pending.size>=256)throw new Error('Слишком много операций с неизвестным исходом. Проверьте фоновые задачи.');stable=clientRequestId;this.pending.set(key,stable);}
    return{request:request.clone({body:{...intent,clientRequestId:stable}}),key};
  }
  resolve(key?:string):void{if(key)this.pending.delete(key);}
  clear():void{this.pending.clear();this.generation++;}
}

export const mutationInterceptor:HttpInterceptorFn=(request,next)=>{
  const intents=inject(MutationIntents);
  const subject=inject(ContextService).user()?.userId??'';
  return from(intents.prepare(request,subject)).pipe(switchMap(prepared=>next(prepared.request).pipe(
    tap(event=>{if(event instanceof HttpResponse)intents.resolve(prepared.key);}),
    catchError((error:unknown)=>{if(error instanceof HttpErrorResponse&&[400,401,403,404,409,412,422].includes(error.status))intents.resolve(prepared.key);return throwError(()=>error);}),
  )));
};
