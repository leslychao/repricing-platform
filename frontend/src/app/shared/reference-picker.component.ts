import { Component, DestroyRef, computed, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialog, MatDialogRef } from '@angular/material/dialog';
import { firstValueFrom } from 'rxjs';
import { ApiService, errorMessage } from '../core/api.service';
import { ContextService } from '../core/context.service';
import { RealtimeService } from '../core/realtime.service';
import { ViewRefresh } from '../core/view-refresh';
import { IconComponent } from './icon.component';

type ReferenceKind = 'offers' | 'categories' | 'promotions' | 'competitor-sources' | 'warehouses' | 'members';
interface ReferenceOption { id: string; name: string; detail: string; unavailable: boolean; }
interface PickerData {
  kind: ReferenceKind; title: string; selected: string[]; maximum: number;
  permitted: readonly string[] | null; labels: ReadonlyMap<string, string>;
}
interface PickerResult { ids: string[]; labels: Map<string, string>; }

@Component({
  selector: 'app-reference-picker', imports: [IconComponent],
  template: `
    <div class="field"><span>{{label()}}</span>
      <button type="button" class="btn" [disabled]="disabled()||!context.allows(permission())" (click)="choose()">
        <app-icon name="search" />{{selected().length ? 'Изменить выбор · '+selected().length : 'Выбрать'}}
      </button>
      @if(!context.allows(permission())){<small>Нет доступа к справочнику кабинета.</small>}
      @for(id of selected();track id){<span class="option-line"><span>{{displayName(id)}}</span>
        @if(!disabled()){<button type="button" class="icon-button" [attr.aria-label]="'Убрать '+(displayName(id))" title="Убрать из выбора" (click)="remove(id)"><app-icon name="close" /></button>}
      </span>}
    </div>`,
})
export class ReferencePickerComponent {
  readonly kind = input.required<ReferenceKind>();
  readonly label = input.required<string>();
  readonly selectionLabel = input<string|undefined>();
  readonly value = input('');
  readonly maximum = input(1);
  readonly disabled = input(false);
  readonly permitted = input<readonly string[] | null>(null);
  readonly valueChange = output<string>();
  readonly selected = computed(() => this.value().split(',').map(id => id.trim()).filter(Boolean));
  readonly labels = signal<ReadonlyMap<string, string>>(new Map());
  readonly context = inject(ContextService);
  readonly permission = computed(() => this.kind()==='members'?'membership.read':'catalog.read');
  private readonly dialogs = inject(MatDialog);
  private readonly destroyRef = inject(DestroyRef);

  async choose(): Promise<void> {
    const dialog = this.dialogs.open<ReferenceChoiceDialogComponent, PickerData, PickerResult>(ReferenceChoiceDialogComponent, {
      width: '700px', data: { kind: this.kind(), title: this.label(), selected: this.selected(),
        maximum: this.maximum(), permitted: this.permitted(), labels: this.labels() },
    });
    const result = await firstValueFrom(dialog.afterClosed());
    if (!result || this.destroyRef.destroyed) return;
    this.labels.set(result.labels);
    this.valueChange.emit(result.ids.join(','));
  }

  displayName(id:string):string { return this.labels().get(id)||(this.selected().length===1?this.selectionLabel():'')||id; }
  remove(id: string): void { this.valueChange.emit(this.selected().filter(value => value !== id).join(',')); }
}

@Component({
  selector: 'app-reference-choice-dialog', imports: [FormsModule, IconComponent],
  template: `
    <header class="dialog-head"><div><h2>{{data.title}}</h2><p>Подтверждённые данные текущего кабинета</p></div><button class="icon-button" aria-label="Закрыть выбор" (click)="dialog.close()"><app-icon name="close" /></button></header>
    <div class="dialog-body">
      <form class="section-bar" (ngSubmit)="searchChanged()"><label class="field">Поиск<input name="search" [(ngModel)]="search" maxlength="200" autofocus></label><button class="btn" type="submit">Найти</button></form>
      @if(error()){<div class="notice error" role="alert">{{error()}}<button class="btn" (click)="load()">Повторить</button></div>}
      @if(loading()){<p role="status">Загружаем справочник…</p>}
      @for(option of options();track option.id){<label class="assignment-row"><input [type]="data.maximum===1?'radio':'checkbox'" name="reference" [checked]="selected().has(option.id)" [disabled]="option.unavailable||(!selected().has(option.id)&&selected().size>=data.maximum&&data.maximum>1)" (change)="toggle(option)"><span><strong>{{option.name}}</strong><small>{{option.detail}}</small></span></label>}
      @if(!loading()&&!error()&&!options().length){<p class="notice">Подходящих записей нет. Проверьте фильтр и состояние синхронизации источника.</p>}
      <div class="pagination"><button class="btn" [disabled]="page()===0||loading()" (click)="next(-1)">Назад</button><span>Страница {{page()+1}} · всего {{total()}}</span><button class="btn" [disabled]="(page()+1)*50>=total()||loading()" (click)="next(1)">Далее</button></div>
    </div>
    <footer class="dialog-footer"><span>Выбрано {{selected().size}} из {{data.maximum}}</span><button class="btn" (click)="dialog.close()">Отмена</button><button class="btn primary" (click)="confirm()">Выбрать</button></footer>`,
})
export class ReferenceChoiceDialogComponent {
  readonly data = inject<PickerData>(MAT_DIALOG_DATA);
  readonly dialog = inject(MatDialogRef<ReferenceChoiceDialogComponent, PickerResult>);
  private readonly api = inject(ApiService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly reads = new ViewRefresh(this.destroyRef);
  readonly selected = signal(new Set(this.data.selected));
  private readonly labels = new Map(this.data.labels);
  readonly options = signal<ReferenceOption[]>([]);
  readonly page = signal(0);
  readonly total = signal(0);
  readonly loading = signal(false);
  readonly error = signal('');
  search = '';
  private epoch = 0;

  constructor() {
    const resource = this.data.kind === 'members' ? 'memberships' : this.data.kind === 'offers' ? 'offers' : this.data.kind === 'competitor-sources' ? 'competitor-observations' : 'sources';
    const stop = inject(RealtimeService).subscribe([resource], async () => { await this.load(); return !this.error(); }, this.data.kind==='members', { owner: this.reads, key: 'load' });
    this.destroyRef.onDestroy(() => { this.epoch++; stop(); });
  }

  searchChanged(): void { this.page.set(0); void this.load(); }
  next(delta: number): void { this.page.update(page => page + delta); void this.load(); }
  load(): Promise<void> { this.epoch++; return this.reads.run('load', () => this.loadCurrent(), () => !this.error()); }
  private async loadCurrent(): Promise<void> {
    const epoch = ++this.epoch;
    this.loading.set(true); this.error.set('');
    try {
      const query = { page: this.page(), size: 50, search: this.search.trim() };
      let options: ReferenceOption[]; let total: number;
      switch (this.data.kind) {
        case 'members': { const result = await this.api.listMemberships({query:{...query,status:'ACTIVE'}}); total=result.total; options=result.items.map(item=>({id:item.userId,name:item.displayName,detail:item.email,unavailable:!item.active})); break; }
        case 'offers': { const result = await this.api.listOffers({query}); total = result.total; options = result.items.map(item => ({id:item.id,name:item.name,detail:item.sku,unavailable:false})); break; }
        case 'categories': { const result = await this.api.listCategories({query}); total = result.total; options = result.items.map(item => ({id:item.id,name:item.name,detail: `${item.externalId} · ${item.kind==='TYPE'?'Тип товара':'Категория'}${Date.parse(item.validUntil)<=Date.now()?' · требуется обновление':''}`,unavailable:Date.parse(item.validUntil)<=Date.now()})); break; }
        case 'promotions': { const result = await this.api.listPromotions({query}); total = result.total; options = result.items.map(item => ({id:item.id,name:item.name,detail:`${item.type} · ${item.startsAt.slice(0,10)} — ${item.endsAt.slice(0,10)}`,unavailable: this.data.permitted!==null&&!this.data.permitted.includes(item.id)})); break; }
        case 'competitor-sources': { const result = await this.api.listCompetitorSources({query}); total = result.total; options = result.items.map(item => ({id:item.sourceId,name:item.sellerName,detail:item.sourceId,unavailable:false})); break; }
        case 'warehouses': { const result = await this.api.listWarehouses({query}); total = result.total; options = result.items.map(item => ({id:item.id,name:item.name,detail: `${item.externalId} · ${item.models.join(', ')}`,unavailable:false})); break; }
      }
      if (epoch!==this.epoch||this.destroyRef.destroyed) return;
      for (const item of options) this.labels.set(item.id,item.name);
      this.options.set(options); this.total.set(total);
    } catch(error: unknown) { if(epoch===this.epoch&&!this.destroyRef.destroyed)this.error.set(errorMessage(error)); }
    finally { if(epoch===this.epoch&&!this.destroyRef.destroyed)this.loading.set(false); }
  }
  toggle(option: ReferenceOption): void {
    const selected = this.data.maximum===1 ? new Set<string>() : new Set(this.selected());
    if(selected.has(option.id))selected.delete(option.id);else if(selected.size<this.data.maximum)selected.add(option.id);
    this.labels.set(option.id,option.name);this.selected.set(selected);
  }
  confirm(): void { this.dialog.close({ids:[...this.selected()],labels:this.labels}); }
}
