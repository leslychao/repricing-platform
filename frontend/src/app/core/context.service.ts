import { Injectable, computed, signal } from '@angular/core';
import { Account, CurrentUser, Organization } from './models';

@Injectable({ providedIn: 'root' })
export class ContextService {
  readonly user = signal<CurrentUser | null>(null);
  readonly organization = signal<Organization | null>(null);
  readonly account = signal<Account | null>(null);
  readonly accounts = signal<Account[]>([]);
  readonly accountsTotal = signal(0);
  readonly accountsPage = signal(0);
  readonly generation = signal(0);
  readonly permissions = computed(() => new Set(this.user()?.permissions ?? []),{
    equal:(left,right)=>left.size===right.size&&[...left].every(value=>right.has(value)),
  });
  readonly identity = computed(() => `${this.user()?.userId ?? ''}/${this.organization()?.id ?? ''}/${this.account()?.id ?? ''}`);
  selectOrganization(organization: Organization): void {
    this.clearPermissions();
    this.organization.set(organization);
    this.account.set(null);
    this.accounts.set([]);this.accountsTotal.set(0);this.accountsPage.set(0);
    this.generation.update(value => value + 1);
  }
  selectAccount(account: Account): void {
    this.clearPermissions();
    this.account.set(account);
    this.generation.update(value => value + 1);
  }
  allows(permission: string): boolean { return this.permissions().has(permission); }
  clearSelection():void{this.clearPermissions();this.organization.set(null);this.account.set(null);this.accounts.set([]);this.accountsTotal.set(0);this.accountsPage.set(0);this.generation.update(value=>value+1);}
  invalidateAccess():void{this.clearPermissions();this.generation.update(value=>value+1);}
  private clearPermissions():void { this.user.update(user=>user?{...user,permissions:[]}:null); }
  clear(): void {
    this.user.set(null);
    this.organization.set(null);
    this.account.set(null);
    this.accounts.set([]);this.accountsTotal.set(0);this.accountsPage.set(0);
    this.generation.update(value => value + 1);
  }
}
