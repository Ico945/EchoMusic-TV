import { readdir, readFile } from 'node:fs/promises';
import { join, relative } from 'node:path';
import { NodeTypes, parse as parseDom, type ElementNode, type RootNode, type TemplateChildNode } from '@vue/compiler-dom';
import { parse as parseSfc } from '@vue/compiler-sfc';
import { describe, expect, it } from 'vitest';

const TV_ROOT = join(process.cwd(), 'src', 'tv');
const NATIVE_INTERACTIVE_TAGS = new Set(['button', 'input', 'textarea', 'select']);
const INTERACTIVE_ROLES = new Set(['button', 'link', 'menuitem', 'option', 'radio', 'switch', 'tab']);
const FORBIDDEN_GESTURE_PATTERN = /(?:@|v-on:)(?:touchstart|touchmove|touchend|touchcancel|pointerdown|pointermove|pointerup|wheel)\b|\b(?:Swipe|Pinch|GestureDetector|TouchEvent)\b/i;
const FORBIDDEN_SCRIPT_GESTURE_PATTERN = /addEventListener\s*\(\s*['"](?:touchstart|touchmove|touchend|touchcancel|pointerdown|pointermove|pointerup|wheel)['"]|\.on(?:touchstart|touchmove|touchend|touchcancel|pointerdown|pointermove|pointerup|wheel)\s*=|\b(?:TouchEvent|PointerEvent|GestureDetector|Hammer|useSwipe|usePointerSwipe)\b/i;

async function vueFiles(directory: string): Promise<string[]> {
  const entries = await readdir(directory, { withFileTypes: true });
  const nested = await Promise.all(entries.map(async (entry) => {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) return vueFiles(path);
    return entry.isFile() && entry.name.endsWith('.vue') ? [path] : [];
  }));
  return nested.flat();
}

async function productionTypeScriptFiles(directory: string): Promise<string[]> {
  const entries = await readdir(directory, { withFileTypes: true });
  const nested = await Promise.all(entries.map(async (entry) => {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) return productionTypeScriptFiles(path);
    return entry.isFile() && entry.name.endsWith('.ts') && !entry.name.endsWith('.spec.ts') ? [path] : [];
  }));
  return nested.flat();
}

function hasDirective(node: ElementNode, name: string, argument?: string): boolean {
  return node.props.some((property) => {
    if (property.type !== NodeTypes.DIRECTIVE || property.name !== name) return false;
    if (argument === undefined) return true;
    return property.arg?.type === NodeTypes.SIMPLE_EXPRESSION && property.arg.content === argument;
  });
}

function hasAttributeOrBinding(node: ElementNode, name: string): boolean {
  return node.props.some((property) => {
    if (property.type === NodeTypes.ATTRIBUTE) return property.name === name;
    return property.name === 'bind'
      && property.arg?.type === NodeTypes.SIMPLE_EXPRESSION
      && property.arg.content === name;
  });
}

function staticAttributeValue(node: ElementNode, name: string): string | undefined {
  const attribute = node.props.find((property) => property.type === NodeTypes.ATTRIBUTE && property.name === name);
  return attribute?.type === NodeTypes.ATTRIBUTE ? attribute.value?.content ?? '' : undefined;
}

function hasFocusableTabIndex(node: ElementNode): boolean {
  const staticValue = staticAttributeValue(node, 'tabindex');
  if (staticValue !== undefined) return staticValue.trim() !== '-1';
  return node.props.some((property) => property.type === NodeTypes.DIRECTIVE
    && property.name === 'bind'
    && property.arg?.type === NodeTypes.SIMPLE_EXPRESSION
    && property.arg.content === 'tabindex');
}

function isInteractive(node: ElementNode): boolean {
  if (NATIVE_INTERACTIVE_TAGS.has(node.tag)) return true;
  if (node.tag === 'a' && hasAttributeOrBinding(node, 'href')) return true;
  const role = staticAttributeValue(node, 'role')?.trim().toLowerCase();
  if (role && INTERACTIVE_ROLES.has(role)) return true;
  if (hasFocusableTabIndex(node)) return true;
  return hasDirective(node, 'on', 'click')
    || hasDirective(node, 'on', 'keydown')
    || hasDirective(node, 'on', 'keyup');
}

function collectInteractiveNodes(root: RootNode): ElementNode[] {
  const result: ElementNode[] = [];
  const visit = (node: RootNode | TemplateChildNode): void => {
    if (node.type === NodeTypes.ELEMENT) {
      if (isInteractive(node)) result.push(node);
      node.children.forEach(visit);
      return;
    }
    if (node.type === NodeTypes.ROOT || node.type === NodeTypes.IF_BRANCH || node.type === NodeTypes.FOR) {
      node.children.forEach(visit);
    }
  };
  visit(root);
  return result;
}

describe('TV interaction source contract', () => {
  it('keeps every interactive Vue element in the D-pad focus registry', async () => {
    const violations: string[] = [];
    for (const file of await vueFiles(TV_ROOT)) {
      const source = await readFile(file, 'utf8');
      const template = parseSfc(source, { filename: file }).descriptor.template?.content;
      if (!template) continue;
      const ast = parseDom(template);
      for (const node of collectInteractiveNodes(ast)) {
        const hasFocusId = hasAttributeOrBinding(node, 'data-focus-id');
        const hasTvFocus = hasDirective(node, 'tv-focus');
        if (!hasFocusId || !hasTvFocus) {
          violations.push(`${relative(TV_ROOT, file)}:${node.loc.start.line} <${node.tag}> focusId=${hasFocusId} tvFocus=${hasTvFocus}`);
        }
      }
    }

    expect(violations, violations.join('\n')).toEqual([]);
  });

  it('does not reintroduce mobile touch, swipe, pinch, pointer, or wheel handlers', async () => {
    const violations: string[] = [];
    for (const file of await vueFiles(TV_ROOT)) {
      const source = await readFile(file, 'utf8');
      const match = source.match(FORBIDDEN_GESTURE_PATTERN);
      if (match) violations.push(`${relative(TV_ROOT, file)}: ${match[0]}`);

      const descriptor = parseSfc(source, { filename: file }).descriptor;
      for (const script of [descriptor.script?.content, descriptor.scriptSetup?.content]) {
        const scriptMatch = script?.match(FORBIDDEN_SCRIPT_GESTURE_PATTERN);
        if (scriptMatch) violations.push(`${relative(TV_ROOT, file)} script: ${scriptMatch[0]}`);
      }
    }
    for (const file of await productionTypeScriptFiles(TV_ROOT)) {
      const source = await readFile(file, 'utf8');
      const match = source.match(FORBIDDEN_SCRIPT_GESTURE_PATTERN);
      if (match) violations.push(`${relative(TV_ROOT, file)}: ${match[0]}`);
    }
    expect(violations, violations.join('\n')).toEqual([]);
  });
});
