import { Injectable, signal } from '@angular/core';

@Injectable({ providedIn: 'root' })
export class FeedbackService {
  readonly message = signal<{ text: string; error: boolean } | null>(null);
  private timer?: ReturnType<typeof setTimeout>;
  show(text: string, error = false): void {
    this.close();
    this.message.set({ text, error });
    if (!error) this.timer = setTimeout(() => this.close(), 6500);
  }
  pause(): void { clearTimeout(this.timer); }
  close(): void { clearTimeout(this.timer); this.message.set(null); }
}
