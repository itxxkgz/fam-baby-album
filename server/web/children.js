import { h, icon, note } from './ui.js';
import { count, safeAvatarUrl } from './utils.mjs';

export function childPortrait(child, size = '') {
  const url = safeAvatarUrl(child?.avatar_url), portrait = h('span', { class: `family-child-portrait ${size}`, 'aria-hidden': 'true' });
  const fallback = () => portrait.replaceChildren(icon('photo'));
  if (url) {
    const image = h('img', { src: url, alt: '', loading: 'lazy' });
    image.addEventListener('error', fallback, { once: true }); portrait.append(image);
  } else fallback();
  return portrait;
}

export function childChoices(children, selected = [], title = '选择宝宝') {
  const available = children.filter(child => !child.archived), inputs = [];
  const node = h('fieldset', { class: 'child-choices' }, h('legend', {}, title), available.map(child => {
    const input = h('input', { type: 'checkbox', value: child.id, checked: selected.includes(child.id), 'aria-label': child.name }); inputs.push(input);
    return h('label', { class: 'child-choice' }, input, childPortrait(child), h('span', {}, h('strong', {}, child.name), child.age ? h('small', {}, child.age) : null));
  }), available.length ? null : note('还没有可以添加照片的宝宝，请先添加或恢复一位宝宝。'));
  return { node, values: () => inputs.filter(input => input.checked).map(input => input.value), disable: value => inputs.forEach(input => { input.disabled = value; }) };
}
export function childSummary(child) { return `${count(child.counts?.photos)} 张照片 · ${count(child.counts?.videos)} 个视频`; }
