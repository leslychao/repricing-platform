import { Component, Directive, Injectable, Injector, inject, viewChild } from '@angular/core';
import { NgForm } from '@angular/forms';
import { MatDialog, MatDialogRef } from '@angular/material/dialog';
import { CanDeactivateFn } from '@angular/router';
import { firstValueFrom } from 'rxjs';

@Directive()
export abstract class GuardedFormDialog {
  protected readonly form = viewChild(NgForm);
  hasUnsavedChanges(): boolean { return Boolean(this.form()?.dirty); }
}

@Component({selector:'app-discard-dialog',template:`<header class="dialog-head"><h2>Закрыть без сохранения?</h2></header><div class="dialog-body"><p>В форме есть несохранённые изменения. При закрытии они будут потеряны.</p></div><footer class="dialog-footer"><button class="btn" (click)="dialog.close(false)">Продолжить редактирование</button><button class="btn danger" (click)="dialog.close(true)">Закрыть без сохранения</button></footer>`})
export class DiscardDialogComponent {readonly dialog=inject(MatDialogRef<DiscardDialogComponent>);}

@Injectable({providedIn:'root'})
export class UnsavedChangesService {
  private readonly dialogs=inject(MatDialog);
  private readonly checks=new Map<()=>boolean,()=>void>();
  private pending?:Promise<boolean>;
  register(check:()=>boolean,discard:()=>void):()=>void{this.checks.set(check,discard);return()=>this.checks.delete(check);}
  async confirm():Promise<boolean>{
    if(![...this.checks.keys()].some(check=>check()))return true;
    if(this.pending)return this.pending;
    this.pending=firstValueFrom(this.dialogs.open(DiscardDialogComponent,{width:'520px'}).afterClosed()).then(result=>{if(result===true){for(const discard of this.checks.values())discard();return true;}return false;}).finally(()=>this.pending=undefined);
    return this.pending;
  }
}

export const unsavedChangesGuard:CanDeactivateFn<unknown>=()=>inject(UnsavedChangesService).confirm();

export function dialogClosePredicate(injector:Injector):(result:unknown,config:unknown,instance:unknown)=>boolean {
  const approved=new WeakSet<object>();
  const pending=new WeakSet<object>();
  return (result,_config,instance)=>{
    if(result!==undefined||typeof instance!=='object'||instance===null||!('hasUnsavedChanges' in instance)||typeof instance.hasUnsavedChanges!=='function')return true;
    if(approved.has(instance)){approved.delete(instance);return true;}
    if(!instance.hasUnsavedChanges())return true;
    if(pending.has(instance))return false;
    pending.add(instance);
    const dialogs=injector.get(MatDialog);
    const current=dialogs.openDialogs.find(dialog=>dialog.componentInstance===instance);
    void firstValueFrom(dialogs.open(DiscardDialogComponent,{width:'520px'}).afterClosed()).then(discard=>{
      pending.delete(instance);
      if(discard&&current){approved.add(instance);current.close();}
    });
    return false;
  };
}
