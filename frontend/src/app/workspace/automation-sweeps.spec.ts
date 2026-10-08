import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { ApiService, operationMessage } from '../core/api.service';
import { ContextService } from '../core/context.service';
import { canonicalResources, RealtimeService } from '../core/realtime.service';
import { AutomationSweepsComponent } from './automation-sweeps.component';

describe('automation sweep status',()=>{
  it('recovers a read error and shows persisted traversal facts without claiming command success',async()=>{
    const list=vi.fn().mockRejectedValueOnce(new Error('offline')).mockResolvedValueOnce({items:[{accountId:'account',cycle:7,completedCycles:6}],total:1,page:0,size:50});
    const stop=vi.fn();
    TestBed.configureTestingModule({providers:[provideRouter([]),
      {provide:ApiService,useValue:{listAutomationSweeps:list}},
      {provide:RealtimeService,useValue:{subscribe:()=>stop}},
    ]});
    const context=TestBed.inject(ContextService);
    context.organization.set({id:'org',name:'Company'});
    context.user.set({userId:'user',displayName:'Viewer',email:'v@example.invalid',organizations:[],permissions:['command.read']});
    context.account.set({id:'account',name:'Account',marketplace:'OZON',externalId:'1',timezone:'Europe/Moscow',status:'ACTIVE',keyConfigured:false,revision:1});
    const fixture=TestBed.createComponent(AutomationSweepsComponent);
    fixture.detectChanges();
    await fixture.componentInstance.load();fixture.detectChanges();
    const element:HTMLElement=fixture.nativeElement;
    expect(element.querySelector('[role="alert"]')).not.toBeNull();
    expect(element.textContent).not.toContain('ещё не запускались');
    await fixture.componentInstance.load();fixture.detectChanges();
    expect(element.querySelector('[role="alert"]')).toBeNull();
    expect(element.textContent).toContain('Первая порция ещё не принята');
    expect(element.textContent).toContain('не подтверждает применение цен');
    expect(element.querySelector('a')?.getAttribute('href')).toBe('/tasks');
    expect(list).toHaveBeenLastCalledWith({query:{page:0,size:50}});
    context.user.set({userId:'user',displayName:'Viewer',email:'v@example.invalid',organizations:[],permissions:[]});
    fixture.detectChanges();
    expect(stop).toHaveBeenCalledOnce();
    expect(element.querySelector('section')).toBeNull();
    expect(canonicalResources(['automation-sweeps'],new Set(['command.read']))).toEqual(['automation-sweeps']);
    expect(canonicalResources(['automation-sweeps'],new Set())).toEqual([]);
  });

  it('presents the queue handoff without exposing worker lanes',()=>{
    expect(operationMessage('JOB_LANE_HANDOFF')).toBe('Ожидает обработки данных');
    expect(operationMessage(undefined)).toBe('');
  });
});
