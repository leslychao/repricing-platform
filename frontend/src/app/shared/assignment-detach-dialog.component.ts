import { Component, DestroyRef, inject, signal } from '@angular/core';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { ApiService, errorMessage, mutation, stateLabel } from '../core/api.service';
import { Assignment, AssignmentDetachReview } from '../core/models';
import { IconComponent } from './icon.component';

@Component({selector:'app-assignment-detach-dialog',imports:[IconComponent],template:`
  <header class="dialog-head"><div><h2>Снять назначение</h2><p>{{assignment.targetName||assignment.targetId||'Весь кабинет'}}</p></div><button class="icon-button" aria-label="Закрыть" (click)="dialog.close()"><app-icon name="close" /></button></header>
  <div class="dialog-body">
    @if(error()){<div class="notice error" role="alert">{{error()}}</div>}
    @if(loading()){<p role="status">Проверяем наследование…</p>}
    @if(review();as result){
      @if(result.inherited;as inherited){
        <h3>После снятия действует</h3><div class="data-pair"><span>Политика</span><strong>{{inherited.policyName}} · версия {{inherited.policyVersion}}</strong></div>
        <div class="data-pair"><span>Область</span><strong>{{inherited.scope==='ACCOUNT'?'Весь кабинет':'Родительская категория'}}</strong></div>
        <div class="data-pair"><span>Режим / состояние</span><strong>{{label(inherited.mode)}} · {{label(inherited.status)}}</strong></div>
        @if(inherited.status!=='ACTIVE'){<p class="notice">Родительское назначение не разрешает управление в своём текущем состоянии.</p>}
      }@else{<p class="notice">Родительского назначения нет. В этой области управление ценами прекратится.</p>}
      <p class="inline-note">Более точные назначения дочерних категорий и товаров сохраняются. История решений и команд остаётся доступной. Снятие не отправляет команду площадке.</p>
    }
  </div>
  <footer class="dialog-footer"><button class="btn" [disabled]="loading()||saving()" (click)="prepare()">Обновить проверку</button><span class="spacer"></span><button class="btn" (click)="dialog.close()">Отмена</button><button class="btn danger" [disabled]="!review()||loading()||saving()" (click)="confirm()">Снять назначение</button></footer>`,
})
export class AssignmentDetachDialogComponent {
  readonly assignment = inject<Assignment>(MAT_DIALOG_DATA);
  readonly dialog = inject(MatDialogRef<AssignmentDetachDialogComponent,boolean>);
  private readonly api = inject(ApiService);
  private readonly destroyRef = inject(DestroyRef);
  readonly review=signal<AssignmentDetachReview|null>(null);
  readonly loading=signal(false);readonly saving=signal(false);readonly error=signal('');
  readonly label=stateLabel;
  constructor(){void this.prepare();}
  async prepare():Promise<void>{
    if(this.loading()||this.saving())return;this.loading.set(true);this.error.set('');this.review.set(null);
    try{const result=await this.api.reviewAssignmentDetach({path:{id:this.assignment.id},query:{expectedRevision:this.assignment.revision}});if(!this.destroyRef.destroyed)this.review.set(result);}
    catch(error:unknown){if(!this.destroyRef.destroyed)this.error.set(errorMessage(error));}
    finally{if(!this.destroyRef.destroyed)this.loading.set(false);}
  }
  async confirm():Promise<void>{
    const review=this.review();if(!review||this.saving())return;this.saving.set(true);this.error.set('');
    try{await this.api.detachAssignment({path:{id:review.assignmentId},body:{...mutation(review.assignmentRevision),expectedInheritanceDigest:review.digest}});if(!this.destroyRef.destroyed)this.dialog.close(true);}
    catch(error:unknown){if(!this.destroyRef.destroyed)this.error.set(errorMessage(error));}
    finally{if(!this.destroyRef.destroyed)this.saving.set(false);}
  }
}
