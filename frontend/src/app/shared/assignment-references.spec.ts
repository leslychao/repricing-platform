import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialog, MatDialogRef } from '@angular/material/dialog';
import { ApiService } from '../core/api.service';
import { ContextService } from '../core/context.service';
import { Assignment, Policy } from '../core/models';
import { RealtimeService } from '../core/realtime.service';
import { AssignmentDetachDialogComponent } from './assignment-detach-dialog.component';
import { PolicyDialogComponent } from './dialogs';
import { ReferenceChoiceDialogComponent } from './reference-picker.component';

const assignment: Assignment = {
  id:'assignment',policyId:'policy',accountId:'account',scope:'OFFER',targetId:'offer',
  mode:'MANUAL',status:'ACTIVE',revision:7,
  regularReferences:{selectedPromos:[],protectedPromos:[]},
  temporaryReferences:{selectedPromos:[],protectedPromos:[]},
};

describe('assignment references and removal', () => {
  it('reads each dictionary page on the server and retains selection across pages and search', async () => {
    const list = vi.fn().mockResolvedValueOnce({items:[{id:'one',name:'First',externalId:'1',kind:'CATEGORY',validUntil:'2099-01-01T00:00:00Z'}],total:51,page:0,size:50})
      .mockResolvedValueOnce({items:[{id:'two',name:'Second',externalId:'2',kind:'TYPE',validUntil:'2099-01-01T00:00:00Z'}],total:51,page:1,size:50})
      .mockResolvedValueOnce({items:[],total:0,page:0,size:50});
    const close = vi.fn();
    TestBed.configureTestingModule({providers:[
      {provide:ApiService,useValue:{listCategories:list}},
      {provide:MatDialogRef,useValue:{close}},
      {provide:MAT_DIALOG_DATA,useValue:{kind:'categories',title:'Categories',selected:[],maximum:2,permitted:null,labels:new Map()}},
      {provide:RealtimeService,useValue:{subscribe:()=>()=>undefined}},
    ]});
    const fixture = TestBed.createComponent(ReferenceChoiceDialogComponent);
    const component = fixture.componentInstance;
    await component.load();
    component.toggle(component.options()[0]);
    component.next(1);
    await fixture.whenStable();
    component.toggle(component.options()[0]);
    component.search='  Missing  ';
    component.searchChanged();
    await fixture.whenStable();
    expect(list.mock.calls).toEqual([
      [{query:{page:0,size:50,search:''}}],
      [{query:{page:1,size:50,search:''}}],
      [{query:{page:0,size:50,search:'Missing'}}],
    ]);
    component.confirm();
    expect(close).toHaveBeenCalledWith({ids:['one','two'],labels:new Map([['one','First'],['two','Second']])});
  });

  it('chooses a confirmed active company member by profile instead of accepting a typed user ID', async () => {
    const list=vi.fn().mockResolvedValue({items:[{userId:'user',displayName:'Анна',email:'anna@example.invalid',active:true}],total:1,page:0,size:50});
    const subscribe=vi.fn().mockReturnValue(()=>undefined);const close=vi.fn();
    TestBed.configureTestingModule({providers:[
      {provide:ApiService,useValue:{listMemberships:list}},
      {provide:MatDialogRef,useValue:{close}},
      {provide:MAT_DIALOG_DATA,useValue:{kind:'members',title:'Участник',selected:[],maximum:1,permitted:null,labels:new Map()}},
      {provide:RealtimeService,useValue:{subscribe}},
    ]});
    const fixture=TestBed.createComponent(ReferenceChoiceDialogComponent);
    await fixture.componentInstance.load();fixture.detectChanges();
    const element:HTMLElement=fixture.nativeElement;
    expect(element.textContent).toContain('Анна');
    expect(element.textContent).toContain('anna@example.invalid');
    expect(list).toHaveBeenCalledWith({query:{page:0,size:50,search:'',status:'ACTIVE'}});
    expect(subscribe).toHaveBeenCalledWith(['memberships'],expect.any(Function),true,expect.any(Object));
    element.querySelector<HTMLInputElement>('input[type="radio"]')?.click();
    fixture.componentInstance.confirm();
    expect(close).toHaveBeenCalledWith({ids:['user'],labels:new Map([['user','Анна']])});
  });

  it('does not allow an expired category and does not present a read failure as an empty list', async () => {
    const list=vi.fn().mockResolvedValueOnce({items:[{id:'old',name:'Expired',externalId:'3',kind:'CATEGORY',validUntil:'2000-01-01T00:00:00Z'}],total:1,page:0,size:50})
      .mockRejectedValueOnce(new HttpErrorResponse({status:503}));
    TestBed.configureTestingModule({providers:[
      {provide:ApiService,useValue:{listCategories:list}},
      {provide:MatDialogRef,useValue:{close:vi.fn()}},
      {provide:MAT_DIALOG_DATA,useValue:{kind:'categories',title:'Categories',selected:[],maximum:1,permitted:null,labels:new Map()}},
      {provide:RealtimeService,useValue:{subscribe:()=>()=>undefined}},
    ]});
    const fixture=TestBed.createComponent(ReferenceChoiceDialogComponent);
    await fixture.componentInstance.load();fixture.detectChanges();
    const element:HTMLElement=fixture.nativeElement;
    expect(element.querySelector<HTMLInputElement>('input[type="radio"]')?.disabled).toBe(true);
    await fixture.componentInstance.load();fixture.detectChanges();
    expect(element.querySelector('[role="alert"]')?.textContent).toContain('Сервис временно недоступен');
    expect(element.textContent).not.toContain('Подходящих записей нет');
  });

  it('confirms the reviewed inheritance revision and disables removal for an active scenario', async () => {
    const review=vi.fn().mockRejectedValueOnce(new HttpErrorResponse({status:409,error:{code:'ASSIGNMENT_HAS_ACTIVE_SCENARIO',message:'Сначала завершите временный сценарий'}}))
      .mockResolvedValueOnce({assignmentId:'assignment',assignmentRevision:7,digest:'exact-parent-revision',reason:'NO_ASSIGNMENT'});
    const remove=vi.fn().mockResolvedValue({});const close=vi.fn();
    TestBed.configureTestingModule({providers:[
      {provide:ApiService,useValue:{reviewAssignmentDetach:review,detachAssignment:remove}},
      {provide:MAT_DIALOG_DATA,useValue:assignment},
      {provide:MatDialogRef,useValue:{close}},
    ]});
    const fixture=TestBed.createComponent(AssignmentDetachDialogComponent);
    await fixture.whenStable();fixture.detectChanges();
    const element:HTMLElement=fixture.nativeElement;
    expect(element.querySelector('[role="alert"]')?.textContent).toContain('Сначала завершите временный сценарий');
    expect(element.querySelector<HTMLButtonElement>('.danger')?.disabled).toBe(true);
    await fixture.componentInstance.confirm();expect(remove).not.toHaveBeenCalled();
    await fixture.componentInstance.prepare();fixture.detectChanges();
    expect(element.textContent).toContain('Родительского назначения нет');
    await fixture.componentInstance.confirm();
    expect(remove).toHaveBeenCalledWith({path:{id:'assignment'},body:{clientRequestId:expect.any(String),expectedRevision:7,expectedInheritanceDigest:'exact-parent-revision'}});
    expect(close).toHaveBeenCalledWith(true);
  });

  it('preserves the explicit empty promotion combination instead of broadening it to every combination', async () => {
    const save=vi.fn().mockResolvedValue(assignment);
    TestBed.configureTestingModule({providers:[
      {provide:ApiService,useValue:{createAssignment:save,listAssignments:async()=>({items:[],total:0,page:0,size:50})}},
      {provide:MAT_DIALOG_DATA,useValue:{}},
      {provide:MatDialogRef,useValue:{close:vi.fn()}},
      {provide:MatDialog,useValue:{}},
      {provide:RealtimeService,useValue:{subscribe:()=>()=>undefined}},
    ]});
    TestBed.overrideComponent(PolicyDialogComponent,{set:{template:''}});
    const context=TestBed.inject(ContextService);
    context.account.set({id:'account',name:'Account',marketplace:'OZON',externalId:'1',timezone:'Europe/Moscow',status:'ACTIVE',keyConfigured:true,revision:1});
    const fixture=TestBed.createComponent(PolicyDialogComponent);
    const component=fixture.componentInstance;
    const policy:Policy={id:'policy',name:'Policy',description:'',status:'ACTIVE',revision:1,version:1,settings:component.settings,assignmentsCount:0};
    component.policy.set(policy);
    component.newAssignment();component.promotionIds='promotion';component.addCombination('regular');
    await component.saveAssignment();
    expect(save).toHaveBeenCalledWith(expect.objectContaining({body:expect.objectContaining({regularReferences:expect.objectContaining({allowedCombinations:[[]]})})}));
    component.editAssignment({...assignment,regularReferences:{selectedPromos:['promotion'],protectedPromos:[],allowedCombinations:[[]]}});
    expect(component.combinationRows('regular')).toHaveLength(1);
    await component.saveAssignment();
    expect(save).toHaveBeenLastCalledWith(expect.objectContaining({body:expect.objectContaining({regularReferences:expect.objectContaining({allowedCombinations:[[]]})})}));
  });
});
