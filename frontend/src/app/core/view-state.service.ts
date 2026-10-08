import { Temporal } from '@js-temporal/polyfill';
import { HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { ApiService, errorMessage, mutation } from './api.service';
import { ContextService } from './context.service';
import { SavedView, ViewState } from './models';
import { RequestScope } from '../api/angular-client.gen';

const GROUPS: (keyof ViewState)[] = ['query','columns','presentation','navigation'];
export function initialView(key='',timeZone='UTC'):ViewState{const events=['finance','payments','returns','audit','account-audit','tasks','imports','reports'];const dated=events.includes(key);const today=Temporal.Now.plainDateISO(timeZone);return{query:{search:'',filters:key==='analytics'?{month:today.toString().slice(0,7)}:dated?{from:today.subtract({days:29}).toString(),to:today.toString()}:{},sort:dated?'date,desc':'id,asc',page:0,size:50},columns:{order:[],hidden:[],widths:{}},presentation:{expanded:[]},navigation:{collapsed:false}};}
export function mergeViewGroups(base:ViewState,local:ViewState,remote:ViewState):{state:ViewState;conflicts:(keyof ViewState)[]} {
  const state=structuredClone(remote);const conflicts:(keyof ViewState)[]=[];
  for(const key of GROUPS){
    if(JSON.stringify(local[key])===JSON.stringify(base[key]))continue;
    if(JSON.stringify(remote[key])!==JSON.stringify(base[key])){conflicts.push(key);continue;}
    Object.assign(state,{[key]:structuredClone(local[key])});
  }
  return{state,conflicts};
}
export function validView(state:ViewState):boolean {
  if(new TextEncoder().encode(JSON.stringify(state)).length>65_536)return false;
  if(Object.keys(state.query.filters).length>20||state.query.sort.split(';').length>5||state.columns.order.length>50||state.columns.hidden.length>50||state.presentation.expanded.length>200)return false;
  if(state.query.size<1||state.query.size>200||state.query.page<0)return false;
  if(Object.values(state.columns.widths).some(width=>width<80||width>800))return false;
  return ![state.query.search,state.query.sort,...Object.values(state.query.filters),...state.columns.order,...state.presentation.expanded].some(value=>value.length>512);
}
interface PendingView {
  key:string;scope:RequestScope;owner:string;base:SavedView;local:ViewState;
  pending?:ViewState;running?:Promise<void>;timer?:ReturnType<typeof setTimeout>;firstAt?:number;
  unresolved?:{clientRequestId:string;expectedRevision:number;resetGeneration:number;state:ViewState};
  conflict:boolean;error:string;
}

@Injectable({providedIn:'root'})
export class ViewStateService {
  private readonly api=inject(ApiService);private readonly context=inject(ContextService);
  readonly status=signal('');readonly conflict=signal(false);private readonly entries=new Map<string,PendingView>();
  private current?:PendingView;private epoch=0;

  async open(key:string,accountScoped=true):Promise<ViewState>{
    const epoch=++this.epoch;const previous=this.current;if(previous)void this.flush(previous);
    this.current=undefined;this.status.set('');this.conflict.set(false);
    const organization=this.context.organization(),user=this.context.user();
    if(!organization||!user)throw new Error('Область не выбрана.');
    const scope:RequestScope={organizationId:organization.id,accountId:accountScoped?this.context.account()?.id:undefined};
    const identity=`${user.userId}/${scope.organizationId}/${scope.accountId??''}/${key}`;
    const existing=this.entries.get(identity);
    let remote:SavedView;
    try{remote=await this.api.getViewState({path:{key}},scope);}
    catch(error:unknown){if(!(error instanceof HttpErrorResponse)||error.status!==404)throw error;remote={schemaVersion:1,revision:0,resetGeneration:0,state:initialView(key,this.context.account()?.timezone??'UTC')};}
    if(epoch!==this.epoch)throw new Error('Открытие представления отменено.');
    if(remote.schemaVersion!==1||!validView(remote.state))throw new Error('Сохранённый вид требует совместимой версии приложения.');
    if(existing){
      this.current=existing;
      if(!existing.pending&&!existing.running&&!existing.unresolved&&!existing.conflict){existing.base=remote;existing.local=structuredClone(remote.state);}
      this.show(existing);return structuredClone(existing.local);
    }
    this.evictSaved();
    if(this.entries.size>=20||this.cacheBytes()+65_536>8*1024*1024){this.status.set('Вид не сохранён: достигнут предел ожидающих контекстов.');return structuredClone(remote.state);}
    const entry:PendingView={key,scope,owner:user.userId,base:remote,local:structuredClone(remote.state),conflict:false,error:''};
    this.entries.set(identity,entry);this.current=entry;return structuredClone(remote.state);
  }

  save(state:ViewState):void{
    const entry=this.current;
    if(!entry){this.status.set('Вид не сохранён: нет свободного контекста.');return;}
    if(!validView(state)){this.status.set('Вид не сохранён: превышены допустимые размеры.');return;}
    entry.local=structuredClone(state);entry.pending=structuredClone(state);entry.error='';
    this.show(entry);if(entry.conflict)return;
    const now=Date.now();entry.firstAt??=now;clearTimeout(entry.timer);
    entry.timer=setTimeout(()=>void this.flush(entry),Math.max(0,Math.min(500,2000-(now-entry.firstAt))));
  }

  retry():void{if(this.current)void this.flush(this.current);}
  async resolveConflict():Promise<void>{
    const entry=this.current;if(!entry||entry.running)return;
    try{const remote=await this.api.getViewState({path:{key:entry.key}},entry.scope);entry.base=remote;entry.conflict=false;entry.pending=structuredClone(entry.local);entry.unresolved=undefined;await this.flush(entry);}catch(error:unknown){entry.error=errorMessage(error);this.show(entry);}
  }
  async reset():Promise<ViewState|null>{
    const entry=this.current;if(!entry)return null;
    await this.flush(entry);if(entry.unresolved||entry.pending||entry.running){entry.error='Сначала требуется разрешить исход сохранения.';this.show(entry);return null;}
    try{const result=await this.api.resetViewState({path:{key:entry.key},body:mutation(entry.base.revision)},entry.scope);entry.base=result;entry.local=structuredClone(result.state);entry.conflict=false;entry.error='';this.show(entry);return structuredClone(result.state);}catch(error:unknown){entry.error=errorMessage(error);this.show(entry);return null;}
  }

  private async flush(entry:PendingView):Promise<void>{
    clearTimeout(entry.timer);entry.firstAt=undefined;
    if(entry.running)return entry.running;
    if(entry.conflict||(!entry.pending&&!entry.unresolved)||entry.owner!==this.context.user()?.userId)return;
    const execute=async()=>{
      let payload=entry.unresolved??{...mutation(entry.base.revision),resetGeneration:entry.base.resetGeneration,state:entry.pending??entry.local};
      entry.pending=undefined;entry.unresolved=payload;let conflicts=0;
      for(let attempt=0;attempt<3;attempt++){
        if(entry.owner!==this.context.user()?.userId)return;
        try{
          const result=await this.api.saveViewState({path:{key:entry.key},body:payload},entry.scope);
          entry.base=result;entry.unresolved=undefined;entry.error='';return;
        }catch(error:unknown){
          if(error instanceof HttpErrorResponse&&[409,412].includes(error.status)){
            const remote=await this.api.getViewState({path:{key:entry.key}},entry.scope);
            if(remote.resetGeneration!==entry.base.resetGeneration){entry.base=remote;entry.conflict=true;entry.error='Вид сброшен в другом окне. Ваши изменения не сохранены.';entry.unresolved=undefined;return;}
            const merged=mergeViewGroups(entry.base.state,payload.state,remote.state);
            if(merged.conflicts.length||conflicts++>=2){entry.base=remote;entry.conflict=true;entry.error='Те же группы вида изменены в другом окне. Ваш экран сохранён локально.';entry.unresolved=undefined;return;}
            entry.base=remote;payload={...mutation(remote.revision),resetGeneration:remote.resetGeneration,state:merged.state};entry.unresolved=payload;continue;
          }
          const retryAfter=error instanceof HttpErrorResponse?Number(error.headers.get('Retry-After')??0):0;
          if(!(error instanceof HttpErrorResponse)||![0,429,500,502,503,504].includes(error.status)||retryAfter>2||attempt===2)throw error;
          await new Promise<void>(resolve=>setTimeout(resolve,(attempt+1)*1000));
        }
      }
      throw new Error('Достигнут предел повторов сохранения вида.');
    };
    entry.running=execute().catch((error:unknown)=>{entry.error=`Вид не сохранён. ${errorMessage(error)}`;}).finally(()=>{
      entry.running=undefined;this.show(entry);
      if(entry.pending&&!entry.error&&!entry.conflict)void this.flush(entry);
    });
    this.show(entry);return entry.running;
  }
  private show(entry:PendingView):void{if(entry!==this.current)return;this.conflict.set(entry.conflict);this.status.set(entry.error||((entry.pending||entry.running||entry.unresolved)?'Изменения вида сохраняются…':''));}
  private evictSaved():void{if(this.entries.size<20)return;for(const[key,entry]of this.entries){if(entry!==this.current&&!entry.pending&&!entry.running&&!entry.unresolved&&!entry.conflict){this.entries.delete(key);return;}}}
  private cacheBytes():number{return[...this.entries.values()].reduce((sum,entry)=>sum+new TextEncoder().encode(JSON.stringify({base:entry.base,local:entry.local,pending:entry.pending,outgoing:entry.unresolved})).length,0);}
  clear():void{this.epoch++;for(const entry of this.entries.values()){clearTimeout(entry.timer);entry.pending=undefined;entry.owner='';}this.entries.clear();this.current=undefined;this.status.set('');this.conflict.set(false);}
}
