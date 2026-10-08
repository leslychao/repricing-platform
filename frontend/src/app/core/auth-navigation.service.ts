import { Injectable, signal } from '@angular/core';

@Injectable({providedIn:'root'})
export class AuthNavigationService {
  readonly failure=signal('');
  private redirecting=false;
  private readonly key='repricer.login-attempt';

  signIn(manual=false):void {
    if(this.redirecting)return;
    const previous=Number(sessionStorage.getItem(this.key));
    if(!manual&&previous>0&&Date.now()-previous<120_000){
      this.failure.set('Вход не создал рабочую сессию. Повторите вход или обратитесь к администратору.');
      return;
    }
    sessionStorage.setItem(this.key,String(Date.now()));
    this.redirecting=true;
    location.replace('/login');
  }

  authenticated():void {sessionStorage.removeItem(this.key);this.failure.set('');}
}
