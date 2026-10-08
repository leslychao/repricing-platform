import { Component, input } from '@angular/core';
import { decimal, stateLabel } from '../core/api.service';
import { TemporaryPermissionState } from '../core/models';

@Component({selector:'app-risk-permission',template:`
  @if(state();as value){
    @if(value.permission;as permission){
      <h3>Экономическое согласие</h3>
      <div class="data-pair"><span>Состояние</span><strong>{{label(permission.status)}}</strong></div>
      <div class="data-pair"><span>Нижняя прибыль покупки</span><strong>{{money(permission.keptProfitFloor)}}</strong></div>
      <div class="data-pair"><span>Лимит потерь B</span><strong>{{money(permission.lossBudget)}}</strong></div>
      <div class="data-pair"><span>Внешняя граница количества</span><strong>{{money(permission.boundedQuantity,' ед.')}}</strong></div>
      @if(value.usage;as usage){
        <div class="data-pair"><span>Признанные потери</span><strong>{{money(usage.recognizedLoss)}}</strong></div>
        <div class="data-pair"><span>Открытая ответственность</span><strong>{{money(usage.potentialLoss)}}</strong></div>
        <div class="data-pair"><span>Занято из лимита</span><strong>{{money(usage.occupied)}}</strong></div>
        <div class="data-pair"><span>Осталось</span><strong>{{money(usage.remaining)}}</strong></div>
      }
      <p class="inline-note">{{permission.externalBoundaryEvidence || 'Отрицательная прибыль без подтверждённой внешней границы не допускается.'}}</p>
      <p class="inline-note">Отзыв согласия останавливает новые риски, но не освобождает ответственность по уже принятым заказам.</p>
    }@else{<p class="muted">Экономическое согласие не выдано.</p>}
  }
`})
export class RiskPermissionComponent {
  readonly state=input<TemporaryPermissionState|null>(null);readonly money=decimal;readonly label=stateLabel;
}
