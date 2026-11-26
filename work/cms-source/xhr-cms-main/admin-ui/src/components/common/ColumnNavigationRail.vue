<template>
  <div
    ref="rail"
    class="column-navigation-rail"
    :class="{ 'is-resizable': canResize, 'is-resizing': dragging }"
    @pointerdown="startResize"
    @pointermove="resize"
    @pointerup="stopResize"
    @pointercancel="stopResize"
    @lostpointercapture="stopResize"
    @click.capture="guardClick"
  >
    <div
      v-if="canResize"
      class="column-navigation-rail__separator"
      role="separator"
      tabindex="0"
      aria-label="左右拖动调整栏目导航宽度，或使用左右方向键"
      aria-orientation="vertical"
      :aria-controls="controls"
      :aria-valuemin="minimum"
      :aria-valuemax="maximum"
      :aria-valuenow="actualWidth"
      title="左右拖动调整栏目导航宽度"
      @keydown="resizeWithKeyboard"
    />
    <el-button
      class="admin-tree-toggle"
      :aria-expanded="!collapsed"
      :aria-controls="controls"
      :aria-label="toggleLabel"
      :title="canResize ? `${toggleLabel}；左右拖动调整宽度` : toggleLabel"
      :data-testid="`${controls}-toggle`"
      @click="emit('update:collapsed', !collapsed)"
    >
      <span class="admin-tree-toggle__content">
        <el-icon><ArrowRight v-if="collapsed" /><ArrowLeft v-else /></el-icon>
        <span class="admin-tree-toggle__label">{{ toggleLabel }}</span>
      </span>
    </el-button>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { ArrowLeft, ArrowRight } from '@element-plus/icons-vue';

const props = defineProps<{
  collapsed: boolean;
  width?: number;
  controls: string;
  breakpoint: number;
}>();
const emit = defineEmits<{
  'update:collapsed': [value: boolean];
  'update:width': [value: number];
}>();
const rail = ref<HTMLElement>();
const wide = ref(false);
const minimum = ref(240);
const maximum = ref(600);
const actualWidth = ref(300);
const dragging = ref(false);
const canResize = computed(() => wide.value && !props.collapsed);
const toggleLabel = computed(() => props.collapsed ? '点击展开栏目导航' : '点击收起栏目导航');
let observer: ResizeObserver | undefined;
let media: MediaQueryList;
let pointer: { id: number; x: number; width: number; target: HTMLElement } | undefined;
let suppressClick = false;
let previousCursor = '';
let previousUserSelect = '';

function updateBounds() {
  const layout = rail.value?.parentElement;
  if (!layout || !canResize.value) return;
  const style = getComputedStyle(layout);
  minimum.value = parseFloat(style.getPropertyValue('--admin-tree-min-width')) || 240;
  const railWidth = rail.value!.getBoundingClientRect().width;
  // 给右侧内容保留空间，并避免导航占据过多宽度。
  maximum.value = Math.max(minimum.value, Math.min(600, layout.clientWidth - railWidth - 360));
  const panel = document.getElementById(props.controls);
  actualWidth.value = Math.round(panel?.getBoundingClientRect().width || minimum.value);
  setWidth(props.width ?? actualWidth.value);
}

function setWidth(value: number) {
  actualWidth.value = Math.round(Math.max(minimum.value, Math.min(maximum.value, value)));
  emit('update:width', actualWidth.value);
}

function startResize(event: PointerEvent) {
  suppressClick = false;
  if (!canResize.value || event.button !== 0 || !event.isPrimary || pointer) return;
  updateBounds();
  const target = event.target as HTMLElement;
  pointer = { id: event.pointerId, x: event.clientX, width: actualWidth.value, target };
  target.setPointerCapture(event.pointerId);
}

function resize(event: PointerEvent) {
  if (!pointer || event.pointerId !== pointer.id) return;
  const delta = event.clientX - pointer.x;
  if (!dragging.value && Math.abs(delta) < 4) return;
  if (!dragging.value) {
    previousCursor = document.body.style.cursor;
    previousUserSelect = document.body.style.userSelect;
    document.body.style.cursor = 'col-resize';
    document.body.style.userSelect = 'none';
    dragging.value = true;
    suppressClick = true;
  }
  setWidth(pointer.width + delta);
}

function stopResize() {
  const active = pointer;
  pointer = undefined;
  if (active?.target.hasPointerCapture(active.id)) active.target.releasePointerCapture(active.id);
  if (dragging.value) {
    document.body.style.cursor = previousCursor;
    document.body.style.userSelect = previousUserSelect;
    dragging.value = false;
  }
}

function guardClick(event: MouseEvent) {
  if (suppressClick && event.detail !== 0) {
    event.preventDefault();
    event.stopPropagation();
  }
  suppressClick = false;
}

function resizeWithKeyboard(event: KeyboardEvent) {
  const steps: Record<string, number> = {
    ArrowLeft: actualWidth.value - 10,
    ArrowRight: actualWidth.value + 10,
    Home: minimum.value,
    End: maximum.value,
  };
  if (!(event.key in steps)) return;
  event.preventDefault();
  setWidth(steps[event.key]);
}

function updateMedia() {
  wide.value = media.matches;
  stopResize();
  void nextTick(updateBounds);
}

watch(() => props.collapsed, () => {
  stopResize();
  void nextTick(updateBounds);
});
onMounted(() => {
  media = window.matchMedia(`(min-width: ${props.breakpoint + 1}px)`);
  updateMedia();
  media.addEventListener('change', updateMedia);
  observer = new ResizeObserver(updateBounds);
  if (rail.value?.parentElement) observer.observe(rail.value.parentElement);
  window.addEventListener('blur', stopResize);
});
onBeforeUnmount(() => {
  stopResize();
  observer?.disconnect();
  media?.removeEventListener('change', updateMedia);
  window.removeEventListener('blur', stopResize);
});
</script>

<style scoped>
.column-navigation-rail {
  position: relative;
  display: grid;
  min-height: 0;
  align-self: stretch;
}
.column-navigation-rail.is-resizable {
  touch-action: none;
  user-select: none;
  cursor: col-resize;
}
.column-navigation-rail__separator {
  position: absolute;
  inset: 0;
  border-radius: var(--admin-radius-md);
}
.column-navigation-rail__separator::before {
  content: '';
  position: absolute;
  top: 0;
  bottom: 0;
  left: calc(50% - 1px);
  width: 2px;
  background: var(--admin-border-subtle);
}
.column-navigation-rail:hover .column-navigation-rail__separator::before,
.column-navigation-rail.is-resizing .column-navigation-rail__separator::before,
.column-navigation-rail__separator:focus-visible::before {
  background: var(--el-color-primary);
}
.column-navigation-rail__separator:focus-visible {
  outline: 2px solid var(--el-color-primary);
  outline-offset: -2px;
}
.column-navigation-rail .admin-tree-toggle {
  position: relative;
}
.column-navigation-rail.is-resizable .admin-tree-toggle {
  cursor: col-resize;
}
</style>
