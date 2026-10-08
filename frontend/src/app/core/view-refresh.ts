import { DestroyRef } from '@angular/core';

type ReadPhase = 'idle' | 'loading' | 'current' | 'failed';
interface ReadState {
  pending?: { read: () => Promise<void>; successful: () => boolean };
  running?: Promise<void>;
  phase: ReadPhase;
  observers: Set<(phase: ReadPhase) => void>;
}

/** One active read per view. Bursts retain only the most recent pending request. */
export class ViewRefresh {
  private readonly views = new Map<string, ReadState>();
  private destroyed = false;

  constructor(destroy: DestroyRef) {
    destroy.onDestroy(() => {
      this.destroyed = true;
      for (const view of this.views.values()) { view.pending = undefined; view.observers.clear(); }
    });
  }

  watch(key: string, observer: (phase: ReadPhase) => void): () => void {
    const view = this.view(key);
    view.observers.add(observer);
    observer(view.phase);
    return () => view.observers.delete(observer);
  }

  private view(key: string): ReadState {
    let view = this.views.get(key);
    if (!view) { view = {phase: 'idle', observers: new Set()}; this.views.set(key, view); }
    return view;
  }

  private phase(view: ReadState, phase: ReadPhase): void {
    view.phase = phase;
    for (const observer of view.observers) observer(phase);
  }

  run(key: string, read: () => Promise<void>, successful: () => boolean = () => true): Promise<void> {
    if (this.destroyed) return Promise.resolve();
    const view = this.view(key);
    view.pending = {read, successful};
    this.phase(view, 'loading');
    if (view.running) return view.running;
    const state = view;
    state.running = Promise.resolve().then(async () => {
      let failure: unknown;
      let current = false;
      try {
        while (!this.destroyed && state.pending) {
          const next = state.pending;
          state.pending = undefined;
          try { await next.read(); failure = undefined; current = next.successful(); }
          catch (error: unknown) { failure = error; current = false; }
        }
        if (!this.destroyed) this.phase(state, current ? 'current' : 'failed');
        if (failure !== undefined) throw failure;
      } finally { state.running = undefined; }
    });
    return state.running;
  }
}
