import { Component, computed, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { decimal } from '../core/api.service';
import { DailyIncome } from '../core/models';
import { IconComponent } from '../shared/icon.component';

@Component({
  selector: 'app-daily-income',
  imports: [RouterLink, IconComponent],
  template: `
    <section class="panel">
      <header class="panel-head"><app-icon name="chart" /><h2>Доход по учётным датам</h2></header>
      <div class="panel-body">
        <p class="inline-note">Доход после возвратов, включая компенсации. Выплаты не входят в этот ряд. Неполные дни показывают только включённые записи.</p>
        @if (plot().known) {
          <div class="chart-scroll">
            <svg viewBox="0 0 920 240" role="img" aria-labelledby="daily-income-title daily-income-description">
              <title id="daily-income-title">Доход по дням выбранного месяца</title>
              <desc id="daily-income-description">Зелёные точки — полный период, оранжевые — неполные данные. Для неизвестных значений линия прерывается. Точные суммы и переходы к начислениям находятся в таблице под графиком.</desc>
              <line x1="84" x2="900" [attr.y1]="plot().zeroY" [attr.y2]="plot().zeroY" class="axis" />
              <text x="78" [attr.y]="plot().zeroY + 4" text-anchor="end" class="axis-label">0 ₽</text>
              @for (line of plot().lines; track line.date) {
                <line [attr.x1]="line.x1" [attr.y1]="line.y1" [attr.x2]="line.x2" [attr.y2]="line.y2" [class.incomplete]="!line.complete" class="income-line" />
              }
              @for (point of plot().points; track point.date) {
                <circle [attr.cx]="point.x" [attr.cy]="point.y" r="4" [class.incomplete]="!point.complete" class="income-point"><title>{{point.date}} · {{money(point.income)}} · {{point.complete ? 'Полные данные' : 'Неполные данные'}}</title></circle>
              }
              @for (label of plot().labels; track label.date) {
                <text [attr.x]="label.x" y="224" text-anchor="middle" class="axis-label">{{label.day}}</text>
              }
            </svg>
          </div>
          <div class="legend"><span><i class="complete-dot"></i>Полные данные</span><span><i class="partial-dot"></i>Неполные данные</span><span>Пропуск — нет данных</span></div>
        } @else {
          <p class="muted">Доход за дни этого месяца пока не подтверждён источниками.</p>
        }
        <details><summary>Суммы по дням и начисления</summary>
          <div class="table-scroll"><table>
            <thead><tr><th scope="col">Учётная дата</th><th scope="col">Доход</th><th scope="col">Полнота</th><th scope="col">Событий</th><th scope="col">Начисления</th></tr></thead>
            <tbody>@for (day of days(); track day.date) {<tr>
              <th scope="row">{{day.date}}</th><td>{{money(day.income)}}</td><td>{{day.complete ? 'Полные данные' : 'Неполные данные'}}</td><td>{{day.events}}</td>
              <td><a routerLink="/finance" [queryParams]="{from:day.date,to:day.date}" [attr.aria-label]="'Открыть начисления за ' + day.date">Открыть</a></td>
            </tr>}</tbody>
          </table></div>
        </details>
      </div>
    </section>
  `,
  styles: `
    :host{display:block;margin-top:20px}.chart-scroll,.table-scroll{overflow:auto}svg{display:block;width:100%;min-width:600px;height:auto;max-height:300px}.axis{stroke:#dce2e6;stroke-width:1}.axis-label{font:11px var(--font,Arial,sans-serif);fill:var(--muted)}.income-line{stroke:var(--accent,#14865e);stroke-width:2.5;fill:none}.income-line.incomplete{stroke:#bb7a19;stroke-dasharray:5 4}.income-point{fill:var(--accent,#14865e);stroke:white;stroke-width:1.5}.income-point.incomplete{fill:#bb7a19}.legend{display:flex;gap:18px;flex-wrap:wrap;color:var(--muted);font-size:11px;margin:4px 0 16px}.legend span{display:flex;align-items:center;gap:6px}.legend i{width:7px;height:7px;border-radius:50%}.complete-dot{background:var(--accent,#14865e)}.partial-dot{background:#bb7a19}summary{cursor:pointer;font-size:12px;font-weight:600;padding:8px 0}table{width:100%;border-collapse:collapse;font-size:12px}th,td{text-align:left;padding:10px 12px;border-bottom:1px solid var(--line,#e4e8ed);white-space:nowrap}thead th{color:var(--muted);font-weight:500}tbody th{font-weight:500}a{color:var(--accent,#14865e)}
  `,
})
export class DailyIncomeComponent {
  readonly days = input.required<readonly DailyIncome[]>();
  readonly money = decimal;
  readonly plot = computed(() => {
    // Floating point is used only for SVG coordinates; displayed money stays in decimal strings.
    const rows = this.days().map((day, index) => ({
      ...day,
      value: day.income === undefined ? undefined : Number(day.income),
      x: 84 + index * 816 / Math.max(1, this.days().length - 1),
    }));
    const amounts = rows.flatMap(row => row.value === undefined ? [] : [row.value]);
    const maximum = Math.max(0, ...amounts);
    const minimum = Math.min(0, ...amounts);
    const span = maximum - minimum || 1;
    const position = (value: number): number => 20 + (maximum - value) * 172 / span;
    const points = rows.flatMap(row => row.value === undefined ? [] : [{...row, y: position(row.value)}]);
    const lines = rows.flatMap((row, index) => {
      const previous = rows[index - 1];
      return !previous || previous.value === undefined || row.value === undefined ? [] : [{
        date: row.date, x1: previous.x, y1: position(previous.value), x2: row.x,
        y2: position(row.value), complete: previous.complete && row.complete,
      }];
    });
    return {known: points.length > 0, points, lines, zeroY: position(0),
      labels: rows.map(row => ({date:row.date, day:row.date.slice(-2), x:row.x}))};
  });
}
