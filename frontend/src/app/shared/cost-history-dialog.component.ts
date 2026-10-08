import { ViewRefresh } from '../core/view-refresh';
import { Component, DestroyRef, inject, signal } from '@angular/core';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { ApiService, dateTime, decimal, errorMessage } from '../core/api.service';
import { ContextService } from '../core/context.service';
import { CostRevision } from '../core/models';
import { RealtimeService } from '../core/realtime.service';
import { IconComponent } from './icon.component';

@Component({selector:'app-cost-history-dialog',imports:[IconComponent],template:`
  <header class="dialog-head"><div><h2>История себестоимости</h2><p>{{data.name}}</p></div><button class="icon-button" aria-label="Закрыть" (click)="dialog.close()"><app-icon name="close" /></button></header>
  <div class="dialog-body">
    @if(error()){<div class="notice error" role="alert">{{error()}}<button class="btn" (click)="load()">Повторить</button></div>}
    @for(row of rows();track row.id){<article class="panel panel-body"><div class="data-pair"><span>{{row.validFrom}} — {{row.validTo || 'бессрочно'}}</span><strong>{{money(row.amount)}}</strong></div><div class="data-pair"><span>Собственные расходы на единицу</span><strong>{{money(row.extraExpense)}}</strong></div><p class="inline-note">Редакция {{row.revision}} · {{row.actor || 'Автор не указан'}} · {{date(row.createdAt)}}</p></article>}
    @empty{<p role="status">{{loading()?'Загружаем историю…':error()?'Данные не получены':'Редакций себестоимости пока нет.'}}</p>}
    <div class="pagination"><button class="btn" [disabled]="page()===0||loading()" (click)="changePage(-1)">Назад</button><span>Страница {{page()+1}} · записей {{total()}}</span><button class="btn" [disabled]="(page()+1)*50>=total()||loading()" (click)="changePage(1)">Далее</button></div>
  </div><footer class="dialog-footer"><button class="btn" (click)="dialog.close()">Закрыть</button></footer>
`})
export class CostHistoryDialogComponent {
  private readonly reads = new ViewRefresh(inject(DestroyRef));

  readonly data=inject<{offerId:string;name:string}>(MAT_DIALOG_DATA);
  readonly dialog=inject(MatDialogRef<CostHistoryDialogComponent>);
  private readonly api=inject(ApiService);private readonly context=inject(ContextService);
  readonly rows=signal<CostRevision[]>([]);readonly page=signal(0);readonly total=signal(0);
  readonly error=signal('');readonly loading=signal(true);readonly money=decimal;
  readonly date=(value:string|undefined)=>dateTime(value,this.context.account()?.timezone??'UTC');
  private epoch=0;
  constructor(){const stop=inject(RealtimeService).subscribe(['cost-revisions'],async()=>{await this.load();return !this.error();},false,{owner:this.reads,key:'load'});inject(DestroyRef).onDestroy(()=>{this.epoch++;stop();});}
  changePage(delta:number):void{this.page.update(page=>page+delta);void this.load();}
  load():Promise<void>{this.epoch++;return this.reads.run('load',()=>this.loadCurrent(),()=>!this.error());}
  private async loadCurrent():Promise<void>{const epoch=++this.epoch;this.loading.set(true);try{const page=await this.api.listCostRevisions({query:{offerId:this.data.offerId,page:this.page(),size:50,sort:'createdAt',direction:'desc'}});if(epoch!==this.epoch)return;this.rows.set(page.items);this.total.set(page.total);this.error.set('');}catch(error:unknown){if(epoch===this.epoch)this.error.set(errorMessage(error));}finally{if(epoch===this.epoch)this.loading.set(false);}}
}
