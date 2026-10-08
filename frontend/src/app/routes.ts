import { unsavedChangesGuard } from './shared/unsaved-changes';
import { Routes } from '@angular/router';
const grids = ['products','automation','finance','payments','expenses','units','inventory','returns','outlook','competitors','team','invitations','account-team','audit','account-audit','tasks','imports','reports'];
export const routes: Routes = [
  {path:'',pathMatch:'full',redirectTo:'products'},
  ...grids.map(screen=>({path:screen,loadComponent:()=>import('./workspace/workspace.component').then(module=>module.WorkspaceComponent),data:{screen}})),
  {path:'products/:id/:tab',loadComponent:()=>import('./workspace/product.component').then(module=>module.ProductComponent)},
  {path:'products/:id',loadComponent:()=>import('./workspace/product.component').then(module=>module.ProductComponent)},
  {path:'decisions/:id',loadComponent:()=>import('./workspace/decision.component').then(module=>module.DecisionComponent),data:{mode:'decision'}},
  {path:'commands/:id',loadComponent:()=>import('./workspace/decision.component').then(module=>module.DecisionComponent),data:{mode:'command'}},
  {path:'analytics',loadComponent:()=>import('./workspace/analytics.component').then(module=>module.AnalyticsComponent)},
  ...['organization','start','profile'].map(mode=>({path:mode,canDeactivate:[unsavedChangesGuard],loadComponent:()=>import('./workspace/organization.component').then(module=>module.OrganizationComponent),data:{mode}})),
  ...['accounts','integration'].map(mode=>({path:mode,loadComponent:()=>import('./workspace/accounts.component').then(module=>module.AccountsComponent),data:{mode}})),
  ...['notifications','insights','alerts'].map(mode=>({path:mode,loadComponent:()=>import('./workspace/events.component').then(module=>module.EventsComponent),data:{mode}})),
  {path:'help',loadComponent:()=>import('./workspace/events.component').then(module=>module.HelpComponent),data:{mode:'help'}},
  {path:'about',loadComponent:()=>import('./workspace/events.component').then(module=>module.HelpComponent),data:{mode:'about'}},
  {path:'**',loadComponent:()=>import('./workspace/events.component').then(module=>module.HelpComponent),data:{mode:'missing'}},
];
