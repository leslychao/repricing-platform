import { DestroyRef } from '@angular/core';
import { ViewRefresh } from './view-refresh';
import { TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialog, MatDialogRef } from '@angular/material/dialog';
import { emptyRule, RuleEditorComponent } from '../shared/rule-editor.component';
import { CompetitorDialogComponent, PolicyDialogComponent } from '../shared/dialogs';
import { Assignment } from './models';
import { HttpClient, HttpHeaders, HttpRequest, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ApiService, accountInstant, decimal, percentage } from './api.service';
import { initialView, mergeViewGroups, validView } from './view-state.service';
import { MutationIntents, mutationInterceptor } from './mutation-intents';
import { firstValueFrom } from 'rxjs';
import { ExportDialogComponent } from '../shared/workflow-dialogs';
import { zPolicySettings } from '../api/zod.gen';
import { canonicalResources, RealtimeService } from './realtime.service';
import { ContextService } from './context.service';

describe('exact values and time boundaries',()=>{
  it('formats arbitrarily large decimal values without floating point loss',()=>{
    expect(decimal('9007199254740993.27')).toBe('9 007 199 254 740 993,27 ₽');
    expect(decimal(undefined)).toBe('Нет данных');
    expect(decimal('0')).toBe('0 ₽');
    expect(percentage('0.12345')).toBe('12,345%');
    expect(percentage('-0.5')).toBe('-50%');
    expect(percentage('0')).toBe('0%');
  });
  it('uses the account timezone and refuses ambiguous or missing local times',()=>{
    expect(accountInstant('2026-10-08T12:00','Europe/Moscow')).toBe('2026-10-08T09:00:00Z');
    expect(()=>accountInstant('2026-03-29T02:30','Europe/Berlin')).toThrow();
    expect(()=>accountInstant('2026-10-25T02:30','Europe/Berlin')).toThrow();
  });
});

describe('access scope and event contracts',()=>{
  it('invalidates old permissions immediately on account change',()=>{
    const context=new ContextService();
    context.user.set({userId:'u',displayName:'User',email:'u@example.invalid',organizations:[],permissions:['finance.write']});
    context.selectAccount({id:'a',name:'Account',marketplace:'OZON',externalId:'42',timezone:'Europe/Moscow',status:'ACTIVE',keyConfigured:false,revision:1});
    expect(context.allows('finance.write')).toBe(false);
    expect(context.generation()).toBe(1);
  });
  it('normalizes API paths to one authorized resource and rejects unknown resources',()=>{
    const resources=canonicalResources(['economics/accruals','economics/summary','sync-runs','capabilities','unknown'],new Set(['finance.read','account.read']));
    expect(resources).toEqual(['economics','sources']);
    expect(canonicalResources(['economics/accruals','offers'],new Set(['catalog.read']))).toEqual(['offers','stock-pools']);
    expect(canonicalResources(['policies'],new Set(['organization.policy.read']),true)).toEqual(['policies']);
    expect(canonicalResources(['policies'],new Set(['organization.policy.read']))).toEqual([]);
  });
  it('refreshes composed offers for each readable owner without subscribing to financial events without permission',()=>{
    const resources=canonicalResources(['offers'],new Set(['catalog.read','policy.read','command.read']));
    expect(resources).toEqual(['offers','policy-assignments','policies','decisions','commands','stock-pools','temporary-runs']);
    expect(canonicalResources(['offers'],new Set(['catalog.read','finance.read'])))
      .toEqual(['offers','cost-revisions','tax-profile','safety-envelopes','stock-pools']);
    expect(canonicalResources(['economics/sales-pace'],new Set(['finance.read','catalog.read','command.read'])))
      .toEqual(['economics','stock-pools','commands','temporary-runs','offers']);
    expect(canonicalResources(['economics/sales-pace','promotions','sales-pace'],new Set(['finance.read','catalog.read','account.read'])))
      .toEqual(['economics','stock-pools','offers','sources']);
    expect(canonicalResources(['offers'],new Set(['catalog.read','competitor.read'])))
      .toEqual(['offers','stock-pools','competitor-observations']);
  });
});

class BrowserSocket {
  static readonly OPEN=1;static readonly instances:BrowserSocket[]=[];
  readyState=0;onopen:(()=>void)|null=null;onerror:(()=>void)|null=null;
  onclose:((event:{code:number})=>void)|null=null;
  onmessage:((event:{data:string})=>void)|null=null;
  readonly sent:string[]=[];
  constructor(){BrowserSocket.instances.push(this);}
  send(value:string):void{this.sent.push(value);}
  close(code=1000):void{this.readyState=3;this.onclose?.({code});}
}

describe('one realtime connection and access invalidation',()=>{
  beforeEach(()=>{BrowserSocket.instances.length=0;vi.stubGlobal('WebSocket',BrowserSocket);TestBed.configureTestingModule({});});
  afterEach(()=>{TestBed.resetTestingModule();vi.unstubAllGlobals();});
  it('remains synchronizing until every acknowledged view finishes its read',async()=>{
    const context=TestBed.inject(ContextService);
    context.user.set({userId:'u',displayName:'User',email:'u@example.invalid',organizations:[],permissions:['finance.read','catalog.read']});
    context.organization.set({id:'org',name:'Company'});
    const owner=TestBed.inject(RealtimeService);TestBed.tick();
    let firstDone:(value:boolean)=>void=()=>undefined,secondDone:(value:boolean)=>void=()=>undefined;
    const first=new Promise<boolean>(resolve=>firstDone=resolve);
    const second=new Promise<boolean>(resolve=>secondDone=resolve);
    const stopFirst=owner.subscribe(['economics'],()=>first),stopSecond=owner.subscribe(['offers'],()=>second);
    const socket=BrowserSocket.instances[0];socket.readyState=1;socket.onopen?.();
    for(const sent of socket.sent){const message:unknown=JSON.parse(sent);
      if(typeof message==='object'&&message!==null&&'subscriptionId'in message&&typeof message.subscriptionId==='string')
        socket.onmessage?.({data:JSON.stringify({type:'subscribed',subscriptionId:message.subscriptionId})});
    }
    firstDone(true);await first;await Promise.resolve();await Promise.resolve();
    expect(owner.state()).toBe('syncing');
    secondDone(true);await second;await Promise.resolve();await Promise.resolve();
    expect(owner.state()).toBe('current');stopFirst();stopSecond();
  });
  it('tracks failed manual reads and their successful retry without another socket event',async()=>{
    const context=TestBed.inject(ContextService);
    context.user.set({userId:'u',displayName:'User',email:'u@example.invalid',organizations:[],permissions:['finance.read']});
    context.organization.set({id:'org',name:'Company'});
    const owner=TestBed.inject(RealtimeService);TestBed.tick();
    const reads=new ViewRefresh(TestBed.inject(DestroyRef));let successful=true;
    const load=async()=>{await reads.run('view',async()=>undefined,()=>successful);return successful;};
    const stop=owner.subscribe(['economics'],load,false,{owner:reads,key:'view'});
    const socket=BrowserSocket.instances[0];socket.readyState=1;socket.onopen?.();
    const message:unknown=JSON.parse(socket.sent[0]);
    if(typeof message!=='object'||message===null||!('subscriptionId'in message)||typeof message.subscriptionId!=='string')throw new Error('Missing subscription');
    socket.onmessage?.({data:JSON.stringify({type:'subscribed',subscriptionId:message.subscriptionId})});
    await load();await Promise.resolve();await Promise.resolve();
    expect(owner.state()).toBe('current');successful=false;await load();
    expect(owner.state()).toBe('syncing');successful=true;await load();
    expect(owner.state()).toBe('current');stop();
  });
  it('keeps a stable connection after an unchanged profile and waits for refreshed rights on 4003',()=>{
    const context=TestBed.inject(ContextService);
    context.user.set({userId:'u',displayName:'User',email:'u@example.invalid',organizations:[],permissions:['finance.read']});
    context.organization.set({id:'org',name:'Company'});
    const owner=TestBed.inject(RealtimeService);TestBed.tick();
    expect(BrowserSocket.instances.length).toBe(1);
    context.user.update(user=>user?{...user,displayName:'Updated name'}:null);TestBed.tick();
    expect(BrowserSocket.instances.length).toBe(1);
    BrowserSocket.instances[0].close(4003);TestBed.tick();
    expect(context.allows('finance.read')).toBe(false);expect(owner.accessInvalidation()).toBe(1);
    expect(BrowserSocket.instances.length).toBe(1);
    context.user.update(user=>user?{...user,permissions:['organization.read']}:null);TestBed.tick();
    expect(BrowserSocket.instances.length).toBe(1);
    owner.accessRefreshed();
    expect(BrowserSocket.instances.length).toBe(2);
  });
});

describe('view conflict and bounded state',()=>{
  it('chooses the saved monthly period in the account timezone at a month boundary',()=>{
    vi.useFakeTimers();try{vi.setSystemTime(new Date('2026-09-30T21:30:00Z'));
      expect(initialView('analytics','Europe/Moscow').query.filters['month']).toBe('2026-10');
      expect(initialView('analytics','America/New_York').query.filters['month']).toBe('2026-09');
    }finally{vi.useRealTimers();}
  });
  it('merges disjoint groups without overwriting another client',()=>{
    const base=initialView(),local=initialView(),remote=initialView();
    local.columns.order=['price','product'];remote.query.search='server search';
    const result=mergeViewGroups(base,local,remote);
    expect(result.conflicts).toEqual([]);
    expect(result.state.columns.order).toEqual(['price','product']);
    expect(result.state.query.search).toBe('server search');
    expect(base.columns.order).toEqual([]);
  });
  it('does not merge two changes within the same group',()=>{
    const base=initialView(),local=initialView(),remote=initialView();
    local.query.search='local';remote.query.page=2;
    const result=mergeViewGroups(base,local,remote);
    expect(result.conflicts).toEqual(['query']);
    expect(result.state.query).toEqual(remote.query);
    expect(local.query.search).toBe('local');
  });
  it('bounds columns, widths, filters, strings and pages',()=>{
    expect(validView(initialView())).toBe(true);
    const state=initialView();state.columns.widths['price']=801;expect(validView(state)).toBe(false);
    state.columns.widths={};state.query.size=201;expect(validView(state)).toBe(false);
    state.query.size=50;state.query.search='x'.repeat(513);expect(validView(state)).toBe(false);
    state.query.search='';state.query.filters=Object.fromEntries(Array.from({length:21},(_,index)=>[String(index),'x']));expect(validView(state)).toBe(false);
  });
});

describe('mutation retry identity',()=>{
  it('retains the key after an unknown result and isolates scope and intent',async()=>{
    const owner=new MutationIntents();
    const request=(key:string,account:string,value:string)=>new HttpRequest('POST','/api/v1/decisions/id/approve',{clientRequestId:key,expectedRevision:4,value},{headers:new HttpHeaders({'X-Organization-Id':'org','X-Account-Id':account})});
    const first=await owner.prepare(request('first','a','100'));
    const retried=await owner.prepare(request('new-click','a','100'));
    expect(retried.request.body).toEqual(first.request.body);
    const another=await owner.prepare(request('other','b','100'));
    expect(another.request.body).toEqual({clientRequestId:'other',expectedRevision:4,value:'100'});
    const edited=await owner.prepare(request('edited','a','101'));
    expect(edited.request.body).toEqual({clientRequestId:'edited',expectedRevision:4,value:'101'});
    owner.resolve(first.key);
    expect((await owner.prepare(request('next-operation','a','100'))).request.body).toEqual({clientRequestId:'next-operation',expectedRevision:4,value:'100'});
  });
  it('clears unresolved operations at logout',async()=>{
    const owner=new MutationIntents();
    await owner.prepare(new HttpRequest('POST','/api/v1/x',{clientRequestId:'old',expectedRevision:0}));
    owner.clear();
    expect((await owner.prepare(new HttpRequest('POST','/api/v1/x',{clientRequestId:'new',expectedRevision:0}))).request.body).toEqual({clientRequestId:'new',expectedRevision:0});
  });
  it('isolates subjects and query parameters and normalizes field order',async()=>{
    const owner=new MutationIntents();
    const first=await owner.prepare(new HttpRequest('POST','/api/v1/x?mode=one',{clientRequestId:'first',expectedRevision:0,value:'100'}),'user-a');
    expect((await owner.prepare(new HttpRequest('POST','/api/v1/x?mode=one',{value:'100',expectedRevision:0,clientRequestId:'retry'}),'user-a')).request.body).toEqual(first.request.body);
    expect((await owner.prepare(new HttpRequest('POST','/api/v1/x?mode=two',{clientRequestId:'other-query',expectedRevision:0,value:'100'}),'user-a')).request.body).toHaveProperty('clientRequestId','other-query');
    expect((await owner.prepare(new HttpRequest('POST','/api/v1/x?mode=one',{clientRequestId:'other-user',expectedRevision:0,value:'100'}),'user-b')).request.body).toHaveProperty('clientRequestId','other-user');
  });
});

describe('HTTP mutation retry',()=>{
  beforeEach(()=>TestBed.configureTestingModule({providers:[provideHttpClient(withInterceptors([mutationInterceptor])),provideHttpClientTesting()]}));
  afterEach(()=>TestBed.inject(HttpTestingController).verify());
  it('reuses an unknown request through the actual interceptor and releases confirmed rejection',async()=>{
    const client=TestBed.inject(HttpClient),http=TestBed.inject(HttpTestingController);
    const send=(clientRequestId:string)=>firstValueFrom(client.post('/api/v1/reports',{clientRequestId,expectedRevision:0,selectionId:'selection',format:'CSV'}));
    const first=send('first'),firstRejected=expect(first).rejects.toHaveProperty('status',0);
    (await vi.waitFor(()=>http.expectOne('/api/v1/reports'))).error(new ProgressEvent('network'));
    await firstRejected;
    const retry=send('new-click'),retryRejected=expect(retry).rejects.toHaveProperty('status',403);
    const request=await vi.waitFor(()=>http.expectOne('/api/v1/reports'));
    expect(request.request.body.clientRequestId).toBe('first');
    request.flush({message:'Access revoked'},{status:403,statusText:'Forbidden'});await retryRejected;
    const newRequest=send('after-rejection');const confirmed=await vi.waitFor(()=>http.expectOne('/api/v1/reports'));
    expect(confirmed.request.body.clientRequestId).toBe('after-rejection');confirmed.flush({operationId:'operation'});await newRequest;
  });
});

describe('monthly export entry',()=>{
  it('captures one explicit whole month and submits the corresponding unit report',async()=>{
    const createSelection=vi.fn().mockResolvedValue({id:'snapshot',total:1200,expiresAt:'2026-11-01T00:00:00Z'}),createReport=vi.fn().mockResolvedValue({operationId:'job',status:'PENDING'}),close=vi.fn();
    const query={...initialView('analytics','Europe/Moscow').query,filters:{month:'2026-09'}};
    const scope={organizationId:'org',accountId:'account'};
    TestBed.configureTestingModule({providers:[{provide:ApiService,useValue:{createSelection,createReport}},{provide:MatDialogRef,useValue:{close}},{provide:MAT_DIALOG_DATA,useValue:{resource:'monthly-pnl',monthly:true,query,ids:[],requestScope:scope}}]});
    const dialog=TestBed.createComponent(ExportDialogComponent).componentInstance;dialog.reportKind='MONTHLY_UNIT_ECONOMICS';dialog.format='XLSX';
    await dialog.submit();expect(createSelection).toHaveBeenCalledExactlyOnceWith({body:{clientRequestId:expect.any(String),expectedRevision:0,resource:'monthly-unit-economics',query,ids:undefined}},scope);expect(createReport).not.toHaveBeenCalled();
    await dialog.submit();expect(createReport).toHaveBeenCalledExactlyOnceWith({body:{clientRequestId:expect.any(String),expectedRevision:0,selectionId:'snapshot',format:'XLSX',kind:'MONTHLY_UNIT_ECONOMICS'}},scope);expect(close).toHaveBeenCalledWith(true);
  });
});

describe('competitor observation boundary',()=>{
  it('keeps one source identity across retries and sends unknown evidence explicitly',async()=>{
    const createCompetitor=vi.fn().mockRejectedValue(new Error('connection lost'));
    TestBed.configureTestingModule({providers:[{provide:ApiService,useValue:{createCompetitor,listCompetitorSources:()=>Promise.resolve({items:[],total:0,page:0,size:50})}},{provide:MatDialog,useValue:{}},{provide:MatDialogRef,useValue:{close:vi.fn()}},{provide:MAT_DIALOG_DATA,useValue:{}}]});
    const dialog=TestBed.createComponent(CompetitorDialogComponent).componentInstance;
    dialog.offerId='offer';dialog.sellerName='Seller';dialog.segment='same-package-region-payment';dialog.observedAt='2026-10-08T10:00';
    await dialog.save();await dialog.save();
    expect(createCompetitor).toHaveBeenCalledTimes(2);
    const first=createCompetitor.mock.calls[0][0].body,second=createCompetitor.mock.calls[1][0].body;
    expect(first).toMatchObject({offerId:'offer',sellerName:'Seller',segment:'same-package-region-payment',inStock:null,price:null,delivery:null,ownOffer:false,observedAt:'2026-10-08T10:00:00Z'});
    expect(first.sourceId).toBe(second.sourceId);
    expect(first).not.toHaveProperty('quantity');expect(first).not.toHaveProperty('paymentTerms');
  });
});

describe('generated API boundary',()=>{
  beforeEach(()=>TestBed.configureTestingModule({providers:[provideHttpClient(),provideHttpClientTesting()]}));
  afterEach(()=>TestBed.inject(HttpTestingController).verify());
  it('rejects a money number in an otherwise valid offer',async()=>{
    const result=TestBed.inject(ApiService).getOffer({path:{id:'offer'}});
    const rejection=expect(result).rejects.toThrow();
    TestBed.inject(HttpTestingController).expectOne('/api/v1/offers/offer').flush({id:'offer',sku:'sku',name:'Product',revision:1,sellerPrice:12.5});
    await rejection;
  });
  it('preserves exact decimal strings from the server',async()=>{
    const result=TestBed.inject(ApiService).getOffer({path:{id:'offer'}});
    TestBed.inject(HttpTestingController).expectOne('/api/v1/offers/offer').flush({id:'offer',sku:'sku',name:'Product',revision:1,sellerPrice:'9007199254740993.27'});
    expect((await result).sellerPrice).toBe('9007199254740993.27');
  });
  it('does not accept an unsupported strategy as a valid policy',()=>{
    expect(zPolicySettings.safeParse({regularRule:{strategy:'FIXED_PRICE'}}).success).toBe(false);
  });
  it('accepts ready files with incomplete source data without inventing data completeness',async()=>{
    const result=TestBed.inject(ApiService).getReport({path:{id:'report'}});
    TestBed.inject(HttpTestingController).expectOne('/api/v1/reports/report').flush({id:'report',name:'Monthly result',status:'READY',createdAt:'2026-10-08T00:00:00Z',revision:1,format:'XLSX',rowCount:12,operationId:'operation',dataStatus:'INCOMPLETE'});
    expect((await result).dataStatus).toBe('INCOMPLETE');
  });
});


describe('policy editing and assignment transitions',()=>{
  it('removes holding corridors when switching to another strategy',()=>{
    const fixture=TestBed.createComponent(RuleEditorComponent);
    const rule=emptyRule();rule.target='100';rule.corridorMinimum='90';rule.corridorMaximum='110';
    fixture.componentRef.setInput('rule',rule);
    fixture.componentInstance.strategy('TARGET_PROFITABILITY');
    expect(rule.corridorMinimum).toBeNull();expect(rule.corridorMaximum).toBeNull();
    expect(rule.metric).toBe('PROFIT');
    fixture.componentInstance.strategy('FOLLOW_COMPETITOR');
    expect(rule.target).toBeNull();expect(rule.corridorMinimum).toBeNull();
  });
  it('uses resume for a paused assignment and pause for an active assignment',async()=>{
    const resume=vi.fn().mockResolvedValue(undefined),pause=vi.fn().mockResolvedValue(undefined);
    TestBed.configureTestingModule({providers:[
      {provide:ApiService,useValue:{resumeAssignment:resume,pauseAssignment:pause}},
      {provide:MatDialog,useValue:{}},{provide:MatDialogRef,useValue:{}},
      {provide:MAT_DIALOG_DATA,useValue:{}},
      {provide:RealtimeService,useValue:{subscribe:()=>()=>undefined}}
    ]});
    const fixture=TestBed.createComponent(PolicyDialogComponent);
    const references={selectedPromos:[],protectedPromos:[]};
    const assignment:Assignment={id:'assignment',policyId:'policy',accountId:'account',
      scope:'ACCOUNT',mode:'MANUAL',status:'PAUSED',revision:8,
      regularReferences:references,temporaryReferences:references};
    await fixture.componentInstance.assignmentAction(assignment,'pause');
    expect(resume).toHaveBeenCalledExactlyOnceWith({path:{id:'assignment'},
      body:{clientRequestId:expect.any(String),expectedRevision:8}});
    expect(pause).not.toHaveBeenCalled();
    await fixture.componentInstance.assignmentAction({...assignment,status:'ACTIVE',revision:9},'pause');
    expect(pause).toHaveBeenCalledExactlyOnceWith({path:{id:'assignment'},
      body:{clientRequestId:expect.any(String),expectedRevision:9}});
  });
});


describe('bounded view refresh',()=>{
  it('serializes reads and retains only the most recent request in a burst',async()=>{
    const reads=new ViewRefresh(TestBed.inject(DestroyRef));
    let release:()=>void=()=>undefined;
    const wait=new Promise<void>(resolve=>release=resolve);
    const calls:string[]=[];
    const initial=reads.run('view',async()=>{calls.push('initial');await wait;});
    await Promise.resolve();
    void reads.run('view',async()=>{calls.push('superseded');});
    const latest=reads.run('view',async()=>{calls.push('latest');});
    expect(calls).toEqual(['initial']);release();await initial;await latest;
    expect(calls).toEqual(['initial','latest']);
  });
  it('drops queued work when its owner is destroyed',async()=>{
    const reads=new ViewRefresh(TestBed.inject(DestroyRef));
    let release:()=>void=()=>undefined;
    const wait=new Promise<void>(resolve=>release=resolve);const pending=vi.fn().mockResolvedValue(undefined);
    const initial=reads.run('view',()=>wait);await Promise.resolve();
    void reads.run('view',pending);TestBed.resetTestingModule();release();await initial;
    expect(pending).not.toHaveBeenCalled();
  });
});
