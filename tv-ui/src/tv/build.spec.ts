import { access, readFile } from 'node:fs/promises';
import { join } from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
import { buildTvArtifact } from './test/buildTvArtifact';

let artifact: Awaited<ReturnType<typeof buildTvArtifact>> | undefined;
let style: HTMLStyleElement | undefined;

afterEach(async () => {
  style?.remove();
  style = undefined;
  document.body.replaceChildren();
  const outputDir = artifact?.outputDir;
  await artifact?.cleanup();
  if (outputDir) {
    await expect(access(outputDir)).rejects.toThrow();
  }
  artifact = undefined;
});

describe('TV production build', () => {
  it('emits a runnable app entry and styled TV canvas into an isolated output directory', async () => {
    artifact = await buildTvArtifact();

    const html = await readFile(join(artifact.outputDir, 'tv.html'), 'utf8');
    const htmlDocument = new DOMParser().parseFromString(html, 'text/html');
    const moduleEntry = htmlDocument.querySelector<HTMLScriptElement>('script[type="module"][src]');
    const stylesheet = htmlDocument.querySelector<HTMLLinkElement>('link[rel="stylesheet"][href]');

    expect(htmlDocument.querySelector('#app')).not.toBeNull();
    expect(htmlDocument.documentElement.lang).toBe('zh-CN');
    expect(moduleEntry?.getAttribute('src')).toMatch(/^\/assets\/tv-ui\/assets\/.+\.js$/);
    expect(stylesheet?.getAttribute('href')).toMatch(/^\/assets\/tv-ui\/assets\/.+\.css$/);

    const moduleAsset = new URL(moduleEntry!.src, 'https://echomusic.test').pathname.replace('/assets/tv-ui/', '');
    const stylesheetAsset = new URL(stylesheet!.href, 'https://echomusic.test').pathname.replace('/assets/tv-ui/', '');
    const [moduleCode, css] = await Promise.all([
      readFile(join(artifact.outputDir, moduleAsset)),
      readFile(join(artifact.outputDir, stylesheetAsset), 'utf8'),
    ]);

    expect(moduleCode.byteLength).toBeGreaterThan(0);

    document.body.innerHTML = '<main class="tv-canvas"><section class="tv-safe-area"></section></main>';
    style = document.createElement('style');
    style.textContent = css;
    document.head.append(style);

    const canvas = document.querySelector<HTMLElement>('.tv-canvas')!;
    const safeArea = document.querySelector<HTMLElement>('.tv-safe-area')!;

    expect(getComputedStyle(canvas).width).toBe('1920px');
    expect(getComputedStyle(canvas).height).toBe('1080px');
    expect(getComputedStyle(canvas).gridTemplateColumns).toBe('5% 90% 5%');
    expect(getComputedStyle(canvas).gridTemplateRows).toBe('5% 90% 5%');
    expect(getComputedStyle(safeArea).gridArea).toBe('2/2');
    expect(getComputedStyle(canvas).getPropertyValue('--tv-canvas-background')).toBe('#141315');
    expect(getComputedStyle(canvas).getPropertyValue('--tv-content-bottom-safe')).toBe('152px');
    expect(getComputedStyle(canvas).getPropertyValue('--tv-focus-scale')).toBe('1.06');
    expect(getComputedStyle(canvas).getPropertyValue('--tv-card-title-size')).toBe('24px');
    expect(getComputedStyle(canvas).getPropertyValue('--tv-card-meta-size')).toBe('20px');
    expect(getComputedStyle(canvas).backgroundColor).toBe('var(--tv-canvas-background)');
    expect(css).toContain('.tv-no-blur');
    expect(css).toContain('.tv-no-blur [data-focus-id]{transition:transform .11s ease-out,opacity .11s ease-out}');
    expect(css).toMatch(/\.tv-canvas\.tv-rapid-input \[data-focus-id\]\{transition:transform (?:60ms|\.06s) ease-out,opacity (?:60ms|\.06s) ease-out!important\}/);
    expect(css).toContain('@keyframes tv-page-enter');
    expect(css).toContain(
      '.tv-no-blur [data-focus-id]:focus-visible,.tv-no-blur [data-focus-id]:focus{box-shadow:0 0 0 4px #fffffff5}',
    );
  }, 15_000);
});
