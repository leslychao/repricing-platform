import { ViewRefresh } from './view-refresh';
import { Injectable, OnDestroy, effect, inject, signal } from '@angular/core';
import { ContextService } from './context.service';

interface Subscription {
  resources: string[];
  organizationOnly:boolean;
  changed: () => Promise<boolean>;
  current: boolean;
  refreshing: boolean;
  pending: boolean;
  refreshId: number;
  acknowledged: boolean;
  timer?: ReturnType<typeof setTimeout>;
  firstPendingAt?: number;
}

const RESOURCE_PERMISSIONS:Readonly<Record<string,string>>={
  offers:'catalog.read',policies:'organization.policy.read','policy-assignments':'policy.read',
  decisions:'command.read',commands:'command.read','temporary-runs':'command.read',
  'automation-sweeps':'command.read',
  'marketplace-accounts':'account.read',sources:'account.read',memberships:'membership.read',
  'account-access':'membership.read',organizations:'organization.read',invitations:'membership.read',
  economics:'finance.read','cost-revisions':'finance.read','tax-profile':'finance.read',
  'safety-envelopes':'finance.read',operations:'operation.read',imports:'file.read',reports:'export',
  'stock-pools':'catalog.read','competitor-observations':'competitor.read',
  notifications:'notification.read',audit:'audit.read'
};

export function canonicalResources(resources:readonly string[],permissions:ReadonlySet<string>,organizationOnly=false):string[]{
  const aliases:Readonly<Record<string,string>>={
    'sync-runs':'sources',capabilities:'sources',promotions:'sources','sales-pace':'economics',accounts:'marketplace-accounts',
    'economics/accruals':'economics','economics/summary':'economics','economics/payments':'economics',
    'seller-costs/revisions':'cost-revisions','economics/products':'economics',
    'economics/inventory':'stock-pools','economics/returns':'economics',
    'economics/outlook':'economics','economics/units':'economics','economics/calculations':'economics','economics/sales-pace':'economics',
    insights:'notifications',alerts:'notifications','audit-events':'audit'
  };
  const dependencies:Readonly<Record<string,readonly string[]>>={
    offers:['offers','cost-revisions','tax-profile','safety-envelopes','policy-assignments','policies','decisions','commands','stock-pools','temporary-runs','competitor-observations'],
    'economics/calculations':['decisions','offers','cost-revisions','tax-profile','safety-envelopes','policy-assignments','policies','stock-pools','temporary-runs','competitor-observations'],
    'stock-pools':['stock-pools','offers','commands'],
    'economics/sales-pace':['economics','stock-pools','commands','temporary-runs','offers','sources']
  };
  return [...new Set(resources.flatMap(resource=>dependencies[resource]??[aliases[resource]??resource]))]
    .filter(resource=>!!RESOURCE_PERMISSIONS[resource]&&permissions.has(resource==='policies'&&!organizationOnly?'policy.read':RESOURCE_PERMISSIONS[resource]));
}

@Injectable({ providedIn: 'root' })
export class RealtimeService implements OnDestroy {
  private readonly context = inject(ContextService);
  readonly state = signal<'offline' | 'connecting' | 'syncing' | 'current'>('offline');
  readonly accessInvalidation=signal(0);
  private waitingForAccess=false;
  private socket?: WebSocket;
  private retryTimer?: ReturnType<typeof setTimeout>;
  private retries = 0;
  private epoch = 0;
  private readonly subscriptions = new Map<string, Subscription>();

  constructor() {
    effect(() => {
      const identity=this.context.identity();
      this.context.permissions();
      this.disconnect();
      if (identity.split('/')[0]&&identity.split('/')[1]&&!this.waitingForAccess) this.connect();
    });
  }

  subscribe(resources: string[], changed: () => Promise<boolean>,organizationOnly=false,view?:{owner:ViewRefresh;key:string}): () => void {
    const id = crypto.randomUUID();
    if (this.subscriptions.size >= 32) throw new Error('Слишком много открытых представлений.');
    this.subscriptions.set(id, { resources, changed, organizationOnly, acknowledged: false, current:false, refreshing:false, pending:false, refreshId:0 });
    const stopWatching=view?.owner.watch(view.key,phase=>{const subscription=this.subscriptions.get(id);if(subscription){subscription.current=phase==='current';this.updateState();}});
    this.sendSubscription(id);
    return () => {
      stopWatching?.();
      clearTimeout(this.subscriptions.get(id)?.timer);
      this.subscriptions.delete(id);
      this.updateState();
      if (this.socket?.readyState === WebSocket.OPEN) {
        this.socket.send(JSON.stringify({ type: 'unsubscribe', subscriptionId: id }));
      }
    };
  }

  retry(): void {if(this.waitingForAccess){this.accessInvalidation.update(value=>value+1);return;}this.disconnect();if(this.context.organization())this.connect();}
  accessRefreshed():void{if(!this.waitingForAccess)return;this.waitingForAccess=false;if(this.context.organization())this.retry();}

  private connect(): void {
    const epoch = ++this.epoch;
    for(const subscription of this.subscriptions.values()){subscription.acknowledged=false;subscription.current=false;subscription.refreshing=false;subscription.pending=false;subscription.refreshId++;clearTimeout(subscription.timer);}
    this.state.set('connecting');
    const socket = new WebSocket(`${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/ws`);
    this.socket = socket;
    socket.onopen = () => {
      if (epoch !== this.epoch) return;
      this.state.set('syncing');
      for (const id of this.subscriptions.keys()) this.sendSubscription(id);
    };
    socket.onmessage = event => {
      if (epoch !== this.epoch || typeof event.data !== 'string' || event.data.length > 8192) return;
      let value: unknown;
      try { value = JSON.parse(event.data); } catch { socket.close(1007); return; }
      if (typeof value !== 'object' || value === null || !('type' in value)) return;
      if (value.type === 'subscribed' && 'subscriptionId' in value && typeof value.subscriptionId === 'string') {
        const subscription = this.subscriptions.get(value.subscriptionId);
        if (subscription && !subscription.acknowledged) { subscription.acknowledged = true; this.refresh(subscription); }
        return;
      }
      if (value.type !== 'changed' || !('resource' in value) || typeof value.resource !== 'string') return;
      if (!('organizationId' in value) || value.organizationId !== this.context.organization()?.id) return;
      if (!('accountId' in value) || (value.accountId !== null && value.accountId !== this.context.account()?.id)) return;
      if (!('revision' in value) || typeof value.revision !== 'number' || !Number.isSafeInteger(value.revision) || value.revision < 0) return;
      for (const subscription of this.subscriptions.values()) {
        if (!subscription.acknowledged || (subscription.organizationOnly&&value.accountId!==null)
            || !canonicalResources(subscription.resources,this.context.permissions(),subscription.organizationOnly).includes(value.resource)) continue;
        subscription.current=false;subscription.pending=true;
        this.updateState();
        clearTimeout(subscription.timer);
        const now = Date.now();
        subscription.firstPendingAt ??= now;
        const delay = Math.max(0, Math.min(250, 2000 - (now - subscription.firstPendingAt)));
        subscription.timer = setTimeout(() => {
          subscription.firstPendingAt = undefined;
          this.refresh(subscription);
        }, delay);
      }
    };
    socket.onclose = event => {
      if (epoch !== this.epoch) return;
      this.state.set('offline');
      for (const subscription of this.subscriptions.values()) subscription.acknowledged = false;
      if(event.code===4003){this.waitingForAccess=true;this.context.invalidateAccess();this.accessInvalidation.update(value=>value+1);return;}
      if (this.retries >= 10) return;
      const wait = Math.min(30_000, 1000 * 2 ** this.retries++) * (0.8 + Math.random() * 0.4);
      this.retryTimer = setTimeout(() => this.connect(), wait);
    };
    socket.onerror = () => socket.close();
  }

  private refresh(subscription:Subscription):void {
    clearTimeout(subscription.timer);subscription.firstPendingAt=undefined;
    subscription.current=false;
    if(subscription.refreshing){subscription.pending=true;this.updateState();return;}
    subscription.refreshing=true;subscription.pending=false;
    const epoch=this.epoch,refreshId=++subscription.refreshId;
    this.updateState();
    void subscription.changed().then(success=>{
      if(epoch===this.epoch&&refreshId===subscription.refreshId)
        subscription.current=success&&!subscription.pending;
    }).catch(()=>{if(epoch===this.epoch&&refreshId===subscription.refreshId)subscription.current=false;}).finally(()=>{
      if(epoch!==this.epoch||refreshId!==subscription.refreshId)return;
      subscription.refreshing=false;
      if(subscription.pending)this.refresh(subscription);
      else this.updateState();
    });
  }

  private updateState():void {
    if(this.socket?.readyState!==WebSocket.OPEN)return;
    const current=[...this.subscriptions.values()].every(subscription=>
      subscription.acknowledged&&subscription.current&&!subscription.refreshing&&!subscription.pending);
    if(current)this.retries=0;
    this.state.set(current?'current':'syncing');
  }

  private sendSubscription(id: string): void {
    const subscription = this.subscriptions.get(id);
    if (!subscription || this.socket?.readyState !== WebSocket.OPEN) return;
    const resources=canonicalResources(subscription.resources,this.context.permissions(),subscription.organizationOnly);
    if(!resources.length){subscription.acknowledged=true;this.refresh(subscription);return;}
    subscription.acknowledged = false;
    this.socket.send(JSON.stringify({
      type: 'subscribe', subscriptionId: id, resources,
      organizationId: this.context.organization()?.id, accountId: subscription.organizationOnly?null:this.context.account()?.id,
    }));
  }

  private disconnect(): void {
    this.epoch++;
    clearTimeout(this.retryTimer);
    this.socket?.close();
    this.socket = undefined;
    this.state.set('offline');
    for (const subscription of this.subscriptions.values()) {
      subscription.acknowledged = false;subscription.current=false;subscription.pending=false;
      subscription.refreshing=false;subscription.refreshId++;
      clearTimeout(subscription.timer);
    }
  }
  ngOnDestroy(): void { this.disconnect(); this.subscriptions.clear(); }
}
