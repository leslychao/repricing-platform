import { TestBed } from '@angular/core/testing';
import { MatDialog, MatDialogRef } from '@angular/material/dialog';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { ApiService } from '../core/api.service';
import { ContextService } from '../core/context.service';
import { RealtimeService } from '../core/realtime.service';
import { ViewStateService, initialView } from '../core/view-state.service';
import { TaxDialogComponent } from '../shared/economics-dialogs';
import { DailyIncomeComponent } from './daily-income.component';
import { WorkspaceComponent } from './workspace.component';

describe('monthly factual income', () => {
  it('recovers a failed tax-profile read without claiming to still be loading or losing input', async () => {
    const read = vi.fn().mockRejectedValueOnce(new Error('Unavailable'))
      .mockResolvedValueOnce({revision:7,intervals:[]});
    TestBed.configureTestingModule({providers:[
      {provide:ApiService,useValue:{getCurrentTax:read}},
      {provide:MatDialogRef,useValue:{close:vi.fn()}},
    ]});
    const fixture = TestBed.createComponent(TaxDialogComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    const element: HTMLElement = fixture.nativeElement;
    expect(element.querySelector('[role="status"]')).toBeNull();
    expect(element.querySelector('[role="alert"]')?.textContent).toContain('Повторить чтение профиля');
    const retry = element.querySelector<HTMLButtonElement>('[role="alert"] button');
    expect(retry?.disabled).toBe(false);
    fixture.componentInstance.rate='0.15';
    retry?.click();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(fixture.componentInstance.current()?.revision).toBe(7);
    expect(fixture.componentInstance.rate).toBe('0.15');
    expect(element.querySelector('[role="alert"]')).toBeNull();
    expect(read).toHaveBeenCalledTimes(2);
  });

  it('leaves unknown days as gaps and preserves exact amounts and day links', () => {
    TestBed.configureTestingModule({providers: [provideRouter([])]});
    const fixture = TestBed.createComponent(DailyIncomeComponent);
    fixture.componentRef.setInput('days', [
      {date:'2026-09-01', income:'9007199254740993.27', events:2, complete:false},
      {date:'2026-09-02', events:0, complete:false},
      {date:'2026-09-03', income:'-20.05', events:1, complete:true},
    ]);
    fixture.detectChanges();
    const element: HTMLElement = fixture.nativeElement;
    expect(element.querySelectorAll('circle').length).toBe(2);
    expect(element.querySelectorAll('.income-line').length).toBe(0);
    expect(element.textContent).toContain('9 007 199 254 740 993,27 ₽');
    const rows = element.querySelectorAll('tbody tr');
    expect(rows[1].textContent).toContain('Нет данных');
    expect(rows[1].textContent).toContain('Неполные данные');
    expect(rows[2].textContent).toContain('-20,05 ₽');
    expect(rows[2].querySelector('a')?.getAttribute('href'))
      .toBe('/finance?from=2026-09-03&to=2026-09-03');
  });

  it.each([
    [{month:'2024-02'}, '2024-02-01', '2024-02-29'],
    [{from:'2026-09-03',to:'2026-09-03'}, '2026-09-03', '2026-09-03'],
  ])('opens the displayed accounting period instead of unrelated saved filters', async (params, from, to) => {
    const saved = initialView('finance');
    saved.query.search = 'unrelated';
    saved.query.filters = {from:'2025-01-01',to:'2025-01-31',component:'SERVICE'};
    saved.query.page = 4;
    const save = vi.fn();
    const list = vi.fn().mockResolvedValue({items:[],total:0,page:0,size:50});
    TestBed.configureTestingModule({providers: [
      provideRouter([]),
      {provide: ActivatedRoute, useValue: {data:of({screen:'finance'}),snapshot:{queryParamMap:convertToParamMap(params)}}},
      {provide: ViewStateService, useValue: {open:async()=>saved,save}},
      {provide: ApiService, useValue: {listAccruals:list}},
      {provide: MatDialog, useValue: {}},
      {provide: RealtimeService, useValue: {subscribe:()=>()=>undefined}},
    ]});
    TestBed.overrideComponent(WorkspaceComponent, {set:{template:''}});
    vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    const context = TestBed.inject(ContextService);
    context.organization.set({id:'org',name:'Company'});
    context.user.set({userId:'user',displayName:'User',email:'user@example.invalid',organizations:[],permissions:['finance.read']});
    context.account.set({id:'account',name:'Account',marketplace:'OZON',externalId:'42',timezone:'Europe/Moscow',status:'ACTIVE',keyConfigured:false,revision:1});
    const fixture = TestBed.createComponent(WorkspaceComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    await fixture.componentInstance.reload();
    expect(list).toHaveBeenCalledExactlyOnceWith({query:{search:'',from,to,sort:'date',direction:'desc',page:0,size:50}}, {organizationId:'org',accountId:'account'});
    expect(save).toHaveBeenCalledWith(expect.objectContaining({query:expect.objectContaining({filters:{from,to},search:'',page:0})}));
    expect(saved.query.filters['component']).toBe('SERVICE');
  });
});
