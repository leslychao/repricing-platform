import { Component, DestroyRef, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatDialogRef } from '@angular/material/dialog';
import { ApiService, errorMessage, mutation } from '../core/api.service';
import { ContextService } from '../core/context.service';
import { FeedbackService } from '../core/feedback.service';
import { TaxView } from '../core/models';
import { IconComponent } from './icon.component';
import { GuardedFormDialog } from './unsaved-changes';

@Component({selector:'app-tax-dialog',imports:[FormsModule,IconComponent],template:`
  <header class="dialog-head"><div><h2>Налоговый профиль</h2><p>{{context.account()?.name}}</p></div><button class="icon-button" aria-label="Закрыть" (click)="dialog.close()"><app-icon name="close" /></button></header>
  <form (ngSubmit)="save()"><div class="dialog-body">
    @if(error()){<div class="notice error" role="alert">{{error()}}@if(!current()){<button type="button" class="btn" [disabled]="loading()" (click)="loadCurrent()">Повторить чтение профиля</button>}</div>}
    @if(current();as profile){
      @for(interval of profile.intervals;track interval.validFrom){<div class="data-pair"><span>{{interval.validFrom}} — {{interval.validUntil || 'бессрочно'}}</span><strong>Ставка {{interval.rate}}</strong></div>}
      <label class="field">Новая ставка, доля от 0 до 1<input name="rate" inputmode="decimal" [(ngModel)]="rate" required></label>
      <div class="fields-2"><label class="field">Действует с<input name="from" type="date" [(ngModel)]="from" required></label><label class="field">Действует до · не включая<input name="until" type="date" [(ngModel)]="until"></label></div>
      <p class="inline-note">Учитывается дата в поясе {{context.account()?.timezone}}. Новая редакция не заменяет основания сохранённых решений.</p>
    }@else if(loading()){<p role="status">Загружаем действующую редакцию…</p>}
  </div><footer class="dialog-footer"><button type="button" class="btn" (click)="dialog.close()">Отмена</button><button type="submit" class="btn primary" [disabled]="saving()||!current()">Опубликовать редакцию</button></footer></form>
`})
export class TaxDialogComponent extends GuardedFormDialog {
  readonly context=inject(ContextService);
  readonly dialog=inject(MatDialogRef<TaxDialogComponent>);
  private readonly api=inject(ApiService);
  private readonly feedback=inject(FeedbackService);
  readonly current=signal<TaxView|null>(null);
  readonly error=signal('');readonly saving=signal(false);readonly loading=signal(false);private destroyed=false;
  rate='';from='';until='';
  constructor(){super();inject(DestroyRef).onDestroy(()=>this.destroyed=true);void this.loadCurrent();}
  async loadCurrent():Promise<void>{
    if(this.loading())return;
    this.loading.set(true);this.error.set('');
    try{const profile=await this.api.getCurrentTax();if(!this.destroyed)this.current.set(profile);}
    catch(error:unknown){if(!this.destroyed)this.error.set(errorMessage(error));}
    finally{if(!this.destroyed)this.loading.set(false);}
  }
  async save():Promise<void>{
    const current=this.current();if(!current||this.saving())return;
    this.saving.set(true);
    try{await this.api.createTaxRevision({body:{...mutation(current.revision),rate:this.rate,validFrom:this.from,validTo:this.until||null}});this.feedback.show('Редакция налога принята для фоновой проверки и публикации.');this.dialog.close(true);}
    catch(error:unknown){this.error.set(errorMessage(error));}
    finally{this.saving.set(false);}
  }
}
