import { ViewRefresh } from '../core/view-refresh';
import { Component, DestroyRef, inject, signal } from '@angular/core';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { RouterLink } from '@angular/router';
import { ApiService, errorMessage, operationMessage, stateLabel } from '../core/api.service';
import { AssignmentBatch, AssignmentBatchRow, BackgroundOperation } from '../core/models';
import { RealtimeService } from '../core/realtime.service';
import { IconComponent } from './icon.component';

@Component({selector:'app-assignment-batch-dialog',imports:[IconComponent,RouterLink],template:`
  <header class="dialog-head"><div><h2>Результаты пакетной операции</h2><p>{{batch()?.kind==='PREVIEW'?'Расчёт полного охвата назначения':'Назначение политики зафиксированной выборке'}}</p></div><button class="icon-button" aria-label="Закрыть" (click)="dialog.close()"><app-icon name="close" /></button></header>
  <div class="dialog-body">
    @if(error()){<div class="notice error" role="alert">{{error()}}</div>}
    @if(batch();as value){
      <div class="data-pair"><span>Состояние пакета</span><strong>{{label(value.state)}}</strong></div>
      <div class="data-pair"><span>Фоновая задача</span><strong>{{label(operation()?.status)}}</strong></div>
      @if(operation()?.message){<p class="inline-note">{{operationMessage(operation()?.message)}}</p>}
      <div class="metric-grid"><div class="metric"><span>Зафиксировано</span><strong>{{value.captured}} / {{value.total}}</strong></div><div class="metric"><span>{{value.kind==='PREVIEW'?'Расчётов готово':'Применено'}}</span><strong>{{value.applied}}</strong></div><div class="metric"><span>Не применено</span><strong>{{value.failed}}</strong></div></div>
      <p class="inline-note">Сначала фиксируется весь состав и редакции. Изменившиеся строки получают отдельный результат. Готовый расчёт не означает применение цены.</p>
      @for(row of rows();track row.id){<div class="panel"><div class="data-pair"><span>Товар</span><a [routerLink]="['/products',row.offerId]" (click)="dialog.close()">{{row.offerId}}</a></div>@if(row.targetId){<div class="data-pair"><span>Цель управления</span><span>{{row.targetId}}</span></div>}<div class="data-pair"><span>Результат</span><strong>{{label(row.state)}}</strong></div>@if(row.reason){<p class="inline-note">{{row.reason}}</p>}@if(row.state==='PREVIEW_READY'&&row.operationId){<a class="btn" [routerLink]="['/decisions',row.operationId]" (click)="dialog.close()">Открыть сохранённый расчёт</a>}</div>}
      <div class="pagination"><button class="btn" [disabled]="page()===0||loading()" (click)="changePage(-1)">Назад</button><span>Страница {{page()+1}}</span><button class="btn" [disabled]="(page()+1)*50>=value.captured||loading()" (click)="changePage(1)">Далее</button></div>
    }@else if(loading()){<p role="status">Загружаем состояние операции…</p>}
  </div><footer class="dialog-footer"><button class="btn" (click)="dialog.close()">Закрыть</button></footer>
`})
export class AssignmentBatchDialogComponent {
  readonly data=inject<{id:string}>(MAT_DIALOG_DATA);
  readonly dialog=inject(MatDialogRef<AssignmentBatchDialogComponent>);
  private readonly api=inject(ApiService);
  readonly batch=signal<AssignmentBatch|null>(null);
  readonly operationMessage=operationMessage;readonly operation=signal<BackgroundOperation|null>(null);
  readonly rows=signal<AssignmentBatchRow[]>([]);
  readonly page=signal(0);readonly loading=signal(false);readonly error=signal('');
  readonly label=stateLabel;
  private epoch=0;private wanted=0;private readonly reads=new ViewRefresh(inject(DestroyRef));
  constructor(){const stop=inject(RealtimeService).subscribe(['policy-assignments','operations'],async()=>{await this.load();return !this.error();},false,{owner:this.reads,key:'batch'});inject(DestroyRef).onDestroy(()=>{this.epoch++;stop();});}
  changePage(delta:number):void{this.wanted++;this.page.update(page=>Math.max(0,page+delta));void this.load();}
  private load():Promise<void>{this.wanted++;return this.reads.run('batch',()=>this.loadCurrent(),()=>!this.error());}
  private async loadCurrent():Promise<void>{
    this.loading.set(true);const epoch=this.epoch,wanted=this.wanted;
    try{const [batch,rows,operation]=await Promise.all([this.api.getAssignmentBatch({path:{id:this.data.id}}),this.api.listAssignmentBatchRows({path:{id:this.data.id},query:{page:this.page(),size:50}}),this.api.getOperation({path:{id:this.data.id}})]);if(epoch!==this.epoch||wanted!==this.wanted)return;this.batch.set(batch);this.rows.set(rows.items);this.operation.set(operation);this.error.set('');}
    catch(error:unknown){if(epoch===this.epoch&&wanted===this.wanted)this.error.set(errorMessage(error));}
    finally{if(epoch===this.epoch){this.loading.set(false);}}
  }
}
