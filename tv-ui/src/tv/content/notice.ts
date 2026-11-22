import { onBeforeUnmount, ref } from 'vue';

export function useTransientNotice() {
  const notice = ref('');
  let timer: ReturnType<typeof setTimeout> | undefined;

  function show(text: string, ms = 2600): void {
    notice.value = text;
    if (timer) clearTimeout(timer);
    timer = setTimeout(() => {
      notice.value = '';
    }, ms);
  }

  onBeforeUnmount(() => {
    if (timer) clearTimeout(timer);
  });

  return { notice, show };
}
