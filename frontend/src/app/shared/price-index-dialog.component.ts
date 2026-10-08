import { ViewRefresh } from '../core/view-refresh';
import { Component, DestroyRef, inject, signal } from '@angular/core';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { ApiService, dateTime, decimal, errorMessage } from '../core/api.service';
import { ContextService } from '../core/context.service';
import { Offer, SupplierPriceIndices } from '../core/models';
import { RealtimeService } from '../core/realtime.service';
import { IconComponent } from './icon.component';

@Component({selector:'app-price-index-dialog',imports:[IconComponent],template:`
  <header class="dialog-head"><div><h2>Индекс цены</h2><p>{{offer().name}}</p></div><button class="icon-button" aria-label="Закрыть" (click)="dialog.close()"><app-icon name="close" /></button></header>
  <div class="dialog-body">
    @if(error()){<div class="notice error" role="alert">{{error()}}<button class="btn" (click)="load()">Повторить</button></div>}
    <h3>Сравнение с конкурентным ориентиром</h3>
    @if(offer().priceComparison;as comparison){
      <div class="data-pair"><span>Наблюдаемый платёж покупателя</span><strong>{{money(comparison.buyerPrice)}}</strong></div>
      <div class="data-pair"><span>Сопоставимый ориентир</span><strong>{{money(comparison.referencePrice)}}</strong></div>
      <div class="data-pair"><span>Соотношение</span><strong>{{money(offer().priceIndex,'')}}</strong></div>
      <div class="data-pair"><span>Условия наблюдения</span><strong>{{comparison.segment}}</strong></div>
      <div class="data-pair"><span>Размещение</span><strong>{{comparison.placementId}}</strong></div>
      <div class="data-pair"><span>Рассчитано / действует до</span><strong>{{date(comparison.calculatedAt)}} / {{date(comparison.validUntil)}}</strong></div>
      <details><summary>Наблюдения источников · {{comparison.observations.length}}</summary>@for(id of comparison.observations;track id){<p class="inline-note">{{id}}</p>}</details>
    }@else{<p class="inline-note">{{loading()?'Проверяем актуальность…':'Нет актуального сохранённого сравнения для одного подтверждённого размещения и сопоставимого сегмента. Выполните предварительный расчёт с конкурентным ориентиром.'}}</p>}
    <p class="inline-note">1 — цена равна ориентиру; меньше 1 — ниже. Это собственное сравнение, без смещения целевой цены политики.</p>
    <h3>Индексы площадки</h3>
    @if(supplier();as indices){
      @if(indices.source){<p class="inline-note">Источник: Ozon · product/info/prices. Значения площадки имеют отдельный смысл и не заменяют собственное сравнение.</p>
        <div class="data-pair"><span>Индекс на Ozon</span><strong>{{money(indices.ozon.value,'')}}</strong></div>
        <div class="data-pair"><span>Минимальная цена на Ozon</span><strong>{{money(indices.ozon.minimumPrice,' '+(indices.ozon.currency??''))}}</strong></div>
        <div class="data-pair"><span>Индекс внешних площадок</span><strong>{{money(indices.external.value,'')}}</strong></div>
        <div class="data-pair"><span>Минимальная внешняя цена</span><strong>{{money(indices.external.minimumPrice,' '+(indices.external.currency??''))}}</strong></div>
        <div class="data-pair"><span>Категория Ozon</span><strong>{{indices.color || 'Нет данных'}}</strong></div>
      }@else{<p class="inline-note">Источник официального индекса не предоставлен площадкой.</p>}
    }@else if(loading()){<p class="inline-note">Загружаем данные площадки…</p>}
  </div><footer class="dialog-footer"><button class="btn" (click)="dialog.close()">Закрыть</button></footer>
`})
export class PriceIndexDialogComponent {
  private readonly reads = new ViewRefresh(inject(DestroyRef));

  readonly data=inject<Offer>(MAT_DIALOG_DATA);
  readonly offer=signal(this.data);
  readonly dialog=inject(MatDialogRef<PriceIndexDialogComponent>);
  readonly context=inject(ContextService);
  private readonly api=inject(ApiService);
  readonly supplier=signal<SupplierPriceIndices|null>(null);
  readonly error=signal('');readonly loading=signal(true);
  readonly money=decimal;
  readonly date=(value:string)=>dateTime(value,this.context.account()?.timezone??'UTC');
  private epoch=0;
  constructor(){const stop=inject(RealtimeService).subscribe(['offers','competitor-observations','decisions'],async()=>{await this.load();return !this.error();},false,{owner:this.reads,key:'load'});inject(DestroyRef).onDestroy(()=>{this.epoch++;stop();});}
  load():Promise<void>{this.epoch++;return this.reads.run('load',()=>this.loadCurrent(),()=>!this.error());}
  private async loadCurrent():Promise<void>{const epoch=++this.epoch;this.loading.set(true);this.error.set('');try{const [offer,supplier]=await Promise.all([this.api.getOffer({path:{id:this.data.id}}),this.api.getSupplierPriceIndices({path:{id:this.data.id}})]);if(epoch===this.epoch){this.offer.set(offer);this.supplier.set(supplier);}}catch(error:unknown){if(epoch===this.epoch)this.error.set(errorMessage(error));}finally{if(epoch===this.epoch)this.loading.set(false);}}
}
