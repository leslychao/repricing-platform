import { ViewRefresh } from '../core/view-refresh';
import { Component, DestroyRef, inject, signal } from '@angular/core';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { ApiService, errorMessage, operationMessage, mutation, stateLabel } from '../core/api.service';
import { Assignment, AutoActivationReview, AutoGrantState, BackgroundOperation } from '../core/models';
import { RealtimeService } from '../core/realtime.service';
import { IconComponent } from './icon.component';

@Component({selector:'app-auto-grant-dialog',imports:[IconComponent],template:`
  <header class="dialog-head"><div><h2>Разрешение AUTO</h2><p>{{assignment().targetName || assignment().targetId || 'Весь кабинет'}}</p></div><button class="icon-button" aria-label="Закрыть" (click)="dialog.close()"><app-icon name="close" /></button></header>
  <div class="dialog-body">
    @if(error()){<div class="notice error" role="alert">{{error()}}</div>}
    <p class="inline-note">Сохранение режима AUTO само по себе не разрешает отправлять цены. Допуск выдаётся отдельно на проверенную версию политики и полный состав коммерческих целей.</p>
    @if(state()?.grant;as grant){
      <div class="data-pair"><span>Последнее разрешение</span><strong>{{grant.active?'Выдано':'Отозвано'}} · редакция {{grant.revision}}</strong></div>
      <div class="data-pair"><span>Версия политики / целей</span><strong>{{grant.binding.policyVersion}} / {{grant.binding.targetIds.length}}</strong></div>
      <p class="inline-note">Фактический допуск и полномочия автора проверяются сервером перед каждым действием.</p>
    }
    @if(operation();as operation){<div class="notice" role="status">{{label(operation.status)}}. {{operationMessage(operation.message)}}</div>}
    @if(review();as review){
      <h3>Подтвердите проверенный состав</h3>
      <div class="data-pair"><span>Версия политики</span><strong>{{review.policyVersion}}</strong></div>
      <div class="data-pair"><span>Коммерческих целей</span><strong>{{review.targetsCount}}</strong></div>
      <div class="data-pair"><span>Операции</span><strong>{{review.operations.join(', ')}}</strong></div>
      <details><summary>Идентификаторы целей</summary>@for(id of review.targetIds;track id){<p class="inline-note">{{id}}</p>}</details>
      <p class="inline-note">Если состав, назначение или версия политики изменятся до выдачи допуска, сервер отклонит подтверждение.</p>
    }
  </div>
  <footer class="dialog-footer">@if(state()?.grant?.active){<button class="btn danger" [disabled]="saving()" (click)="revoke()">Отозвать AUTO</button>}<span class="spacer"></span><button class="btn" (click)="dialog.close()">Закрыть</button>@if(!operationId()){<button class="btn primary" [disabled]="saving()" (click)="review()?activate():prepare()">{{review()?'Разрешить AUTO для этого состава':'Проверить назначение'}}</button>}</footer>
`})
export class AutoGrantDialogComponent {
  private readonly reads = new ViewRefresh(inject(DestroyRef));

  readonly assignment=signal(inject<Assignment>(MAT_DIALOG_DATA));readonly dialog=inject(MatDialogRef<AutoGrantDialogComponent>);
  private readonly api=inject(ApiService);readonly state=signal<AutoGrantState|null>(null);
  readonly review=signal<AutoActivationReview|null>(null);readonly operationId=signal<string|null>(null);
  readonly operationMessage=operationMessage;readonly operation=signal<BackgroundOperation|null>(null);readonly saving=signal(false);readonly error=signal('');readonly label=stateLabel;
  private epoch=0;
  constructor(){const stop=inject(RealtimeService).subscribe(['policy-assignments','operations'],async()=>{await this.refresh();return !this.error();},false,{owner:this.reads,key:'refresh'});inject(DestroyRef).onDestroy(()=>{this.epoch++;stop();});}
  private refresh():Promise<void>{this.epoch++;return this.reads.run('refresh',()=>this.refreshCurrent(),()=>!this.error());}
  private async refreshCurrent():Promise<void>{
    const epoch=++this.epoch;
    try{const [assignment,state]=await Promise.all([this.api.getAssignment({path:{id:this.assignment().id}}),this.api.getAutoGrant({path:{id:this.assignment().id}})]);if(epoch!==this.epoch)return;
      if(assignment.revision!==this.assignment().revision)this.review.set(null);this.assignment.set(assignment);this.state.set(state);
      const id=this.operationId();if(id){const operation=await this.api.getOperation({path:{id}});if(epoch===this.epoch){this.operation.set(operation);if(['FAILED','CANCELLED'].includes(operation.status)){this.operationId.set(null);this.review.set(null);}}}
    }catch(error:unknown){if(epoch===this.epoch)this.error.set(errorMessage(error));}
  }
  async prepare():Promise<void>{
    if(this.saving())return;this.saving.set(true);this.error.set('');
    try{let assignment=this.assignment();if(assignment.status!=='ACTIVE')throw new Error('Сначала возобновите назначение.');
      if(assignment.mode!=='AUTO'){assignment=await this.api.createAssignment({body:{...mutation(assignment.revision),policyId:assignment.policyId,accountId:assignment.accountId,scope:assignment.scope,targetId:assignment.targetId??null,mode:'AUTO',regularReferences:assignment.regularReferences,temporaryReferences:assignment.temporaryReferences}});this.assignment.set(assignment);}
      const [review,state]=await Promise.all([this.api.previewAuto({path:{id:assignment.id},query:{expectedRevision:assignment.revision}}),this.api.getAutoGrant({path:{id:assignment.id}})]);this.review.set(review);this.state.set(state);
    }catch(error:unknown){this.error.set(errorMessage(error));}finally{this.saving.set(false);}
  }
  async activate():Promise<void>{
    const review=this.review();if(!review||this.saving())return;this.saving.set(true);this.error.set('');
    try{const result=await this.api.activateAssignment({path:{id:review.assignmentId},body:{...mutation(review.assignmentRevision),expectedGrantRevision:this.state()?.grant?.revision??0,expectedPolicyVersion:review.policyVersion,expectedScopeDigest:review.scopeDigest}});this.operationId.set(result.operationId);this.review.set(null);await this.refresh();}catch(error:unknown){this.error.set(errorMessage(error));}finally{this.saving.set(false);}
  }
  async revoke():Promise<void>{const grant=this.state()?.grant;if(!grant||this.saving())return;this.saving.set(true);try{this.state.set(await this.api.revokeAutoGrant({path:{id:this.assignment().id},body:mutation(grant.revision)}));this.review.set(null);this.operationId.set(null);}catch(error:unknown){this.error.set(errorMessage(error));}finally{this.saving.set(false);}}
}
