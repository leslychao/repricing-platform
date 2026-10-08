import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, input, output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { CdkDrag, CdkDragDrop, CdkDragHandle, CdkDropList } from '@angular/cdk/drag-drop';
import { GridColumn, GridRow, ViewState } from '../core/models';
import { IconComponent } from './icon.component';

@Component({
  selector: 'app-grid',
  imports: [FormsModule, CdkDropList, CdkDrag, CdkDragHandle, IconComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="grid-toolbar">
      <label class="search"><app-icon name="search" /><input type="search" aria-label="Поиск" placeholder="Поиск по названию или артикулу" [ngModel]="view().query.search" (ngModelChange)="searchChanged($event)"></label>
      <button class="btn" (click)="filters.emit()"><app-icon name="filter" />Фильтры</button>
      <span class="spacer"></span>
      @if(exportAllowed()){<button class="btn quiet" (click)="exportRequested.emit()" title="Экспорт разрешённой выборки"><app-icon name="download" /><span>Экспорт</span></button>}
      @if (customized()) { <button class="btn quiet" (click)="resetRequested.emit()"><app-icon name="refresh" />Сбросить вид</button> }
      <details class="columns-menu"><summary class="btn"><app-icon name="columns" />Столбцы</summary><div class="columns-panel">
        @for (column of columns(); track column.id) {
          <label><input type="checkbox" [checked]="!hidden(column)" (change)="toggleColumn(column.id)">{{ column.label }}</label>
        }
        <button class="btn quiet" (click)="fullResetRequested.emit()">Полный сброс представления</button>
      </div></details>
    </div>
    @if (selected().length) { <div class="selection-bar"><strong>Выбрано: {{ selected().length }}</strong><button class="link" (click)="selectionChanged.emit([])">Снять выделение</button><span class="spacer"></span><ng-content select="[selectionActions]" /></div> }
    <div class="table-card">
      <div class="table-scroll" tabindex="0" [attr.aria-label]="title()">
        <table class="data-table" [style.min-width.px]="tableWidth()">
          <caption class="sr-only">{{ title() }}</caption>
          <colgroup>@if (selectable()) { <col style="width:42px"> }@for (column of visibleColumns(); track column.id) { <col [style.width.px]="columnWidth(column)"> }<col style="width:48px"></colgroup>
          <thead>
            @if (groups().length) { <tr class="column-groups">@if (selectable()) { <th></th> }@for (group of groups(); track $index) { <th [attr.colspan]="group.count" scope="colgroup">{{ group.name }}</th> }<th></th></tr> }
            <tr cdkDropList cdkDropListOrientation="horizontal" (cdkDropListDropped)="drop($event)">
              @if (selectable()) { <th><input type="checkbox" aria-label="Выбрать строки страницы" [checked]="allSelected()" (change)="selectPage()"></th> }
              @for (column of visibleColumns(); track column.id) {
                <th cdkDrag [cdkDragData]="column.id" scope="col" [attr.aria-sort]="sortDirection(column.id)">
                  <div class="column-heading"><button cdkDragHandle class="grip icon-button" [attr.aria-label]="'Переместить столбец ' + column.label" (keydown.arrowleft)="move(column.id, -1)" (keydown.arrowright)="move(column.id, 1)"><app-icon name="grip" /></button><button class="sort-button" (click)="sort(column.id)">{{ column.label }} @if (view().query.sort.startsWith(column.id + ',')) { <span>{{ view().query.sort.endsWith('asc') ? '↑' : '↓' }}</span> }</button></div>
                  <button class="column-resize" role="separator" aria-orientation="vertical" [attr.aria-label]="'Изменить ширину ' + column.label" [attr.aria-valuenow]="columnWidth(column)" aria-valuemin="80" aria-valuemax="800" (pointerdown)="startResize($event,column)" (keydown.arrowleft)="resize(column, columnWidth(column)-10)" (keydown.arrowright)="resize(column, columnWidth(column)+10)"></button>
                </th>
              }<th><span class="sr-only">Действия</span></th>
            </tr>
          </thead>
          <tbody>
            @for (row of rows(); track row.id) {
              <tr>@if (selectable()) { <td><input type="checkbox" [attr.aria-label]="'Выбрать ' + row.id" [checked]="selected().includes(row.id)" (change)="select(row.id)"></td> }
                @for (column of visibleColumns(); track column.id) {
                  <td>@if (row.cells[column.id]; as cell) {
                    @if (cell.kind === 'product') { <button class="product-cell" (click)="openRow.emit(row.id)">@if (cell.imageUrl) { <img [src]="cell.imageUrl" alt="" loading="lazy"> } @else { <span class="product-symbol"><app-icon name="box" /></span> }<span><strong>{{ cell.text }}</strong><small>{{ cell.detail }}</small></span></button> }
                    @else if(cell.action){<button class="link" [attr.aria-label]="column.label+': '+cell.text" [title]="column.label" (click)="cellAction.emit({rowId:row.id,columnId:column.id})">{{cell.text}}</button>}
                    @else { <div class="cell-content" [title]="cell.text"><span [class]="cell.kind === 'status' ? 'badge ' + (cell.tone ?? '') : cell.kind === 'money' ? 'money' : ''">{{ cell.text }}</span>@if (cell.detail) { <small>{{ cell.detail }}</small> }</div> }
                  } @else { <span class="muted">—</span> }</td>
                }<td><button class="icon-button" [attr.aria-label]="'Открыть запись ' + row.id" (click)="openRow.emit(row.id)" title="Подробности"><app-icon name="chevron" /></button></td>
              </tr>
            } @empty { <tr><td [attr.colspan]="visibleColumns().length+2"><div class="empty"><app-icon name="search" /><h2>{{ loading() ? 'Загружаем данные…' : error() ? 'Данные недоступны' : 'Записей пока нет' }}</h2><p>{{ loading() ? 'Проверяем актуальный контекст и сохранённый вид.' : error() ? 'Исправьте указанную ошибку и повторите загрузку.' : 'Измените условия поиска или добавьте данные.' }}</p></div></td></tr> }
          </tbody>
        </table>
      </div>
      <div class="table-footer"><span>{{ total() ? view().query.page*view().query.size+1 : 0 }}–{{ end() }} из {{ total() }}</span><label>На странице <select [ngModel]="view().query.size" (ngModelChange)="pageSize($event)">@for (size of [25,50,100,200]; track size) { <option [ngValue]="size">{{size}}</option> }</select></label><div class="pager"><button class="icon-button" [disabled]="view().query.page===0 || loading()" (click)="page(-1)" aria-label="Предыдущая страница">‹</button><span>{{ view().query.page+1 }}</span><button class="icon-button" [disabled]="end()>=total() || loading()" (click)="page(1)" aria-label="Следующая страница">›</button></div></div>
    </div>
  `,
})
export class GridComponent {
  readonly title = input('Данные');
  readonly columns = input.required<GridColumn[]>();
  readonly rows = input.required<GridRow[]>();
  readonly view = input.required<ViewState>();
  readonly total = input(0);
  readonly loading = input(false);
  readonly error = input('');
  readonly selected = input<string[]>([]);
  readonly selectable = input(false);
  readonly exportAllowed = input(false);
  readonly viewChanged = output<ViewState>();
  readonly selectionChanged = output<string[]>();
  readonly openRow = output<string>();
  readonly cellAction = output<{rowId:string;columnId:string}>();
  readonly filters = output<void>();
  readonly exportRequested = output<void>();
  readonly resetRequested = output<void>();
  readonly fullResetRequested = output<void>();
  private resizeAbort?: AbortController;
  readonly visibleColumns = computed(() => {
    const order = this.view().columns.order;
    return this.columns().filter(column => !this.hidden(column)).sort((a,b) => {
      const ai = order.indexOf(a.id), bi = order.indexOf(b.id);
      return (ai < 0 ? this.columns().indexOf(a) : ai) - (bi < 0 ? this.columns().indexOf(b) : bi);
    });
  });
  readonly groups = computed(() => {
    if (!this.visibleColumns().some(column => column.group)) return [];
    const result: { name: string; count: number }[] = [];
    for (const column of this.visibleColumns()) {
      const last = result.at(-1), name = column.group ?? '';
      if (last?.name === name) last.count++; else result.push({ name, count: 1 });
    }
    return result;
  });
  readonly tableWidth = computed(() => this.visibleColumns().reduce((sum,column) => sum+this.columnWidth(column),this.selectable()?90:48));
  readonly end = computed(() => Math.min(this.total(),(this.view().query.page+1)*this.view().query.size));
  readonly allSelected = computed(() => this.rows().length>0 && this.rows().every(row=>this.selected().includes(row.id)));
  readonly customized = computed(() => this.view().columns.order.length>0 || Object.keys(this.view().columns.widths).length>0 || this.view().columns.hidden.length>0 || this.view().query.sort!=='id,asc');
  constructor() { inject(DestroyRef).onDestroy(() => this.resizeAbort?.abort()); }
  hidden(column: GridColumn): boolean { return this.view().columns.order.length ? this.view().columns.hidden.includes(column.id) : (column.hidden ?? false); }
  columnWidth(column: GridColumn): number { return this.view().columns.widths[column.id] ?? column.width ?? 160; }
  private update(change: (state: ViewState) => void): void { const state=structuredClone(this.view());change(state);this.viewChanged.emit(state); }
  searchChanged(search: string): void { this.update(state=>{state.query.search=search;state.query.page=0;}); }
  sort(id: string): void { this.update(state=>{state.query.sort=`${id},${state.query.sort===id+',asc'?'desc':'asc'}`;state.query.page=0;}); }
  sortDirection(id: string): string { return this.view().query.sort===id+',asc'?'ascending':this.view().query.sort===id+',desc'?'descending':'none'; }
  page(delta: number): void { this.update(state=>{state.query.page+=delta;}); }
  pageSize(size: number): void { this.update(state=>{state.query.size=size;state.query.page=0;}); }
  select(id: string): void { this.selectionChanged.emit(this.selected().includes(id)?this.selected().filter(value=>value!==id):[...this.selected(),id]); }
  selectPage(): void { this.selectionChanged.emit(this.allSelected()?[]:this.rows().map(row=>row.id)); }
  toggleColumn(id: string): void { this.update(state=>{if(!state.columns.order.length){state.columns.order=this.columns().map(c=>c.id);state.columns.hidden=this.columns().filter(c=>c.hidden).map(c=>c.id);}state.columns.hidden=state.columns.hidden.includes(id)?state.columns.hidden.filter(value=>value!==id):[...state.columns.hidden,id];}); }
  drop(event: CdkDragDrop<unknown>): void { const visible=this.visibleColumns();const source=visible[event.previousIndex],target=visible[event.currentIndex];if(source&&target)this.move(source.id,event.currentIndex-event.previousIndex); }
  move(id: string,delta: number): void {
    const visible=this.visibleColumns();const source=visible.findIndex(column=>column.id===id);
    const target=visible[Math.max(0,Math.min(visible.length-1,source+delta))];
    if(source<0||!target||target.id===id)return;
    this.update(state=>{
      const order=state.columns.order.length?state.columns.order:this.columns().map(column=>column.id);
      if(!state.columns.order.length)state.columns.hidden=this.columns().filter(column=>column.hidden).map(column=>column.id);
      order.splice(order.indexOf(id),1);
      const position=order.indexOf(target.id)+(delta>0?1:0);order.splice(position,0,id);
      state.columns.order=order;
    });
  }
  resize(column: GridColumn,width: number): void { this.update(state=>{state.columns.widths[column.id]=Math.max(80,Math.min(800,Math.round(width)));}); }
  startResize(event: PointerEvent,column: GridColumn): void {
    event.preventDefault();event.stopPropagation();this.resizeAbort?.abort();
    const controller=new AbortController();this.resizeAbort=controller;
    const start=event.clientX,width=this.columnWidth(column);let latest=width;
    document.addEventListener('pointermove',e=>{latest=width+e.clientX-start;},{signal:controller.signal});
    document.addEventListener('pointerup',()=>{controller.abort();this.resize(column,latest);},{once:true,signal:controller.signal});
  }
}
