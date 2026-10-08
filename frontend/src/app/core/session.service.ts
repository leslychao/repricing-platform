import { ViewRefresh } from './view-refresh';
import { UnsavedChangesService } from '../shared/unsaved-changes';
import { DestroyRef, Injectable, effect, inject, signal, untracked } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { ApiService } from './api.service';
import { ContextService } from './context.service';
import { Account, Organization } from './models';
import { RealtimeService } from './realtime.service';

@Injectable({providedIn:'root'})
export class SessionService {
  private readonly unsaved=inject(UnsavedChangesService);private readonly api=inject(ApiService);private readonly context=inject(ContextService);
  private readonly realtime=inject(RealtimeService);readonly accessError=signal('');
  private accountsEpoch=0;private refreshEpoch=0;private readonly reads=new ViewRefresh(inject(DestroyRef));
  constructor(){
    effect(()=>{const invalidation=this.realtime.accessInvalidation();if(invalidation>0)untracked(async()=>{await this.refreshAccess();return !this.accessError();});});
    effect(onCleanup=>{const identity=this.context.identity();if(!identity.split('/')[1])return;
      untracked(()=>{const stop=this.realtime.subscribe(['organizations','memberships'],async()=>{await this.refreshAccess();return !this.accessError();},true,{owner:this.reads,key:'access'});
        const stopAccount=this.realtime.subscribe(['account-access'],async()=>{await this.refreshAccess();return !this.accessError();},false,{owner:this.reads,key:'access'});
        onCleanup(()=>{stop();stopAccount();this.refreshEpoch++;});});
    });
    inject(DestroyRef).onDestroy(()=>this.refreshEpoch++);
  }
  refreshAccess():Promise<void>{this.refreshEpoch++;return this.reads.run('access',()=>this.refreshAccessCurrent(),()=>!this.accessError());}
  private async refreshAccessCurrent():Promise<void>{
    const epoch=this.refreshEpoch,generation=this.context.generation();
    try{const profile=await this.api.getMe();if(epoch!==this.refreshEpoch||generation!==this.context.generation())return;
      this.context.user.set(profile);const organization=profile.organizations.find(value=>value.id===this.context.organization()?.id);if(organization)this.context.organization.set(organization);this.accessError.set('');this.realtime.accessRefreshed();
    }catch(error:unknown){if(epoch!==this.refreshEpoch||generation!==this.context.generation())return;
      if(error instanceof HttpErrorResponse&&error.status===403){
        try{const profile=await this.api.getMe({}, {organizationId:''});if(epoch===this.refreshEpoch){this.context.clearSelection();this.context.user.set(profile);this.realtime.accessRefreshed();this.accessError.set('Права изменились. Выберите доступную компанию заново.');}}
        catch{if(epoch===this.refreshEpoch)this.accessError.set('Не удалось перечитать доступные компании. Повторите загрузку.');}
      }
      else this.accessError.set('Не удалось обновить права доступа. Повторите загрузку.');
    }
  }
  async selectOrganization(organization:Organization):Promise<boolean>{if(!(await this.unsaved.confirm()))return false;this.context.selectOrganization(organization);await this.loadAccounts();return true;}
  async selectAccount(account:Account):Promise<void>{if(account.id===this.context.account()?.id||!(await this.unsaved.confirm()))return;this.context.selectAccount(account);await this.loadAccounts();}
  loadAccounts(page=this.context.accountsPage()):Promise<void>{this.accountsEpoch++;return this.reads.run('account-list',()=>this.loadAccountsCurrent(page));}
  private async loadAccountsCurrent(page:number):Promise<void>{
    if(!this.context.organization())return;
    const generation=this.context.generation(),epoch=++this.accountsEpoch;
    const accounts=await this.api.listAccounts({query:{page,size:50}});
    if(generation!==this.context.generation()||epoch!==this.accountsEpoch)return;
    this.context.accounts.set(accounts.items);this.context.accountsTotal.set(accounts.total);this.context.accountsPage.set(page);
    if(accounts.items.length&&!this.context.account())this.context.selectAccount(accounts.items[0]);
    const selectedGeneration=this.context.generation();
    const user=await this.api.getMe();
    if(selectedGeneration===this.context.generation())this.context.user.set(user);
  }
}
