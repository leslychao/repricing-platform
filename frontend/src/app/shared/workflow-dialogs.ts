import { ReferencePickerComponent } from './reference-picker.component';
import { ViewRefresh } from '../core/view-refresh';
import { AssignmentBatchDialogComponent } from './assignment-batch-dialog.component';
import { RequestScope } from '../api/angular-client.gen';
import { RiskPermissionComponent } from './risk-permission.component';
import { RealtimeService } from '../core/realtime.service';
import { Component, DestroyRef, inject, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialog, MatDialogRef } from '@angular/material/dialog';
import { firstValueFrom } from 'rxjs';
import { ApiService, accountInstant, dateTime, errorMessage, operationMessage, mutation, stateLabel } from '../core/api.service';
import { ContextService } from '../core/context.service';
import { AccountAccess, Assignment, BackgroundOperation, Invitation, ImportDetail, Membership, Policy, Selection, TemporaryRun, TemporaryRunCompletionReview, TemporaryRunReviewRequest, TemporaryPermissionState, ViewState } from '../core/models';
import { IconComponent } from './icon.component';
import { GuardedFormDialog } from './unsaved-changes';
import { ConfirmDialogComponent } from './dialogs';

@Component({selector:'app-export-dialog',imports:[FormsModule,IconComponent],template:`
  <header class="dialog-head"><div><h2>{{data.monthly?'Месячный отчёт':'Экспорт таблицы'}}</h2><p>{{data.monthly?'Период: '+data.query.filters['month']:'Файл содержит только доступные вам поля.'}}</p></div><button class="icon-button" aria-label="Закрыть" (click)="dialog.close()"><app-icon name="close" /></button></header>
  <form (ngSubmit)="submit()"><div class="dialog-body">
    @if(error()){<div class="notice error" role="alert">{{error()}}</div>}
    @if(selection();as snapshot){<div class="notice"><app-icon name="check" /><span>Зафиксировано {{snapshot.total}} строк. Изменения таблицы после этого момента не изменят файл.</span></div>}
    @else if(data.monthly){<label class="field">Вид отчёта<select name="reportKind" [(ngModel)]="reportKind" [disabled]="saving()"><option value="MONTHLY_PNL">Месячный финансовый результат · P&amp;L</option><option value="MONTHLY_UNIT_ECONOMICS">Фактическая экономика товаров</option></select></label><p class="inline-note">Фиксируется весь месяц кабинета. Подтверждённые и предварительные суммы, нераспределённые расходы и полнота источников сохраняются в файле. Выплаты не подменяют начисления.</p>}
    @else{<label class="field">Состав выборки<select name="scope" [(ngModel)]="scope" [disabled]="saving()"><option value="all">Все строки по текущим фильтрам · {{data.total}}</option>@if(data.ids.length){<option value="selected">Выбранные строки · {{data.ids.length}}</option>}</select></label>}
    <label class="field">Формат<select name="format" [(ngModel)]="format" [disabled]="saving()"><option value="CSV">CSV</option><option value="XLSX">Excel · XLSX</option></select></label>
    <p class="inline-note">Файл готовится в фоне. Его состояние и срок доступности отображаются в истории экспорта.</p>
  </div><footer class="dialog-footer"><button class="btn" type="button" (click)="dialog.close()">Отмена</button><button class="btn primary" type="submit" [disabled]="saving()">{{selection()?'Подготовить файл':'Зафиксировать выборку'}}</button></footer></form>
`})
export class ExportDialogComponent {
  readonly data=inject<{resource:string;query:ViewState['query'];ids:string[];total?:number;requestScope:RequestScope;monthly?:boolean}>(MAT_DIALOG_DATA);
  readonly dialog=inject(MatDialogRef<ExportDialogComponent>);private readonly api=inject(ApiService);
  readonly error=signal('');readonly saving=signal(false);readonly selection=signal<Selection|null>(null);
  scope:'all'|'selected'=this.data.ids.length?'selected':'all';format:'CSV'|'XLSX'='CSV';
  reportKind:'EXPORT'|'MONTHLY_PNL'|'MONTHLY_UNIT_ECONOMICS'=this.data.monthly?'MONTHLY_PNL':'EXPORT';
  async submit():Promise<void>{
    if(this.saving())return;this.saving.set(true);this.error.set('');
    try{const snapshot=this.selection();if(!snapshot){const resource=this.reportKind==='MONTHLY_PNL'?'monthly-pnl':this.reportKind==='MONTHLY_UNIT_ECONOMICS'?'monthly-unit-economics':this.data.resource;this.selection.set(await this.api.createSelection({body:{...mutation(),resource,query:this.data.query,ids:this.scope==='selected'?this.data.ids:undefined}},this.data.requestScope));return;}
      await this.api.createReport({body:{...mutation(),selectionId:snapshot.id,format:this.format,kind:this.reportKind}},this.data.requestScope);this.dialog.close(true);
    }catch(error:unknown){this.error.set(errorMessage(error));}finally{this.saving.set(false);}
  }
}

@Component({selector:'app-access-dialog',imports:[FormsModule,IconComponent,ReferencePickerComponent],template:`
  <header class="dialog-head"><div><h2>{{data.account?'Доступ к кабинету':'Права участника'}}</h2><p>{{context.organization()?.name}} @if(data.account){· {{context.account()?.name}}}</p></div><button class="icon-button" aria-label="Закрыть" (click)="dialog.close()"><app-icon name="close" /></button></header><form (ngSubmit)="save()"><div class="dialog-body">@if(error()){<div class="notice error" role="alert">{{error()}}</div>}@if(data.member;as member){<div class="data-pair"><span>Участник</span><strong>{{member.displayName}} · {{member.email}}</strong></div>}@else{<app-reference-picker kind="members" label="Участник компании" [(value)]="userId" />}@if(data.account){<label class="field">Роль в кабинете<select name="role" [(ngModel)]="role"><option value="ADMIN">Администратор</option><option value="MANAGER">Менеджер</option><option value="OPERATOR">Оператор</option><option value="VIEWER">Наблюдатель</option></select></label>}<h3>Дополнительные права</h3>@for(permission of permissions;track permission.id){<label class="option-line"><input type="checkbox" [checked]="selected.includes(permission.id)" (change)="toggle(permission.id)">{{permission.label}}</label>}<p class="inline-note">Сервер проверит предел делегирования и права на финансовые поля. Роль кабинета не передаёт владение компанией.</p>@if(data.member&& !data.account&&context.allows('ownership.transfer')){<button type="button" class="btn danger" [disabled]="saving()" (click)="transfer()">Передать владение компанией</button>}</div><footer class="dialog-footer">@if(data.member?.active){<button type="button" class="btn danger" [disabled]="saving()" (click)="revoke()">{{data.account?'Отозвать доступ':'Отозвать членство'}}</button>}@else if(data.member&&!data.account){<button type="button" class="btn" [disabled]="saving()" (click)="reactivate()">Восстановить членство</button>}<span class="spacer"></span><button type="button" class="btn" (click)="dialog.close()">Отмена</button><button class="btn primary" type="submit" [disabled]="saving()">Сохранить</button></footer></form>
`})
export class AccessDialogComponent extends GuardedFormDialog {
  readonly data=inject<{account:boolean;member?:Membership|AccountAccess}>(MAT_DIALOG_DATA);readonly dialog=inject(MatDialogRef<AccessDialogComponent>);readonly context=inject(ContextService);private readonly api=inject(ApiService);private readonly dialogs=inject(MatDialog);readonly error=signal('');readonly saving=signal(false);userId=this.data.member?.userId??'';role=this.data.member?.role??'VIEWER';selected=[...(this.data.member?.permissions??[])];
  readonly permissions=this.data.account?[{id:'finance.read',label:'Читать финансовые данные'},{id:'finance.write',label:'Изменять финансовые данные'},{id:'resource.manage',label:'Управлять экономическими ограничениями'},{id:'connection.manage',label:'Управлять подключением'},{id:'file.write',label:'Импортировать данные'},{id:'export',label:'Экспортировать разрешённые данные'},{id:'raw.read',label:'Читать исходные файлы'},{id:'audit.read',label:'Читать аудит кабинета'}]:[{id:'organization.policy.read',label:'Читать политики компании'},{id:'organization.policy.manage',label:'Управлять политиками компании'}];
  private savedPermissions=JSON.stringify(this.selected);
  override hasUnsavedChanges():boolean{return super.hasUnsavedChanges()||JSON.stringify(this.selected)!==this.savedPermissions;}
  toggle(id:string):void{this.selected=this.selected.includes(id)?this.selected.filter(value=>value!==id):[...this.selected,id];}
  async save():Promise<void>{if(this.saving())return;this.saving.set(true);try{const options={path:{id:this.userId},body:{...mutation(this.data.member?.revision),role:this.data.account?this.role:'MEMBER',permissions:this.selected}};if(this.data.account)await this.api.updateAccountAccess({...options,body:{...options.body,expectedRevision:this.data.member?.revision}});else await this.api.updateMembership(options);this.dialog.close(true);}catch(error:unknown){this.error.set(errorMessage(error));}finally{this.saving.set(false);}}
  async revoke():Promise<void>{const member=this.data.member;if(!member||this.saving())return;const confirmed=await firstValueFrom(this.dialogs.open(ConfirmDialogComponent,{width:'520px',data:{title:this.data.account?'Отозвать доступ к кабинету?':'Отозвать членство?',message:this.data.account?'Доступ к этому кабинету будет прекращён. Членство в компании сохраняется.':'Доступ участника к компании и её кабинетам будет прекращён. Это действие записывается в аудит.',action:'Отозвать',danger:true}}).afterClosed());if(!confirmed)return;this.saving.set(true);try{if(this.data.account)await this.api.revokeAccountAccess({path:{id:member.userId},body:mutation(member.revision)});else await this.api.revokeMembership({path:{id:member.userId},body:mutation(member.revision)});this.dialog.close(true);}catch(error:unknown){this.error.set(errorMessage(error));}finally{this.saving.set(false);}}
  async reactivate():Promise<void>{const member=this.data.member;if(!member||this.saving())return;this.saving.set(true);try{await this.api.reactivateMembership({path:{id:member.userId},body:mutation(member.revision)});this.dialog.close(true);}catch(error:unknown){this.error.set(errorMessage(error));}finally{this.saving.set(false);}}
  async transfer():Promise<void>{const organization=this.context.organization();if(!organization||!this.data.member||this.saving())return;const confirmed=await firstValueFrom(this.dialogs.open(ConfirmDialogComponent,{width:'520px',data:{title:'Передать владение?',message:'Выбранный участник станет владельцем компании. Ваши полномочия владельца прекратятся.',action:'Передать владение',danger:true}}).afterClosed());if(!confirmed)return;this.saving.set(true);try{await this.api.transferOwner({path:{id:organization.id},body:{...mutation(organization.revision),memberId:this.data.member.userId}});this.dialog.close(true);}catch(error:unknown){this.error.set(errorMessage(error));}finally{this.saving.set(false);}}
}

@Component({selector:'app-bulk-assignment',imports:[FormsModule,IconComponent],template:`<header class="dialog-head"><div><h2>Назначить политику</h2><p>Выборка зафиксирована сервером: {{data.total}} товаров.</p></div><button class="icon-button" aria-label="Закрыть" (click)="dialog.close()"><app-icon name="close" /></button></header><form (ngSubmit)="save()"><div class="dialog-body">@if(error()){<div class="notice error" role="alert">{{error()}}</div>}<label class="field">Поиск политики<input name="policySearch" [(ngModel)]="search" maxlength="200"></label><button type="button" class="btn" [disabled]="loading()" (click)="findPolicies()">Найти</button><label class="field">Опубликованная политика<select name="policy" [(ngModel)]="policyId" required><option value="">Выберите политику</option>@for(policy of policies();track policy.id){<option [value]="policy.id">{{policy.name}} · версия {{policy.version}}</option>}</select></label><div class="pagination"><button class="btn" type="button" [disabled]="page()===0||loading()" (click)="changePage(-1)">Назад</button><span>Страница {{page()+1}} · всего {{total()}}</span><button class="btn" type="button" [disabled]="(page()+1)*50>=total()||loading()" (click)="changePage(1)">Далее</button></div><label class="field">Режим<select name="mode" [(ngModel)]="mode"><option value="PREVIEW">Расчёт без применения</option><option value="MANUAL">Ручное подтверждение</option></select></label><p class="inline-note">Назначение проверяет зафиксированный состав и редакции. AUTO выдаётся отдельным действием.</p></div><footer class="dialog-footer"><button class="btn" type="button" (click)="dialog.close()">Отмена</button><button class="btn primary" type="submit" [disabled]="saving()||!policyId">Назначить</button></footer></form>`})
export class BulkAssignmentDialogComponent extends GuardedFormDialog {
  readonly data=inject<{selectionId:string;total:number}>(MAT_DIALOG_DATA);readonly dialog=inject(MatDialogRef<BulkAssignmentDialogComponent>);private readonly dialogs=inject(MatDialog);private readonly api=inject(ApiService);readonly error=signal('');readonly saving=signal(false);readonly policies=signal<Policy[]>([]);policyId='';mode:'PREVIEW'|'MANUAL'='PREVIEW';
  readonly loading=signal(false);readonly page=signal(0);readonly total=signal(0);search='';private querySearch='';private loadEpoch=0;
  constructor(){super();void this.loadPolicies();inject(DestroyRef).onDestroy(()=>this.loadEpoch++);}
  findPolicies():void{this.querySearch=this.search.trim();this.page.set(0);this.policyId='';void this.loadPolicies();}
  changePage(delta:number):void{this.page.update(value=>Math.max(0,value+delta));this.policyId='';void this.loadPolicies();}
  private readonly reads=new ViewRefresh(inject(DestroyRef));
  private loadPolicies():Promise<void>{this.loadEpoch++;return this.reads.run('policies',()=>this.loadPoliciesCurrent(),()=>!this.error());}
  private async loadPoliciesCurrent():Promise<void>{const epoch=++this.loadEpoch;this.loading.set(true);try{const page=await this.api.listPolicies({query:{status:'ACTIVE',size:50,page:this.page(),search:this.querySearch,sort:'name',direction:'asc'}});if(epoch!==this.loadEpoch)return;this.policies.set(page.items);this.total.set(page.total);this.error.set('');}catch(error:unknown){if(epoch===this.loadEpoch)this.error.set(errorMessage(error));}finally{if(epoch===this.loadEpoch)this.loading.set(false);}}
  async save():Promise<void>{if(this.saving())return;this.saving.set(true);try{const result=await this.api.bulkAssign({body:{...mutation(),selectionId:this.data.selectionId,policyId:this.policyId,mode:this.mode}});this.dialog.close(true);this.dialogs.open(AssignmentBatchDialogComponent,{width:'820px',data:{id:result.operationId}});}catch(error:unknown){this.error.set(errorMessage(error));}finally{this.saving.set(false);}}
}

@Component({selector:'app-import-review',imports:[IconComponent],template:`<header class="dialog-head"><div><h2>Проверка импорта</h2><p>{{detail()?.name}}</p></div><button class="icon-button" aria-label="Закрыть" (click)="dialog.close()"><app-icon name="close" /></button></header><div class="dialog-body">@if(error()){<div class="notice error" role="alert">{{error()}}</div>}@if(detail();as item){<div class="data-pair"><span>Состояние</span><strong>{{label(item.status)}}</strong></div><div class="data-pair"><span>Редакция проверки</span><strong>{{item.validationEdition}}</strong></div>@if(item.reason){<div class="notice">{{item.reason}}</div>}@if(item.temporaryExpired){<div class="notice">Срок хранения временных строк истёк. История импорта сохранена; для новой проверки загрузите файл заново.</div>}<div class="metric-grid"><div class="metric"><span>Строк</span><strong>{{item.totalRows}}</strong></div><div class="metric"><span>Проверено</span><strong>{{item.validRows}}</strong></div><div class="metric"><span>Ошибок</span><strong>{{item.errorRows}}</strong></div></div>@for(row of item.rows;track row.row){<div class="data-pair"><span>Строка {{row.row}}</span><strong>{{row.message || label(row.status)}}</strong></div>}<div class="pagination"><button class="btn" [disabled]="page()===0||loading()" (click)="changePage(-1)">Назад</button><span>Страница {{page()+1}}</span><button class="btn" [disabled]="(page()+1)*50>=item.totalRows||loading()" (click)="changePage(1)">Далее</button></div><p class="inline-note">Показано до 50 строк проверки. Полная диагностика доступна в файле ошибок.</p>@if(!item.temporaryExpired){<a class="btn" [href]="errorsLink()">Скачать ошибки CSV</a>}}@else{<p role="status">Загружаем результат проверки…</p>}</div><footer class="dialog-footer"><button class="btn" (click)="dialog.close()">Закрыть</button>@if(detail()&&['DRAFT','VALIDATED','PREPARING'].includes(detail()?.status??'')&&!detail()?.temporaryExpired){<button class="btn danger" [disabled]="saving()" (click)="cancel()">Отменить импорт</button>}@if(!detail()?.temporaryExpired&&detail()?.status==='STALE'){<button class="btn primary" [disabled]="saving()" (click)="revalidate()">Повторно проверить актуальные данные</button>}@if(!detail()?.temporaryExpired&&detail()?.status==='VALIDATED'){<button class="btn primary" [disabled]="saving()" (click)="apply()">Применить проверенные строки</button>}</footer>`})
export class ImportReviewDialogComponent {
  private readonly reads = new ViewRefresh(inject(DestroyRef));

  readonly data=inject<{id:string}>(MAT_DIALOG_DATA);readonly dialog=inject(MatDialogRef<ImportReviewDialogComponent>);private readonly api=inject(ApiService);private readonly context=inject(ContextService);readonly error=signal('');readonly saving=signal(false);readonly detail=signal<ImportDetail|null>(null);readonly label=stateLabel;readonly page=signal(0);readonly loading=signal(false);
  private epoch=0;
  constructor(){const stop=inject(RealtimeService).subscribe(['imports'],async()=>{await this.load();return !this.error();},false,{owner:this.reads,key:'load'});inject(DestroyRef).onDestroy(()=>{this.epoch++;stop();});}
  private load():Promise<void>{this.epoch++;return this.reads.run('load',()=>this.loadCurrent(),()=>!this.error());}
  private async loadCurrent():Promise<void>{const epoch=++this.epoch;this.loading.set(true);try{const detail=await this.api.getImport({path:{id:this.data.id},query:{page:this.page(),size:50}});if(epoch===this.epoch){this.detail.set(detail);this.error.set('');}}catch(error:unknown){if(epoch===this.epoch)this.error.set(errorMessage(error));}finally{if(epoch===this.epoch)this.loading.set(false);}}
  changePage(delta:number):void{this.page.update(value=>value+delta);void this.load();}
  async revalidate():Promise<void>{const detail=this.detail();if(!detail||this.saving())return;this.saving.set(true);try{await this.api.revalidateImport({path:{id:detail.id},body:mutation(detail.revision)});await this.load();}catch(error:unknown){this.error.set(errorMessage(error));}finally{this.saving.set(false);}}
  async cancel():Promise<void>{const detail=this.detail();if(!detail||this.saving())return;this.saving.set(true);try{await this.api.cancelImport({path:{id:detail.id},body:{...mutation(detail.revision),previewRevision:detail.revision}});await this.load();}catch(error:unknown){this.error.set(errorMessage(error));}finally{this.saving.set(false);}}
  errorsLink():string{return `/api/v1/imports/${encodeURIComponent(this.data.id)}/errors?organizationId=${encodeURIComponent(this.context.organization()?.id??'')}&accountId=${encodeURIComponent(this.context.account()?.id??'')}`;}
  async apply():Promise<void>{const detail=this.detail();if(!detail||this.saving())return;this.saving.set(true);try{await this.api.applyImport({path:{id:detail.id},body:{...mutation(detail.revision),previewRevision:detail.revision}});this.dialog.close(true);}catch(error:unknown){this.error.set(errorMessage(error));}finally{this.saving.set(false);}}
}

@Component({selector:'app-temporary-run',imports:[FormsModule,IconComponent,RiskPermissionComponent,ReferencePickerComponent],template:`
  <header class="dialog-head"><div><h2>Временный сценарий</h2><p>Назначение {{data.assignment.targetName || data.assignment.targetId || 'кабинета'}}</p></div><button class="icon-button" aria-label="Закрыть" (click)="dialog.close()"><app-icon name="close" /></button></header>
  <form (ngSubmit)="save()"><div class="dialog-body">
    @if(error()){<div class="notice error" role="alert">{{error()}}</div>}
    @if(!operationId()&&!run()){
      <app-reference-picker kind="offers" label="Товары эпизода" [(value)]="offerIds" [maximum]="1000" [selectionLabel]="data.assignment.scope==='OFFER'?data.assignment.targetName:undefined" /><p class="inline-note">От 1 до 1000 товаров. Сервер фиксирует состав и проверяет действующее назначение каждого товара.</p>
      <div class="fields-2"><label class="field">Начало<input type="datetime-local" name="start" [(ngModel)]="startsAt" required></label><label class="field">Окончание<input type="datetime-local" name="end" [(ngModel)]="endsAt" required></label></div>
      <p class="inline-note">Время в поясе {{context.account()?.timezone}}. Пауза не продлевает эпизод.</p>
      <div class="fields-2"><label class="field">Предел принятого количества<input inputmode="decimal" name="quantity" [(ngModel)]="maximumAcceptedQuantity"><small>Количество единиц, принятых в заказы.</small></label><label class="field">Минимальный оставшийся остаток<input inputmode="decimal" name="stock" [(ngModel)]="minimumRemainingStock"></label></div>
      <p class="inline-note">Используется временное правило опубликованной политики. Создание эпизода не выдаёт экономическое согласие и не включает AUTO.</p>
    }@else if(run();as value){
      <div class="data-pair"><span>Состояние</span><strong>{{label(value.state)}}</strong></div><div class="data-pair"><span>Принято в заказы</span><strong>{{value.acceptedQuantity}}</strong></div><p class="inline-note">{{value.reason}}</p>
      <app-risk-permission [state]="permission()" />
      @if(permission()?.permission?.status==='ACTIVE'){<button class="btn danger" type="button" [disabled]="saving()" (click)="revokePermission()">Отозвать согласие и завершить эпизод</button>}
      @if((value.state==='READY'||value.state==='RUNNING')&&permission()?.permission?.status!=='ACTIVE'){
        <label class="field">Нижняя прибыль сохранённой покупки, ₽<input inputmode="decimal" name="floor" [(ngModel)]="keptProfitFloor" required></label><label class="field">Лимит потерь B, ₽<input inputmode="decimal" name="budget" [(ngModel)]="lossBudget"><small>Обязателен при отрицательном пороге прибыли.</small></label><p class="inline-note">Период, область и внешнюю границу возможных потерь сервер берёт из замороженного эпизода и подтверждённых условий площадки. Ручное количество не заменяет внешнюю гарантию.</p>
      }
    }@else{
      <div class="notice" role="status">Операция {{operationId()}}: {{label(operation()?.status)}}. {{operationMessage(operation()?.message)}}</div><p class="inline-note">Результат появится после сохранения сервером. Можно закрыть окно и посмотреть операцию в задачах.</p>
    }
    @if(completion();as review){
      <h3>Подтверждённая область завершения</h3>
      <div class="data-pair"><span>Первое разрешённое действие</span><strong>{{date(review.firstActiveAt)}}</strong></div>
      <div class="data-pair"><span>Завершение не раньше</span><strong>{{date(review.earliestCompletionAt)}}</strong></div>
      <p class="inline-note">После окончания периода сервер может ожидать следующего разрешённого окна. Завершение подтверждается по фактическому состоянию площадки.</p>
      @for(target of review.targets.slice(reviewPage()*50,(reviewPage()+1)*50);track target.targetId){
        <article class="panel panel-body"><div class="data-pair"><span>Товар / ценевая цель</span><strong>{{target.offerId}} / {{target.targetId}}</strong></div><div class="data-pair"><span>Возможные выходы из акций</span><strong>{{target.possibleExits.join(', ') || 'Не требуются'}}</strong></div><div class="data-pair"><span>Управляемые поля</span><strong>{{managedFields(target.managedFields)}}</strong></div></article>
      }
      @if(review.targets.length>50){<div class="pagination"><button type="button" class="btn" [disabled]="reviewPage()===0" (click)="reviewPage.update(previousReviewPage)">Назад</button><span>Страница {{reviewPage()+1}} · целей {{review.targets.length}}</span><button type="button" class="btn" [disabled]="(reviewPage()+1)*50>=review.targets.length" (click)="reviewPage.update(nextReviewPage)">Далее</button></div>}
      @if(!run()){<p class="inline-note">Нажимая «Подтвердить и создать», вы подтверждаете показанную область завершения. Изменение состава или условий потребует новой проверки.</p>}
    }
  </div><footer class="dialog-footer"><button class="btn" type="button" (click)="dialog.close()">Закрыть</button>@if((!operationId()&&!run())||((run()?.state==='READY'||run()?.state==='RUNNING')&&permission()?.permission?.status!=='ACTIVE')){<button class="btn primary" type="submit" [disabled]="saving()">{{run()?'Выдать экономическое согласие':reviewMatches()?'Подтвердить и создать':'Проверить завершение'}}</button>}</footer></form>
`})
export class TemporaryRunDialogComponent extends GuardedFormDialog {
  private readonly reads = new ViewRefresh(inject(DestroyRef));

  readonly data=inject<{assignment:Assignment;run?:TemporaryRun}>(MAT_DIALOG_DATA);
  readonly dialog=inject(MatDialogRef<TemporaryRunDialogComponent>);
  readonly context=inject(ContextService);private readonly api=inject(ApiService);
  readonly error=signal('');readonly saving=signal(false);readonly run=signal<TemporaryRun|null>(this.data.run??null);readonly permission=signal<TemporaryPermissionState|null>(null);private readonly dialogs=inject(MatDialog);
  readonly operationMessage=operationMessage;readonly operationId=signal<string|null>(null);readonly operation=signal<BackgroundOperation|null>(null);readonly label=stateLabel;
  offerIds=this.data.assignment.scope==='OFFER'?this.data.assignment.targetId??'':'';
  startsAt='';endsAt='';maximumAcceptedQuantity='';minimumRemainingStock='';keptProfitFloor='';lossBudget='';
  private epoch=0;
  readonly review=signal<TemporaryRunCompletionReview|null>(null);readonly reviewPage=signal(0);
  readonly previousReviewPage=(page:number)=>page-1;readonly nextReviewPage=(page:number)=>page+1;
  readonly date=(value:string)=>dateTime(value,this.context.account()?.timezone??'UTC');
  private reviewedInput?:TemporaryRunReviewRequest;private reviewedFingerprint='';
  private inputFingerprint():string{return JSON.stringify([this.offerIds,this.startsAt,this.endsAt,this.maximumAcceptedQuantity,this.minimumRemainingStock,this.context.account()?.timezone]);}
  reviewMatches():boolean{return !!this.review()&&!!this.reviewedInput&&this.reviewedFingerprint===this.inputFingerprint();}
  completion():TemporaryRunCompletionReview|null{return this.run()?.completionReview??(this.reviewMatches()?this.review():null);}
  managedFields(fields:readonly string[]):string{return fields.map(field=>({BASE_PRICE:'Базовая цена',PROMOTION_PRICE:'Цена в акции'}[field]??field)).join(', ')||'Нет изменяемых полей';}
  constructor(){super();const stop=inject(RealtimeService).subscribe(['temporary-runs','operations'],async()=>{await this.refresh();return !this.error();},false,{owner:this.reads,key:'refresh'});inject(DestroyRef).onDestroy(()=>{this.epoch++;stop();});}
  private refresh():Promise<void>{this.epoch++;return this.reads.run('refresh',()=>this.refreshCurrent(),()=>!this.error());}
  private async refreshCurrent():Promise<void>{
    const id=this.operationId(),run=this.run();if(!id&&!run)return;const epoch=++this.epoch;
    try{
      if(id){const operation=await this.api.getOperation({path:{id}});if(epoch!==this.epoch)return;this.operation.set(operation);if(operation.status!=='SUCCEEDED')return;}
      const runId=id??run?.id;if(!runId)return;
      const [result,permission]=await Promise.all([this.api.getTemporaryRun({path:{id:runId}}),this.api.getTemporaryPermission({path:{id:runId}})]);if(epoch===this.epoch){this.run.set(result);this.permission.set(permission);this.error.set('');}
    }catch(error:unknown){if(epoch===this.epoch)this.error.set(errorMessage(error));}
  }
  async revokePermission():Promise<void>{
    const run=this.run(),permission=this.permission()?.permission;if(!run||!permission||this.saving())return;
    const confirmed=await firstValueFrom(this.dialogs.open(ConfirmDialogComponent,{width:'520px',data:{title:'Отозвать экономическое согласие?',message:'Новые риски будут остановлены. Сервер безопасно завершит эпизод; ответственность по принятым заказам сохранится.',action:'Отозвать',danger:true}}).afterClosed());if(!confirmed)return;
    this.saving.set(true);try{this.run.set(await this.api.revokeTemporaryPermission({path:{id:run.id},body:{...mutation(run.revision),expectedPermissionRevision:permission.revision}}));await this.refresh();}catch(error:unknown){this.error.set(errorMessage(error));}finally{this.saving.set(false);}
  }

  async save():Promise<void>{
    if(this.saving())return;this.saving.set(true);this.error.set('');
    try{
      const run=this.run();
      if(!run){
        if(this.operationId())return;
        if(!this.reviewMatches()){
          const zone=this.context.account()?.timezone;if(!zone)throw new Error('Выберите кабинет с часовым поясом');
          const fingerprint=this.inputFingerprint(),epoch=this.epoch;
          const input:TemporaryRunReviewRequest={expectedRevision:this.data.assignment.revision,assignmentId:this.data.assignment.id,offerIds:this.offerIds.split(/[\s,;]+/).filter(Boolean),startsAt:accountInstant(this.startsAt,zone),endsAt:accountInstant(this.endsAt,zone),maximumAcceptedQuantity:this.maximumAcceptedQuantity||null,minimumRemainingStock:this.minimumRemainingStock||null};
          const review=await this.api.reviewTemporaryRun({body:input});
          if(epoch!==this.epoch||fingerprint!==this.inputFingerprint())return;
          this.reviewedInput=input;this.reviewedFingerprint=fingerprint;this.reviewPage.set(0);this.review.set(review);return;
        }
        const review=this.review(),input=this.reviewedInput;if(!review||!input)return;
        const operation=await this.api.createTemporaryRun({body:{...input,clientRequestId:mutation().clientRequestId,expectedCompletionReviewDigest:review.digest}});
        this.operationId.set(operation.operationId);this.form()?.form.markAsPristine();await this.refresh();
      }else{
        await this.api.permitTemporaryRun({path:{id:run.id},body:{...mutation(run.revision),keptProfitFloor:this.keptProfitFloor,lossBudget:this.lossBudget||null}});this.form()?.form.markAsPristine();await this.refresh();
      }
    }catch(error:unknown){
      if(error instanceof HttpErrorResponse&&error.status===409&&!this.run()){
        this.review.set(null);this.reviewedInput=undefined;this.reviewedFingerprint='';
      }
      this.error.set(errorMessage(error));
    }finally{this.saving.set(false);}
  }
}

@Component({selector:'app-invitation-dialog',imports:[IconComponent],template:`<header class="dialog-head"><div><h2>Приглашение</h2><p>{{invitation().email}}</p></div><button class="icon-button" aria-label="Закрыть" (click)="dialog.close()"><app-icon name="close" /></button></header><div class="dialog-body">@if(error()){<div class="notice error" role="alert">{{error()}}</div>}<div class="data-pair"><span>Действует до</span><strong>{{date(invitation().expiresAt)}}</strong></div><div class="data-pair"><span>Доставка письма</span><strong>{{label(invitation().mailState)}}</strong></div>@if(invitation().consumedAt;as dateValue){<div class="data-pair"><span>Принято</span><strong>{{date(dateValue)}}</strong></div>}@if(invitation().revokedAt;as dateValue){<div class="data-pair"><span>Отозвано</span><strong>{{date(dateValue)}}</strong></div>}<p class="inline-note">Повторный выпуск заменяет прежний код. Принятие не восстанавливает отозванные права кабинета.</p></div><footer class="dialog-footer"><button class="btn" (click)="dialog.close()">Закрыть</button>@if(context.allows('membership.manage')&&!invitation().consumedAt){<button class="btn" [disabled]="saving()" (click)="act('reissue')">Выпустить повторно</button>@if(!invitation().revokedAt){<button class="btn danger" [disabled]="saving()" (click)="act('revoke')">Отозвать</button>}}</footer>`})
export class InvitationDialogComponent {
  readonly data=inject<Invitation>(MAT_DIALOG_DATA);readonly invitation=signal(this.data);readonly context=inject(ContextService);readonly dialog=inject(MatDialogRef<InvitationDialogComponent>);private readonly api=inject(ApiService);readonly error=signal('');readonly saving=signal(false);readonly label=stateLabel;readonly date=(value:string)=>dateTime(value,this.context.account()?.timezone??'UTC');
  async act(action:'reissue'|'revoke'):Promise<void>{if(this.saving())return;this.saving.set(true);try{const current=this.invitation(),params={path:{id:current.id},body:mutation(current.revision)};this.invitation.set(await(action==='reissue'?this.api.reissueInvitation(params):this.api.revokeInvitation(params)));}catch(error:unknown){this.error.set(errorMessage(error));}finally{this.saving.set(false);}}
}
