import { ChangeDetectionStrategy, Component, input } from '@angular/core';

const PATHS: Record<string, string> = {
  box: 'M12 3 3 8v9l9 5 9-5V8L12 3ZM3 8l9 5 9-5M12 13v9M7.5 5.5l9 5',
  chart: 'M4 3v17h17M8 15V9m5 6V5m5 10v-4',
  search: 'M21 21l-5-5M18 10a8 8 0 1 1-16 0 8 8 0 0 1 16 0',
  plug: 'm7 7 10 10M7 3v4m10 10v4M3 7h4m10 10h4M5 12l7-7 7 7-7 7z',
  users: 'M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2m20 0v-2a4 4 0 0 0-3-3.87M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8m8-7.87a4 4 0 0 1 0 7.75',
  history: 'M3 12a9 9 0 1 0 3-6.7L3 8M3 3v5h5m4-1v5l3 2',
  building: 'M4 22V3h16v19M8 7h1m6 0h1M8 11h1m6 0h1M8 15h1m6 0h1M9 22v-4h6v4',
  store: 'M3 10v11h18V10M2 10l2-7h16l2 7M2 10h20M9 21v-7h6v7',
  bell: 'M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9m-8 12a2 2 0 0 0 4 0',
  chevron: 'm9 5 7 7-7 7', down: 'm6 9 6 6 6-6', menu: 'M3 6h18M3 12h18M3 18h18',
  close: 'm6 6 12 12M6 18 18 6', plus: 'M12 5v14M5 12h14',
  filter: 'M3 5h18l-7 8v6l-4 2v-8L3 5Z', columns: 'M3 4h18v16H3zM10 4v16m5-16v16',
  refresh: 'M20 4v6h-6M4 20v-6h6M20 9A8 8 0 0 0 6 6M4 15a8 8 0 0 0 14 3',
  upload: 'M12 16V3m-5 5 5-5 5 5M3 16v5h18v-5', download: 'M12 3v13m-5-5 5 5 5-5M3 16v5h18v-5',
  shield: 'M12 3 4 6v6c0 4 4 7 8 9 4-2 8-5 8-9V6l-8-3Zm-4 9 3 3 5-6',
  edit: 'm15 4 5 5-11 11H4v-5L15 4ZM12 7l5 5',
  info: 'M12 11v6m0-10v.01M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0',
  clock: 'M12 7v5l3 2M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0',
  check: 'm5 12 4 4L19 6', alert: 'm12 3 10 18H2L12 3Zm0 6v5m0 3v.01',
  user: 'M20 21v-2a6 6 0 0 0-6-6h-4a6 6 0 0 0-6 6v2M16 5a4 4 0 1 1-8 0 4 4 0 0 1 8 0',
  logout: 'M9 5H3v14h6m5-14 7 7-7 7M7 12h14',
  sliders: 'M4 5h16M4 12h16M4 19h16M8 3v4m8 3v4m-6 3v4',
  wallet: 'M4 5h15v15H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h12M19 10h-6v6h6',
  pause: 'M8 4v16M16 4v16', play: 'm7 4 14 8-14 8V4',
  arrow: 'M4 12h16m-6-6 6 6-6 6', grip: 'M9 5h.01M15 5h.01M9 12h.01M15 12h.01M9 19h.01M15 19h.01',
};

@Component({
  selector: 'app-icon',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path [attr.d]="path()" /></svg>',
  styles: ':host{display:inline-flex;width:18px;height:18px;flex:none}svg{width:100%;height:100%}',
})
export class IconComponent {
  readonly name = input('box');
  path(): string { return PATHS[this.name()] ?? PATHS['box']; }
}
