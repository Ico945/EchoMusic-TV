import { inject, type InjectionKey } from 'vue';

export const BACK_PRIORITY = ['ime', 'dialog', 'drawer', 'page', 'home-exit'] as const;
export type BackLayerKind = (typeof BACK_PRIORITY)[number];
export type BackLayer = { kind: BackLayerKind; close: () => void };

export function dispatchBack(layers: BackLayer[]): BackLayerKind {
  const target = BACK_PRIORITY.find((kind) => layers.some((layer) => layer.kind === kind));
  if (!target) return 'home-exit';
  for (let index = layers.length - 1; index >= 0; index -= 1) {
    if (layers[index].kind !== target) continue;
    layers[index].close();
    break;
  }
  return target;
}

export class BackLayerRegistry {
  private readonly entries = new Map<symbol, BackLayer>();

  constructor(private readonly restoreFocus: (id: string) => void) {}

  register(layer: BackLayer, restoreFocusId?: string): () => void {
    const key = Symbol(layer.kind);
    this.entries.set(key, layer);
    let active = true;
    return () => {
      if (!active) return;
      active = false;
      this.entries.delete(key);
      if (restoreFocusId) this.restoreFocus(restoreFocusId);
    };
  }

  hasAny(kinds: readonly BackLayerKind[]): boolean {
    return [...this.entries.values()].some(layer => kinds.includes(layer.kind));
  }

  dispatch(fallbacks: BackLayer[] = []): BackLayerKind {
    // Page-owned Back behavior (e.g. search results -> suggestions) must win
    // over the shell's generic "leave route" fallback at the same priority.
    return dispatchBack([...fallbacks, ...this.entries.values()]);
  }
}

export const BACK_LAYER_REGISTRY_KEY: InjectionKey<BackLayerRegistry> = Symbol('tv-back-layers');

export function useBackLayerRegistry(): BackLayerRegistry {
  const registry = inject(BACK_LAYER_REGISTRY_KEY);
  if (!registry) throw new Error('TV BackLayer registry is not available');
  return registry;
}
