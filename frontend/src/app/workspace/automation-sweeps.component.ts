import { Component, DestroyRef, effect, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ApiService, dateTime, errorMessage } from '../core/api.service';
import { ContextService } from '../core/context.service';
import { AutomationSweep } from '../core/models';
import { RealtimeService } from '../core/realtime.service';
import { ViewRefresh } from '../core/view-refresh';

@Component({selector:'app-automation-sweeps',imports:[RouterLink],template:`
  @if(context.account()&&context.allows('command.read')){
    <section class="panel"><header class="panel-head"><h2>Обход автоматизации</h2></header><div class="panel-body">
      @if(error()){<div class="notice error" role="alert">{{error()}}<button class="btn" (click)="load()">Повторить</button></div>}
      @else if(loading()){<p role="status">Загружаем состояние обхода…</p>}
      @else{
        @for(sweep of sweeps();track sweep.accountId){
          <dl><dt>Текущий цикл</dt><dd>{{sweep.cycle}}</dd><dt>Завершено полных обходов</dt><dd>{{sweep.completedCycles}}</dd><dt>Начало текущего цикла</dt><dd>{{sweep.cycleStartedAt?date(sweep.cycleStartedAt):'Первая порция ещё не принята'}}</dd></dl>
        }@empty{<p>Автоматические обходы ещё не запускались.</p>}
        @if(total()>50){<div class="pagination"><button class="btn" [disabled]="page()===0" (click)="changePage(-1)">Назад</button><span>Страница {{page()+1}} · всего {{total()}}</span><button class="btn" [disabled]="(page()+1)*50>=total()" (click)="changePage(1)">Далее</button></div>}
      }
      <p class="inline-note">Завершённый обход означает проверку области автоматизации. Он не подтверждает применение цен. <a routerLink="/tasks">Состояние фоновых задач</a></p>
    </div></section>
  }
`,styles:`dl{display:grid;grid-template-columns:minmax(150px,1fr) 2fr;gap:10px 20px}dt{color:var(--muted)}dd{margin:0}@media(max-width:600px){dl{grid-template-columns:1fr;gap:5px}dd{margin-bottom:10px}}`})
export class AutomationSweepsComponent {
  readonly context=inject(ContextService);
  private readonly api=inject(ApiService);
  private readonly realtime=inject(RealtimeService);
  private readonly reads=new ViewRefresh(inject(DestroyRef));
  readonly sweeps=signal<AutomationSweep[]>([]);
  readonly page=signal(0);
  readonly total=signal(0);
  readonly loading=signal(false);
  readonly error=signal('');
  private epoch=0;
  readonly date=(value:string)=>dateTime(value,this.context.account()?.timezone??'UTC');

  constructor(){
    effect(onCleanup=>{
      this.context.identity();this.context.permissions();
      this.epoch++;this.sweeps.set([]);this.page.set(0);this.total.set(0);this.error.set('');
      if(this.context.account()&&this.context.allows('command.read')){
        const stop=this.realtime.subscribe(['automation-sweeps'],async()=>{await this.load();return !this.error();},false,{owner:this.reads,key:'sweeps'});
        onCleanup(()=>{this.epoch++;stop();});
      }
    });
  }

  changePage(delta:number):void{this.page.update(page=>Math.max(0,page+delta));void this.load();}
  load():Promise<void>{this.epoch++;return this.reads.run('sweeps',()=>this.read(),()=>!this.error());}
  private async read():Promise<void>{
    if(!this.context.account()||!this.context.allows('command.read'))return;
    const epoch=++this.epoch;this.loading.set(true);this.error.set('');
    try{
      const result=await this.api.listAutomationSweeps({query:{page:this.page(),size:50}});
      if(epoch!==this.epoch)return;
      this.sweeps.set(result.items);this.total.set(result.total);
    }catch(error:unknown){if(epoch===this.epoch)this.error.set(errorMessage(error));}
    finally{if(epoch===this.epoch)this.loading.set(false);}
  }
}
