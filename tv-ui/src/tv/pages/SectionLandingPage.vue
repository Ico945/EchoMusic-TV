<script setup lang="ts">
defineProps<{ title: string; page: string }>();
</script>

<template>
  <section class="section-page" :data-page="page">
    <span>ECHOMUSIC TV</span><h1>{{ title }}</h1>
    <p>这个栏目已接入 TV 导航，内容能力将在后续阶段连接 EchoMusic 数据层。</p>
    <div class="placeholder-row" aria-hidden="true">
      <div v-for="index in 5" :key="index"><i /></div>
    </div>
  </section>
</template>

<style scoped>
.section-page { padding:30px 18px; }
.section-page>span { color:#72b6ff; font-size:14px; letter-spacing:2px; font-weight:700; }
h1 { margin:8px 0 10px; font-size:58px; letter-spacing:-2px; } p { color:var(--tv-text-secondary); font-size:20px; }
.placeholder-row { display:grid; grid-template-columns:repeat(5,1fr); gap:20px; margin-top:48px; }
.placeholder-row>div { height:240px; padding:0; border:1px solid var(--tv-border); border-radius:var(--tv-radius-card); background:var(--tv-glass); display:grid; place-items:center; }
.placeholder-row i { display:block; width:64px; height:8px; margin:0 auto; border-radius:8px; background:rgba(255,255,255,.22); }
</style>
