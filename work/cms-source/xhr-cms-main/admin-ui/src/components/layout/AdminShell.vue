<template>
  <div
      class="admin-shell"
      :class="{
      'is-nav-collapsed': showCollapsedSidebar,
      'is-mobile-nav': isMobileViewport
    }"
  >
    <a href="#cms-page-content" class="admin-skip-link" @click.prevent="pageContent?.focus()">跳到当前页面内容</a>
    <aside
        class="admin-sidebar"
        :class="{
        'is-collapsed': showCollapsedSidebar,
        'is-mobile-open': mobileNavOpen,
        [sidebarBackgroundClass]: true
      }"
        aria-label="系统导航"
    >
      <canvas
          v-if="branding.sidebarBackgroundStyle === 'binary'"
          ref="sidebarBinaryCanvas"
          class="admin-sidebar-binary-canvas"
          aria-hidden="true"
      ></canvas>
      <canvas
          v-if="branding.sidebarBackgroundStyle === 'stars'"
          ref="sidebarStarsCanvas"
          class="admin-sidebar-star-canvas"
          aria-hidden="true"
      ></canvas>
      <div class="admin-sidebar-header">
        <RouterLink
            class="admin-sidebar-brand has-logo" to="/admin/v2"
            :aria-label="branding.systemName" :title="branding.systemName"
        >
          <img class="admin-sidebar-brand-logo"
               :src="branding.systemLogoUrl || DEFAULT_LOGO_URL" @error="handleLogoError"/>
          <span class="admin-sidebar-brand-copy">
            <span class="admin-sidebar-kicker">{{ branding.systemName }}</span>
          </span>
        </RouterLink>
        <button
            type="button"
            class="admin-sidebar-toggle"
            :aria-label="showCollapsedSidebar ? '展开导航' : '收起导航'"
            :aria-expanded="showCollapsedSidebar ? 'false' : 'true'"
            @click="toggleSidebar"
        >
          <el-icon>
            <component :is="showCollapsedSidebar ? Expand : Fold"/>
          </el-icon>
        </button>
      </div>

      <div class="admin-sidebar-scroll">
        <section
            v-for="group in groupedMenus"
            :key="group.key"
            class="admin-nav-group"
            :class="{ 'is-active': isGroupActive(group), 'is-expanded': isGroupExpanded(group.key) }"
        >
          <button
              type="button"
              class="admin-nav-group-header"
              :title="showCollapsedSidebar ? (group.category ?? group.label) : undefined"
              :aria-expanded="isGroupExpanded(group.key) || showCollapsedSidebar"
              @click="toggleGroup(group.key)"
          >
            <span class="admin-nav-group-icon">
              <el-icon>
                <component :is="resolveIcon(group.icon)"/>
              </el-icon>
            </span>
            <span class="admin-nav-group-label">{{ group.category ?? group.label }}</span>
            <span v-if="group.badge" class="admin-nav-group-badge">{{ group.badge }}</span>
            <el-icon v-if="!showCollapsedSidebar" class="admin-nav-group-arrow">
              <component :is="isGroupExpanded(group.key) ? ArrowDown : ArrowRight"/>
            </el-icon>
          </button>

          <nav v-show="isGroupExpanded(group.key) || showCollapsedSidebar" class="admin-nav-list">
            <template v-for="item in navEntries(group)" :key="item.key">
              <div class="admin-nav-entry" :class="{ 'has-children': hasCollapsibleChildren(item) }" :style="navEntryStyle(item)">
                <RouterLink
                    v-if="item.path"
                    class="admin-nav-link"
                    :class="{ 'admin-nav-link--child': item.depth > 0 }"
                    :to="item.path"
                    :title="showCollapsedSidebar ? item.label : undefined"
                    @click="closeMobileSidebar"
                >
                  <span class="admin-nav-link-icon">
                    <el-icon>
                      <component :is="resolveIcon(item.icon)"/>
                    </el-icon>
                  </span>
                  <span class="admin-nav-link-text">{{ item.label }}</span>
                  <span v-if="item.badge" class="admin-nav-link-badge">{{ item.badge }}</span>
                </RouterLink>
                <button
                    v-else
                    type="button"
                    class="admin-nav-link admin-nav-link--disabled"
                    :class="{ 'admin-nav-link--child': item.depth > 0 }"
                    :title="item.tooltip ?? item.label"
                    disabled
                >
                  <span class="admin-nav-link-icon">
                    <el-icon>
                      <component :is="resolveIcon(item.icon)"/>
                    </el-icon>
                  </span>
                  <span class="admin-nav-link-text">{{ item.label }}</span>
                  <span v-if="item.badge" class="admin-nav-link-badge">{{ item.badge }}</span>
                </button>
                <button
                    v-if="hasCollapsibleChildren(item)"
                    type="button"
                    class="admin-nav-toggle"
                    :aria-label="isNavEntryExpanded(item) ? `收起${item.label}` : `展开${item.label}`"
                    :aria-expanded="isNavEntryExpanded(item) ? 'true' : 'false'"
                    :data-testid="`admin-nav-toggle-${item.key}`"
                    @click="toggleNavEntry(item.key)"
                >
                  <el-icon>
                    <component :is="isNavEntryExpanded(item) ? ArrowDown : ArrowRight"/>
                  </el-icon>
                </button>
              </div>
            </template>
          </nav>
        </section>
      </div>
    </aside>

    <main class="admin-main">
      <header class="admin-topbar">
        <div class="admin-topbar-leading">
          <button
              type="button"
              class="admin-mobile-nav-toggle"
              :aria-label="mobileNavOpen ? '关闭导航抽屉' : '打开导航抽屉'"
              :aria-expanded="mobileNavOpen ? 'true' : 'false'"
              @click="toggleMobileSidebar"
          >
            <el-icon>
              <Expand/>
            </el-icon>
          </button>
          <div class="admin-topbar-copy">
            <p class="admin-topbar-kicker">
              <span class="admin-topbar-system-name">{{ branding.systemName }}</span>
              <span>{{ shellTitle }}</span>
            </p>
            <h2 class="admin-topbar-title">{{ routeTitle }}</h2>
          </div>
        </div>

        <div class="admin-topbar-actions">
          <div v-if="showSiteSwitcher" class="admin-site-switcher">
            <span class="admin-site-switcher-label">当前站点</span>
            <el-select
                :model-value="siteContext.currentSite?.id"
                :loading="siteContext.switching"
                aria-label="当前站点" placeholder="选择站点"
                size="default"
                style="width: 220px"
                @change="siteContext.switchSite"
            >
              <el-option
                  v-for="site in siteContext.sites"
                  :key="site.id"
                  :label="site.name"
                  :value="site.id"
              />
            </el-select>
          </div>

          <el-dropdown trigger="click" @command="handleUserCommand">
            <button type="button" class="admin-user-button" :aria-label="`用户菜单：${currentAdminName}`">
              <span class="admin-user-avatar">{{ currentAdminInitial }}</span>
              <span class="admin-user-copy">
                <span class="admin-user-name">{{ currentAdminName }}</span>
                <span v-if="currentAdminRoleLabel" class="admin-user-role">{{ currentAdminRoleLabel }}</span>
              </span>
              <el-icon>
                <ArrowDown/>
              </el-icon>
            </button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="profile">个人资料</el-dropdown-item>
                <el-dropdown-item command="password">修改密码</el-dropdown-item>
                <el-dropdown-item divided command="logout">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </header>

      <section class="admin-content">
        <el-alert
            v-if="siteContext.message"
            :title="siteContext.message"
            type="error"
            show-icon
            closable
            @close="siteContext.clearMessage"
        />
        <div id="cms-page-content" ref="pageContent" tabindex="-1" class="admin-content-body">
          <RouterView/>
        </div>
      </section>
    </main>

    <ProfileSecurityPanel
        v-model:profile-visible="profileVisible"
        v-model:password-visible="passwordVisible"
        :profile="profileForm"
        :password="passwordForm"
        :password-error="passwordError"
        :saving="profileSaving"
        @save-profile="handleSaveProfile"
        @save-password="handleSavePassword"
    />

    <div v-if="siteContext.loading" class="admin-loading-mask">
      <el-card shadow="never">加载中...</el-card>
    </div>

    <button
        v-if="isMobileViewport && mobileNavOpen"
        type="button"
        class="admin-sidebar-backdrop"
        aria-label="关闭导航抽屉"
        @click="closeMobileSidebar"
    />
  </div>
</template>

<script setup lang="ts">
import {
  ArrowDown,
  ArrowRight,
  Collection,
  Compass,
  Connection,
  Document,
  DocumentCopy,
  Expand,
  Files,
  Fold,
  Folder,
  Goods,
  GoodsFilled,
  Grid,
  HomeFilled,
  House,
  Lock,
  MagicStick,
  Management,
  Monitor,
  Notebook,
  OfficeBuilding,
  Operation,
  Promotion,
  Setting,
  Tickets,
  UserFilled
} from '@element-plus/icons-vue';
import {ElMessage} from 'element-plus';
import {type Component, computed, nextTick, onBeforeUnmount, onMounted, ref, watch} from 'vue';
import {useRoute, useRouter} from 'vue-router';
import {http, isAuthenticationError} from '@/api/http';
import {useBrandingStore} from '@/stores/branding';
import {type SystemMenuItem, useSiteContextStore} from '@/stores/siteContext';
import ProfileSecurityPanel, {type PasswordForm, type ProfileForm} from '@/views/system/ProfileSecurityPanel.vue';
import {useSystemUsersWorkspace} from '@/views/system/useSystemUsersWorkspace';

interface NavEntry extends SystemMenuItem {
  depth: number;
}

const pageContent = ref<HTMLElement | null>(null);
const route = useRoute();
const router = useRouter();
const siteContext = useSiteContextStore();
const brandingStore = useBrandingStore();
const branding = brandingStore.settings;
const profileVisible = ref(false);
const passwordVisible = ref(false);
const {
  profileForm,
  passwordForm,
  passwordError,
  saving: profileSaving,
  loadCurrentProfile,
  saveProfile,
  savePassword
} = useSystemUsersWorkspace(siteContext);
const sidebarCollapsed = ref(false);
const isMobileViewport = ref(false);
const mobileNavOpen = ref(false);
const expandedNavKeys = ref<Set<string>>(new Set());
const expandedGroups = ref<Set<string>>(new Set());
const sidebarBinaryCanvas = ref<HTMLCanvasElement | null>(null);
const sidebarStarsCanvas = ref<HTMLCanvasElement | null>(null);
let mobileMediaQuery: MediaQueryList | null = null;
let sidebarBinaryTimer = 0;
let sidebarBinaryResizeObserver: ResizeObserver | null = null;
let sidebarStarsAnimationFrame = 0;
let sidebarStarsResizeObserver: ResizeObserver | null = null;
const handleMobileViewportChange = (event: MediaQueryListEvent) => {
  syncViewportState(event.matches);
};

const iconMap: Record<string, Component> = {
  Collection,
  Compass,
  Connection,
  Document,
  DocumentCopy,
  Files,
  Grid,
  HomeFilled,
  House,
  Lock,
  Monitor,
  Notebook,
  OfficeBuilding,
  Operation,
  Promotion,
  Setting,
  UserFilled,
  Goods,
  GoodsFilled,
  Folder,
  MagicStick,
  Management,
  Tickets
};

/** 后端返回的动态菜单只按 menu_setting 过滤，可能包含当前用户路由无权访问的条目；
 *  这里再按路由 meta 与能力位对齐一次，避免「菜单可见但点开被守卫拦截」。 */
function isMenuPathPermitted(path: string): boolean {
  if (!path) {
    return true;
  }

  const meta = router.resolve(path).meta;

  if (meta.requiresPermissionCenterRead) {
    return siteContext.canAccessPermissionCenter;
  }

  if (meta.requiresSystemSettingsManage) {
    return siteContext.canManageSystemSettings;
  }

  if (meta.requiresSystemAdmin) {
    return siteContext.canManageSystem || siteContext.canAccessSystemMenuPath(path);
  }

  return true;
}

const permittedSystemMenus = computed<SystemMenuItem[]>(() => {
  const filterItems = (items: SystemMenuItem[]): SystemMenuItem[] =>
      items.reduce<SystemMenuItem[]>((acc, item) => {
        if (item.children?.length) {
          const children = filterItems(item.children);
          if (children.length) {
            acc.push({...item, children});
          }
          return acc;
        }

        if (isMenuPathPermitted(item.path)) {
          acc.push({...item});
        }

        return acc;
      }, []);

  return filterItems(siteContext.systemMenus);
});

const groupedMenus = computed(() => permittedSystemMenus.value.filter((menu) => menu.children?.length));
const showCollapsedSidebar = computed(() => sidebarCollapsed.value && !isMobileViewport.value);

// 只在站点内容相关页面显示站点切换器
const showSiteSwitcher = computed(() => {
  const path = route.path;
  // 站点内容及其子菜单
  if (path.startsWith('/admin/v2/cms/')) {
    return true;
  }
  // 内容管理
  if (path === '/admin/v2/cms/content-management') {
    return true;
  }
  // 模板管理
  if (path === '/admin/v2/cms/template-management') {
    return true;
  }
  // 管理员组管理（栏目权限树按当前站点展示）
  if (path === '/admin/v2/system/admin-groups') {
    return true;
  }
  // 站点日志
  if (path === '/admin/v2/site-logs') {
    return true;
  }
  return false;
});
const sidebarBackgroundClass = computed(() => {
  const style = ['binary', 'stars', 'solid', 'none'].includes(branding.sidebarBackgroundStyle)
      ? branding.sidebarBackgroundStyle
      : 'binary';
  return `sidebar-bg-${style}`;
});

const menuEntries = computed(() => groupedMenus.value.flatMap((group) => flattenMenuItems(group.children ?? [])));

const activeGroupMenu = computed(() => {
  const matchedGroup = permittedSystemMenus.value.find((menu) => isGroupActive(menu));
  if (matchedGroup) {
    return matchedGroup;
  }

  const matchedEntry = menuEntries.value.find((menu) => isPathActive(menu.path));
  if (!matchedEntry) {
    return permittedSystemMenus.value[0] ?? null;
  }

  return groupedMenus.value.find((menu) => containsMenuKey(menu.children ?? [], matchedEntry.key)) ?? permittedSystemMenus.value[0] ?? null;
});

const routeTitle = computed(() => {
  if (route.meta.title) {
    return String(route.meta.title);
  }

  const matchedEntry = menuEntries.value.find((menu) => isPathActive(menu.path));
  return matchedEntry?.label ?? '工作台';
});

const shellTitle = computed(() => {
  if (route.path === '/admin/v2') {
    return routeTitle.value;
  }

  return activeGroupMenu.value?.category ?? '工作台';
});

/** 右上角展示登录账号（t_users.username），不展示姓名或所属组名 */
const currentAdminName = computed(() => siteContext.currentAdmin?.username?.trim() || '未登录');

const currentAdminInitial = computed(() => {
  const name = currentAdminName.value;
  return name ? name.slice(0, 1).toUpperCase() : 'A';
});

/** 用户名下方标识管理范围：平台管理（超管/平台组，跨站点）或站点管理（普通组） */
const currentAdminRoleLabel = computed(() => {
  if (!siteContext.currentAdmin) {
    return '';
  }
  return siteContext.isPlatformAdmin ? '平台管理' : '站点管理';
});

function toggleSidebar() {
  if (isMobileViewport.value) {
    mobileNavOpen.value = !mobileNavOpen.value;
    return;
  }
  sidebarCollapsed.value = !sidebarCollapsed.value;
}

function toggleMobileSidebar() {
  mobileNavOpen.value = !mobileNavOpen.value;
}

function closeMobileSidebar() {
  mobileNavOpen.value = false;
}

function resolveIcon(icon?: string) {
  return (icon && iconMap[icon]) || Grid;
}

/** 未配置或加载失败时，使用管理端自带的 Logo。 */
const DEFAULT_LOGO_URL = `${import.meta.env.BASE_URL}media/system-logo.svg`;

function handleLogoError(event: Event) {
  const target = event.target as HTMLImageElement;
  if (!target || target.dataset.fallbackApplied === 'true') {
    return;
  }
  target.dataset.fallbackApplied = 'true';
  target.src = DEFAULT_LOGO_URL;
}

function navEntries(group: SystemMenuItem) {
  if (group.key === 'cms') {
    return visibleContentMenuItems(group.children ?? []);
  }
  return flattenMenuItems(group.children ?? []);
}

function flattenMenuItems(items: SystemMenuItem[], depth = 0): NavEntry[] {
  return items.flatMap((item) => [
    {...item, depth},
    ...flattenMenuItems(item.children ?? [], depth + 1)
  ]);
}

function visibleContentMenuItems(items: SystemMenuItem[], depth = 0): NavEntry[] {
  return items.flatMap((item) => {
    const entry = {...item, depth};
    if (!hasCollapsibleChildren(entry) || !isNavEntryExpanded(entry)) {
      return [entry];
    }
    return [entry, ...visibleContentMenuItems(item.children ?? [], depth + 1)];
  });
}

function hasCollapsibleChildren(item: SystemMenuItem) {
  return Boolean(item.children?.length);
}

function isNavEntryExpanded(item: SystemMenuItem) {
  return expandedNavKeys.value.has(item.key);
}

function toggleNavEntry(key: string) {
  const next = new Set(expandedNavKeys.value);
  if (next.has(key)) {
    next.delete(key);
  } else {
    next.add(key);
  }
  expandedNavKeys.value = next;
}

function isGroupExpanded(key: string) {
  return expandedGroups.value.has(key);
}

function toggleGroup(key: string) {
  if (showCollapsedSidebar.value) {
    return;
  }
  expandedGroups.value = expandedGroups.value.has(key) ? new Set() : new Set([key]);
}

function navEntryStyle(item: NavEntry) {
  if (showCollapsedSidebar.value) {
    return {};
  }

  return {paddingLeft: `${(item.depth + 1) * 20}px`};
}

function isPathActive(path: string) {
  if (!path) {
    return false;
  }

  if (path === '/admin/v2') {
    return route.path === path;
  }

  return route.path === path || route.path.startsWith(`${path}/`);
}

function isGroupActive(menu: SystemMenuItem) {
  if (isPathActive(menu.path)) {
    return true;
  }

  return menu.children?.some((child) => isMenuActive(child)) ?? false;
}

function isMenuActive(menu: SystemMenuItem): boolean {
  return isPathActive(menu.path) || (menu.children?.some((child) => isMenuActive(child)) ?? false);
}

function containsMenuKey(items: SystemMenuItem[], key: string): boolean {
  return items.some((item) => item.key === key || containsMenuKey(item.children ?? [], key));
}

function syncExpandedMenus() {
  const activeGroup = groupedMenus.value.find((group) => isGroupActive(group));
  expandedGroups.value = activeGroup ? new Set([activeGroup.key]) : new Set();

  const activeParentKeys = groupedMenus.value.flatMap((group) => activeParentKeysForItems(group.children ?? []));
  expandedNavKeys.value = new Set(activeParentKeys);
}

function activeParentKeysForItems(items: SystemMenuItem[], ancestors: string[] = []): string[] {
  for (const item of items) {
    if (isPathActive(item.path)) {
      return ancestors;
    }
    const childKeys = activeParentKeysForItems(item.children ?? [], [...ancestors, item.key]);
    if (childKeys.length) {
      return childKeys;
    }
  }
  return [];
}

async function openProfilePanel() {
  profileVisible.value = true;
  try {
    await loadCurrentProfile();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '个人资料加载失败');
  }
}

async function handleSaveProfile(next: ProfileForm) {
  if (await saveProfile(next)) {
    profileVisible.value = false;
  }
}

async function handleSavePassword(next: PasswordForm) {
  if (await savePassword(next)) {
    passwordVisible.value = false;
  }
}

async function handleUserCommand(command: string | number | object) {
  if (command === 'profile') {
    await openProfilePanel();
    return;
  }
  if (command === 'password') {
    passwordVisible.value = true;
    return;
  }
  if (command !== 'logout') {
    return;
  }

  try {
    await http.post<void>('/api/admin/v2/auth/logout');
    siteContext.clearSessionState();
    await router.push('/admin/login');
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '退出登录失败');
  }
}

watch(
    () => [route.name, route.params.menuId] as const,
    async ([routeName, menuId]) => {
      closeMobileSidebar();
      // 不再自动重定向，内容总览页面显示仪表盘
    },
    {immediate: true}
);

watch(
    () => [route.fullPath, permittedSystemMenus.value] as const,
    () => {
      syncExpandedMenus();
    },
    {immediate: true}
);

watch(
    () => branding.sidebarBackgroundStyle,
    () => {
      void syncSidebarBackgroundAnimations();
    }
);

function syncViewportState(matches: boolean) {
  isMobileViewport.value = matches;
  if (!matches) {
    mobileNavOpen.value = false;
    return;
  }
  sidebarCollapsed.value = false;
}

async function syncSidebarBackgroundAnimations() {
  stopSidebarBinaryAnimation();
  stopSidebarStarsAnimation();
  if (branding.sidebarBackgroundStyle === 'binary') {
    await nextTick();
    startSidebarBinaryAnimation();
    return;
  }
  if (branding.sidebarBackgroundStyle !== 'stars') {
    return;
  }

  await nextTick();
  startSidebarStarsAnimation();
}

function startSidebarBinaryAnimation() {
  if (typeof window === 'undefined') {
    return;
  }
  if (import.meta.env.MODE === 'test') {
    return;
  }

  const canvas = sidebarBinaryCanvas.value;
  const host = canvas?.parentElement;
  if (!canvas || !host || typeof canvas.getContext !== 'function') {
    return;
  }

  let context: CanvasRenderingContext2D | null = null;
  try {
    context = canvas.getContext('2d');
  } catch {
    return;
  }
  if (!context) {
    return;
  }

  const canvasElement = canvas;
  const hostElement = host;
  const ctx = context;
  let width = 0;
  let height = 0;
  let digits: Array<{
    x: number;
    y: number;
    size: number;
    alpha: number;
    value: 0 | 1;
    phase: number;
  }> = [];
  const prefersReducedMotion = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false;

  function createDigits() {
    const columnGap = 24;
    const rowGap = 28;
    const columns = Math.max(3, Math.floor(width / columnGap));
    const rows = Math.max(8, Math.floor(height / rowGap));
    const nextDigits: typeof digits = [];
    for (let row = 0; row <= rows; row += 1) {
      for (let column = 0; column <= columns; column += 1) {
        if (Math.random() < 0.18) {
          continue;
        }
        nextDigits.push({
          x: Math.min(width - 10, Math.max(8, column * columnGap + 8 + (Math.random() - 0.5) * 9)),
          y: Math.min(height - 8, Math.max(14, row * rowGap + 12 + (Math.random() - 0.5) * 10)),
          size: Math.random() > 0.78 ? 13 : 11,
          alpha: Math.random() * 0.22 + 0.14,
          value: Math.random() > 0.5 ? 1 : 0,
          phase: Math.random()
        });
      }
    }
    digits = nextDigits;
  }

  function resize() {
    const rect = hostElement.getBoundingClientRect();
    width = Math.max(1, rect.width);
    height = Math.max(1, rect.height);
    const pixelRatio = Math.min(window.devicePixelRatio || 1, 2);
    canvasElement.width = Math.round(width * pixelRatio);
    canvasElement.height = Math.round(height * pixelRatio);
    canvasElement.style.width = `${width}px`;
    canvasElement.style.height = `${height}px`;
    ctx.setTransform(pixelRatio, 0, 0, pixelRatio, 0, 0);
    createDigits();
  }

  function draw() {
    ctx.clearRect(0, 0, width, height);
    ctx.font = '700 11px "SFMono-Regular", Consolas, "Liberation Mono", monospace';
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    for (const digit of digits) {
      if (Math.random() > 0.74 || prefersReducedMotion) {
        digit.value = Math.random() > 0.5 ? 1 : 0;
        digit.alpha = Math.random() * 0.25 + 0.12;
        digit.phase = Math.random();
      }
      const pulse = digit.phase > 0.82 ? 0.22 : 0;
      ctx.font = `700 ${digit.size}px "SFMono-Regular", Consolas, "Liberation Mono", monospace`;
      ctx.fillStyle = `rgb(226 232 240 / ${digit.alpha + pulse})`;
      ctx.fillText(String(digit.value), digit.x, digit.y);
    }
  }

  resize();
  draw();
  if (!prefersReducedMotion) {
    sidebarBinaryTimer = window.setInterval(draw, 420);
  }

  if (typeof ResizeObserver !== 'undefined') {
    sidebarBinaryResizeObserver = new ResizeObserver(() => {
      resize();
      draw();
    });
    sidebarBinaryResizeObserver.observe(hostElement);
  }
}

function startSidebarStarsAnimation() {
  if (typeof window === 'undefined') {
    return;
  }
  if (import.meta.env.MODE === 'test') {
    return;
  }

  const canvas = sidebarStarsCanvas.value;
  const host = canvas?.parentElement;
  if (!canvas || !host || typeof canvas.getContext !== 'function') {
    return;
  }

  let context: CanvasRenderingContext2D | null = null;
  try {
    context = canvas.getContext('2d');
  } catch {
    return;
  }
  if (!context) {
    return;
  }
  const canvasElement = canvas;
  const hostElement = host;
  const ctx = context;

  let width = 0;
  let height = 0;
  const particles: Array<{
    x: number;
    y: number;
    vx: number;
    vy: number;
    radius: number;
    alpha: number;
    hue: string;
  }> = [];
  const palette = ['96 165 250', '129 140 248', '167 139 250', '59 130 246', '255 255 255'];
  const maxDistance = 86;
  const prefersReducedMotion = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false;

  function createParticle() {
    return {
      x: Math.random() * width,
      y: Math.random() * height,
      vx: (Math.random() - 0.5) * 0.32,
      vy: (Math.random() - 0.5) * 0.32,
      radius: Math.random() * 1.4 + 0.55,
      alpha: Math.random() * 0.45 + 0.28,
      hue: palette[Math.floor(Math.random() * palette.length)]
    };
  }

  function resize() {
    const rect = hostElement.getBoundingClientRect();
    width = Math.max(1, rect.width);
    height = Math.max(1, rect.height);
    const pixelRatio = Math.min(window.devicePixelRatio || 1, 2);
    canvasElement.width = Math.round(width * pixelRatio);
    canvasElement.height = Math.round(height * pixelRatio);
    canvasElement.style.width = `${width}px`;
    canvasElement.style.height = `${height}px`;
    ctx.setTransform(pixelRatio, 0, 0, pixelRatio, 0, 0);

    const particleCount = Math.max(36, Math.min(58, Math.round((width * height) / 5200)));
    particles.length = 0;
    for (let index = 0; index < particleCount; index += 1) {
      particles.push(createParticle());
    }
  }

  function draw() {
    ctx.clearRect(0, 0, width, height);
    for (let index = 0; index < particles.length; index += 1) {
      for (let nextIndex = index + 1; nextIndex < particles.length; nextIndex += 1) {
        const current = particles[index];
        const next = particles[nextIndex];
        const deltaX = current.x - next.x;
        const deltaY = current.y - next.y;
        const distance = Math.sqrt(deltaX * deltaX + deltaY * deltaY);
        if (distance >= maxDistance) {
          continue;
        }

        ctx.beginPath();
        ctx.strokeStyle = `rgb(96 165 250 / ${(1 - distance / maxDistance) * 0.16})`;
        ctx.lineWidth = 0.5;
        ctx.moveTo(current.x, current.y);
        ctx.lineTo(next.x, next.y);
        ctx.stroke();
      }
    }

    for (const particle of particles) {
      ctx.beginPath();
      ctx.fillStyle = `rgb(${particle.hue} / ${particle.alpha})`;
      ctx.shadowBlur = 8;
      ctx.shadowColor = `rgb(${particle.hue} / 0.42)`;
      ctx.arc(particle.x, particle.y, particle.radius, 0, Math.PI * 2);
      ctx.fill();
    }
    ctx.shadowBlur = 0;
  }

  function update() {
    for (const particle of particles) {
      particle.x += particle.vx;
      particle.y += particle.vy;
      if (particle.x < 0 || particle.x > width) {
        particle.vx *= -1;
      }
      if (particle.y < 0 || particle.y > height) {
        particle.vy *= -1;
      }
      particle.x = Math.max(0, Math.min(width, particle.x));
      particle.y = Math.max(0, Math.min(height, particle.y));
    }
  }

  function animate() {
    update();
    draw();
    sidebarStarsAnimationFrame = window.requestAnimationFrame(animate);
  }

  resize();
  draw();
  if (!prefersReducedMotion) {
    sidebarStarsAnimationFrame = window.requestAnimationFrame(animate);
  }

  if (typeof ResizeObserver !== 'undefined') {
    sidebarStarsResizeObserver = new ResizeObserver(() => {
      resize();
      draw();
    });
    sidebarStarsResizeObserver.observe(hostElement);
  }
}

function stopSidebarStarsAnimation() {
  if (sidebarStarsAnimationFrame && typeof window !== 'undefined') {
    window.cancelAnimationFrame(sidebarStarsAnimationFrame);
  }
  sidebarStarsAnimationFrame = 0;
  sidebarStarsResizeObserver?.disconnect();
  sidebarStarsResizeObserver = null;
}

function stopSidebarBinaryAnimation() {
  if (sidebarBinaryTimer && typeof window !== 'undefined') {
    window.clearInterval(sidebarBinaryTimer);
  }
  sidebarBinaryTimer = 0;
  sidebarBinaryResizeObserver?.disconnect();
  sidebarBinaryResizeObserver = null;
}

onMounted(() => {
  if (typeof window !== 'undefined' && typeof window.matchMedia === 'function') {
    mobileMediaQuery = window.matchMedia('(max-width: 960px)');
    syncViewportState(mobileMediaQuery.matches);
    if (typeof mobileMediaQuery.addEventListener === 'function') {
      mobileMediaQuery.addEventListener('change', handleMobileViewportChange);
    } else if (typeof mobileMediaQuery.addListener === 'function') {
      mobileMediaQuery.addListener(handleMobileViewportChange);
    }
  }
  void brandingStore.loadBranding().catch(() => undefined);
  void syncSidebarBackgroundAnimations();
  void siteContext.loadSites({autoSelectIfMissing: true}).catch((error) => {
    if (!isAuthenticationError(error) && !siteContext.message) {
      siteContext.setMessage('站点上下文加载失败，请稍后重试');
    }
  });
});

onBeforeUnmount(() => {
  if (mobileMediaQuery) {
    if (typeof mobileMediaQuery.removeEventListener === 'function') {
      mobileMediaQuery.removeEventListener('change', handleMobileViewportChange);
    } else if (typeof mobileMediaQuery.removeListener === 'function') {
      mobileMediaQuery.removeListener(handleMobileViewportChange);
    }
  }
  stopSidebarBinaryAnimation();
  stopSidebarStarsAnimation();
});

defineExpose({handleUserCommand, profileVisible, passwordVisible});
</script>
