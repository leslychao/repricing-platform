import { Component, DestroyRef, effect, inject, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ApiService, dateTime, decimal, errorMessage } from '../core/api.service';
import { ContextService } from '../core/context.service';
import { QuantityObligation, QuantityReservation } from '../core/models';
import { RealtimeService } from '../core/realtime.service';
import { ViewRefresh } from '../core/view-refresh';
import { IconComponent } from '../shared/icon.component';

@Component({selector:'app-quantity-commitments',imports:[RouterLink,IconComponent],template:`
  <section class="panel"><header class="panel-head"><app-icon name="box" /><h2>Удержания и обязательства количества</h2></header><div class="panel-body">
    <p class="inline-note">Показан конкретный общий пул. Удержание после перехода в обязательство не считается вторым занятым количеством. Исторический объём завершённой записи не равен текущему занятому остатку.</p>
    @if(!context.allows('catalog.read')){<p class="muted">Нет права читать запасы.</p>}
    @else{
      @if(error()){<div class="notice error" role="alert">{{error()}}<button class="btn" (click)="load()">Повторить</button></div>}
      <div class="cols-2"><section><h3>Удержания перед подтверждённым эффектом</h3>
        @for(row of reservations();track row.commandId+row.poolId){<details><summary>{{quantity(row.quantity)}} · {{status(row.state)}}</summary>
          <div class="data-pair"><span>Общий пул</span><strong>{{row.poolId}}</strong></div>
          <div class="data-pair"><span>Редакция остатка при удержании</span><strong>{{row.stockRevision}}</strong></div>
          <div class="data-pair"><span>Команда</span>@if(context.allows('command.read')){<a [routerLink]="['/commands',row.commandId]">{{row.commandId}}</a>}@else{<strong>{{row.commandId}}</strong>}</div>
        </details>}@empty{@if(!loading()&&!error()){<p class="muted">Удержаний в выбранной области нет.</p>}}
        <div class="pagination"><span>{{reservationTotal()}} записей</span><button class="btn" [disabled]="loading()||reservationPage()===0" (click)="reservationPage.set(reservationPage()-1);load()">Назад</button><button class="btn" [disabled]="loading()||(reservationPage()+1)*50>=reservationTotal()" (click)="reservationPage.set(reservationPage()+1);load()">Далее</button></div>
      </section><section><h3>Внешние обязательства</h3>
        @for(row of obligations();track row.id){<details><summary>{{quantity(row.quantity)}} · {{status(row.state)}}</summary>
          <div class="data-pair"><span>Общий пул</span><strong>{{row.poolId}}</strong></div>
          <div class="data-pair"><span>Осталось по последнему доказательству</span><strong>{{quantity(row.remainingQuantity)}}</strong></div>
          <div class="data-pair"><span>Приём новых единиц закрыт</span><strong>{{proof(row.admissionClosed)}}</strong></div>
          <div class="data-pair"><span>Учтено в остатке</span><strong>{{row.reflectedInStock?'Подтверждено для редакции '+(row.reflectedStockRevision??'не указана'):'Подтверждения нет'}}</strong></div>
          <div class="data-pair"><span>Редакция / время доказательства</span><strong>{{row.sourceRevision??'Нет данных'}} · {{date(row.observedAt)}}</strong></div>
          <div class="data-pair"><span>Команда</span>@if(context.allows('command.read')){<a [routerLink]="['/commands',row.commandId]">{{row.commandId}}</a>}@else{<strong>{{row.commandId}}</strong>}</div>
        </details>}@empty{@if(!loading()&&!error()){<p class="muted">Обязательств в выбранной области нет.</p>}}
        <div class="pagination"><span>{{obligationTotal()}} записей</span><button class="btn" [disabled]="loading()||obligationPage()===0" (click)="obligationPage.set(obligationPage()-1);load()">Назад</button><button class="btn" [disabled]="loading()||(obligationPage()+1)*50>=obligationTotal()" (click)="obligationPage.set(obligationPage()+1);load()">Далее</button></div>
      </section></div>
      @if(loading()){<p role="status">Читаем количественные обязательства…</p>}
      <p class="inline-note">Неизвестный результат отправки не освобождает запас. Завершение определяется подтверждённым состоянием площадки.</p>
    }
  </div></section>
`})
export class QuantityCommitmentsComponent {
  readonly offerId=input.required<string>();readonly context=inject(ContextService);
  private readonly api=inject(ApiService);private readonly realtime=inject(RealtimeService);
  private readonly reads=new ViewRefresh(inject(DestroyRef));private epoch=0;
  readonly reservations=signal<QuantityReservation[]>([]);readonly obligations=signal<QuantityObligation[]>([]);
  readonly reservationPage=signal(0);readonly obligationPage=signal(0);
  readonly reservationTotal=signal(0);readonly obligationTotal=signal(0);
  readonly loading=signal(false);readonly error=signal('');
  readonly quantity=(value:string|undefined)=>decimal(value,' ед.');
  readonly date=(value:string|undefined)=>dateTime(value,this.context.account()?.timezone??'UTC');
  readonly proof=(value:boolean|undefined)=>value===undefined?'Неизвестно':value?'Да':'Нет';
  private readonly labels:Readonly<Record<string,string>>={HELD:'Удержано',CONVERTED:'Перешло в обязательство',RELEASED:'Освобождено',EXPECTED:'Ожидается',CONFIRMED:'Подтверждено',DISPUTED:'Спорное',SETTLED:'Завершено',CANCELLED:'Отменено'};
  status(value:string):string{return this.labels[value]??value;}
  constructor(){effect(onCleanup=>{this.context.identity();this.context.permissions();this.offerId();this.epoch++;this.reservations.set([]);this.obligations.set([]);this.reservationPage.set(0);this.obligationPage.set(0);this.reservationTotal.set(0);this.obligationTotal.set(0);if(!this.context.account()||!this.context.allows('catalog.read'))return;const stop=this.realtime.subscribe(['stock-pools','commands','temporary-runs'],async()=>{await this.load();return !this.error();},false,{owner:this.reads,key:'commitments'});onCleanup(stop);});inject(DestroyRef).onDestroy(()=>this.epoch++);}
  load():Promise<void>{this.epoch++;return this.reads.run('commitments',()=>this.loadCurrent(),()=>!this.error());}
  private async loadCurrent():Promise<void>{const epoch=++this.epoch;this.loading.set(true);this.error.set('');try{const [holds,obligations]=await Promise.all([this.api.listReservations({query:{offerId:this.offerId(),page:this.reservationPage(),size:50}}),this.api.listObligations({query:{offerId:this.offerId(),page:this.obligationPage(),size:50}})]);if(epoch!==this.epoch)return;this.reservations.set(holds.items);this.reservationTotal.set(holds.total);this.obligations.set(obligations.items);this.obligationTotal.set(obligations.total);}catch(error:unknown){if(epoch===this.epoch)this.error.set(errorMessage(error));}finally{if(epoch===this.epoch)this.loading.set(false);}}
}
