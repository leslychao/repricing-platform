export type * from '../api/types.gen';
export interface Page<T> { items:T[];total:number;page:number;size:number; }
export interface GridColumn { id: string; label: string; group?: string; minWidth?: number; width?: number; hidden?: boolean; }
export interface GridCell { text: string; detail?: string; kind?: 'text' | 'status' | 'product' | 'money'; imageUrl?: string; tone?: string; action?: boolean; }
export interface GridRow { id: string; cells: Record<string, GridCell>; revision?: number; }
export interface ScreenDefinition {
  key: string; title: string; section: string; scope: 'account' | 'organization' | 'user';
  resource: string; columns: GridColumn[];
}
export interface FilterField {
  id:string; label:string; type:'text'|'date'|'select'|'reference'; required?:boolean; reference?:'offers'|'warehouses'|'categories'|'promotions'|'competitor-sources';
  options?:ReadonlyArray<{value:string;label:string}>;
}
