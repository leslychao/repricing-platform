import { HttpClient, HttpErrorResponse, HttpInterceptorFn, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { catchError, throwError } from 'rxjs';
import { AuthNavigationService } from './auth-navigation.service';
import { ContextService } from './context.service';
import { GeneratedApiClient } from '../api/angular-client.gen';
import { Temporal } from '@js-temporal/polyfill';

export const contextInterceptor: HttpInterceptorFn = (request, next) => {
  if (!request.url.startsWith('/api/v1')) return next(request);
  const context = inject(ContextService);
  const authentication = inject(AuthNavigationService);
  let headers = request.headers;
  const organization = context.organization();
  const account = context.account();
  if (organization && !headers.has('X-Organization-Id')) headers = headers.set('X-Organization-Id', organization.id);
  if (account && !headers.has('X-Account-Id')) headers = headers.set('X-Account-Id', account.id);
  return next(request.clone({ headers, withCredentials: true })).pipe(catchError((error:unknown)=>{
    if(error instanceof HttpErrorResponse&&error.status===401){
      context.clear();
      authentication.signIn();
    }
    return throwError(()=>error);
  }));
};

@Injectable({ providedIn: 'root' })
export class ApiService extends GeneratedApiClient {}

export function errorMessage(error: unknown): string {
  if (!(error instanceof HttpErrorResponse)) return 'Не удалось завершить операцию. Повторите попытку.';
  if (error.status === 0) return 'Нет связи с сервером. Проверьте соединение.';
  const value: unknown = error.error;
  if (typeof value === 'object' && value !== null && 'message' in value && typeof value.message === 'string') {
    const code='code' in value&&typeof value.code==='string'?value.code:'';
    const requestId='requestId' in value&&typeof value.requestId==='string'?value.requestId:'';
    const reference=[code,requestId?'ID: '+requestId:''].filter(Boolean).join(' · ');
    return value.message+(reference?' ('+reference+')':'');
  }
  const messages: Record<number, string> = {
    401: 'Для продолжения войдите в аккаунт.', 403: 'Недостаточно прав для этого действия.',
    404: 'Объект недоступен или не найден.', 409: 'Состояние изменилось. Обновите данные перед повтором.',
    412: 'Эту запись изменили. Перечитайте её перед сохранением.',
    422: 'Проверьте введённые значения.', 503: 'Сервис временно недоступен. Введённые данные сохранены в форме.',
  };
  return messages[error.status] ?? 'Сервер не смог завершить операцию. Повторите попытку позже.';
}

export function mutation(expectedRevision = 0): { clientRequestId: string; expectedRevision: number } {
  return { clientRequestId: crypto.randomUUID(), expectedRevision };
}

export function operationMessage(value:string|undefined):string {
  return value==='JOB_LANE_HANDOFF'?'Ожидает обработки данных':value??'';
}

export function decimal(value: string | null | undefined, suffix = ' ₽'): string {
  if (value === undefined || value === null || value === '') return 'Нет данных';
  if (!/^-?\d+(\.\d+)?$/.test(value)) return 'Нет данных';
  const [integer, fraction = ''] = value.split('.');
  return `${integer.replace(/\B(?=(\d{3})+(?!\d))/g, ' ')}${fraction ? ',' + fraction : ''}${suffix}`;
}

/** API ratios are fractions; shift decimal digits exactly for presentation as percentages. */
export function percentage(value: string | undefined): string {
  if (!value || !/^-?\d+(\.\d+)?$/.test(value)) return 'Нет данных';
  const negative = value.startsWith('-');
  const [integer, fraction = ''] = (negative ? value.slice(1) : value).split('.');
  const digits = fraction.padEnd(2, '0');
  const whole = (integer + digits.slice(0, 2)).replace(/^0+(?=\d)/, '');
  const remainder = digits.slice(2).replace(/0+$/, '');
  return decimal(`${negative ? '-' : ''}${whole}${remainder ? '.' + remainder : ''}`, '%');
}

export function dateTime(value: string | undefined, timeZone = 'UTC'): string {
  if (!value) return 'Нет данных';
  if(/^\d{4}-\d{2}-\d{2}$/.test(value))return value.split('-').reverse().join('.');
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return value;
  return new Intl.DateTimeFormat('ru-RU', { dateStyle: 'short', timeStyle: 'short', timeZone }).format(parsed);
}

export function localDateTime(value:string,timeZone:string):string {
  return Temporal.Instant.from(value).toZonedDateTimeISO(timeZone).toPlainDateTime().toString({smallestUnit:'minute'});
}

export function accountInstant(value:string,timeZone:string):string {
  return Temporal.ZonedDateTime.from(`${value}[${timeZone}]`,{disambiguation:'reject'}).toInstant().toString();
}

export function stateLabel(value: string | undefined): string {
  if (!value) return 'Нет данных';
  const labels: Record<string, string> = { DETACHED:'Назначение снято', COMMITTED:'Применён', INVALID:'Ошибки в данных', WAITING:'Ожидает продолжения', CAPTURING:'Фиксация состава', PREVIEW_PENDING:'Расчёт в очереди', PREVIEW_READY:'Расчёт сохранён', DEAD:'Не завершена', PREPARING:'Подготовка',
    SUCCEEDED:'Завершено', VALIDATING:'Проверяется', VALIDATED:'Проверен', APPLYING:'Применяется', APPLIED:'Применён', STALE:'Нужна повторная проверка', EXPIRED:'Срок истёк', UPLOADING:'Загружается', ARCHIVED:'В архиве', COMPLETE:'Полные данные', CHECKING:'Проверяется', INACTIVE:'Неактивен', FINISHING:'Завершается', FINISHED:'Завершён', BLOCKED:'Заблокирован', CALCULATED:'Рассчитано', NOT_CALCULATED:'Нет расчёта', NO_CHANGE:'Изменений нет', PRELIMINARY:'Предварительно', MANAGER:'Менеджер', OPERATOR:'Оператор', VIEWER:'Наблюдатель', DRAFT: 'Черновик', PUBLISHED: 'Опубликована', ACTIVE: 'Активен', PAUSED: 'Пауза',
    PENDING: 'В очереди', RUNNING: 'Выполняется', COMPLETED: 'Завершено', FAILED: 'Ошибка',
    UNKNOWN: 'Результат неизвестен', CONFIRMED: 'Подтверждено', RECONCILING: 'Идёт сверка',
    CONNECTED: 'Подключён', DISCONNECTED: 'Не подключён', NEEDS_CHECK: 'Требует проверки',
    PREVIEW: 'Расчёт без применения', MANUAL: 'Ручное подтверждение', AUTO: 'Автоматический допуск',
    OWNER: 'Владелец', ADMIN: 'Администратор', MEMBER: 'Участник', INVITED: 'Приглашён',
    INCOMPLETE: 'Неполные данные', AVAILABLE: 'Доступно', UNAVAILABLE: 'Недоступно',
    CANCELLED: 'Отменено', ACCEPTED: 'Принято', READY: 'Готово', REVOKED: 'Отозвано',
  };
  return labels[value] ?? value;
}
