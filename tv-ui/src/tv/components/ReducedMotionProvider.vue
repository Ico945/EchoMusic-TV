<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, type InjectionKey, provide } from 'vue';
import { useTvSettingsStore } from '../stores/settings';

export const REDUCED_MOTION_KEY: InjectionKey<{ reducedMotion: () => boolean }> = Symbol('tv-reduced-motion');

const settings = useTvSettingsStore();

provide(REDUCED_MOTION_KEY, {
  reducedMotion: () => settings.settings.reducedMotion,
});

const className = 'tv-reduced-motion';

onMounted(() => {
  if (settings.settings.reducedMotion) document.documentElement.classList.add(className);
});

onBeforeUnmount(() => {
  document.documentElement.classList.remove(className);
});

const reducedMotion = computed(() => settings.settings.reducedMotion);
</script>

<template>
  <slot :reduced-motion="reducedMotion" />
</template>
