import { bootstrapApplication } from '@angular/platform-browser';
import { provideHttpClient, withInterceptors, withXsrfConfiguration } from '@angular/common/http';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { AppComponent } from './app/app.component';
import { routes } from './app/routes';
import { mutationInterceptor } from './app/core/mutation-intents';
import { contextInterceptor } from './app/core/api.service';
import { Injector } from '@angular/core';
import { MAT_DIALOG_DEFAULT_OPTIONS } from '@angular/material/dialog';
import { dialogClosePredicate } from './app/shared/unsaved-changes';

bootstrapApplication(AppComponent, {
  providers: [
    {provide:MAT_DIALOG_DEFAULT_OPTIONS,useFactory:(injector:Injector)=>({maxWidth:'calc(100vw - 24px)',autoFocus:'first-tabbable',restoreFocus:true,closePredicate:dialogClosePredicate(injector)}),deps:[Injector]},
    provideRouter(routes, withComponentInputBinding()),
    provideHttpClient(
      withInterceptors([contextInterceptor,mutationInterceptor]),
      withXsrfConfiguration({ cookieName: 'XSRF-TOKEN', headerName: 'X-XSRF-TOKEN' }),
    ),
  ],
}).catch((error: unknown) => console.error('Не удалось запустить интерфейс.', error));
