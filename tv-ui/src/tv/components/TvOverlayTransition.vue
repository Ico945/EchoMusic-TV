<script setup lang="ts">
// A leaving layer may still be drawn, but must stop owning remote focus now.
function retire(element: Element) {
  element.setAttribute('inert', '');
  element.setAttribute('aria-hidden', 'true');
}
function activate(element: Element) {
  element.removeAttribute('inert');
  element.removeAttribute('aria-hidden');
}
</script>

<template>
  <Transition name="tv-overlay" :duration="{ enter: 180, leave: 140 }" @before-enter="activate" @before-leave="retire" @leave-cancelled="activate">
    <slot />
  </Transition>
</template>
