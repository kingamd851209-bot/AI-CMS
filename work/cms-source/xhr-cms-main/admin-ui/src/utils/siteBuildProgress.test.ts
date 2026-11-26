import assert from 'node:assert/strict';
import { test } from 'node:test';
import { getSiteBuildProgress, type SiteBuildProgressInput, type SiteBuildReleaseProgress,
  type SiteBuildStageId } from './siteBuildProgress.ts';

const scope = { siteId: 59, packageVersion: 'v1795334340312-ac9257f2f2f7' };
const revision = { ...scope, planId: 'applied', planRevision: 2, navigationRevision: 6 };
const pack = { siteId: scope.siteId, state: 'ready', value: { status: 'RUNNING', version: scope.packageVersion } } as const;
const plan = { id: revision.planId, revision: revision.planRevision, status: 'CONFIRMED' } as const;
const navigation = { revision: revision.navigationRevision, planRevision: revision.planRevision,
  status: 'APPLIED', nodes: [{ menuId: 396 }, { menuId: 405 }] } as const;
const structure = { ...scope, state: 'ready', value: { plan, navigation } } as const;
const applied: SiteBuildProgressInput = { siteId: 59, package: pack, structure };
const stageGoals = ['分析原包并确认栏目', '维护字段、初值与可增减记录', '绑定模板并核对访客预览', '生成静态页并分别验收部署'];
const status = (input: SiteBuildProgressInput, id: SiteBuildStageId) =>
  getSiteBuildProgress(input).stages.find(value => value.id === id)?.status;

function release(ready: number, excepted: number, pending: number, accepted: boolean): SiteBuildReleaseProgress {
  return { ready: accepted, evidenceCurrent: accepted,
    pages: [...Array.from({ length: ready }, () => ({ state: 'READY' })),
      ...Array.from({ length: excepted }, () => ({ state: 'EXCEPTION' })),
      ...Array.from({ length: pending }, () => ({ state: 'PENDING' }))],
    links: { state: 'SCANNED', truncated: false, items: [{ state: 'EXTERNAL' }] },
  };
}

test('区分项目未选、当前包尚未读取和实际无包', () => {
  assert.deepEqual(getSiteBuildProgress({ siteId: null }).stages.map(stage => stage.status), Array(5).fill('waiting'));
  assert.deepEqual(getSiteBuildProgress({ siteId: null }).stages.map(stage => stage.detail), ['请先选择站点', ...stageGoals]);
  assert.deepEqual(getSiteBuildProgress({ siteId: 59 }).stages.map(stage => stage.status), Array(5).fill('unknown'));
  assert.equal(status({ ...applied, package: { siteId: 59, state: 'loading' } }, 'package'), 'unknown');
  assert.equal(status({ ...applied, package: { siteId: 59, state: 'error' } }, 'package'), 'attention');
  const empty = getSiteBuildProgress({ ...applied, package: { siteId: 59, state: 'ready', value: { status: 'NOT_UPLOADED' } } });
  assert.deepEqual(empty.stages.map(stage => stage.status), Array(5).fill('waiting'));
  assert.deepEqual(empty.stages.map(stage => stage.detail), ['请先上传本站页面包', ...stageGoals]);
  assert.equal(status({ ...applied, package: { ...pack, value: { ...pack.value, status: 'STOPPED' } } }, 'package'), 'complete');
  assert.equal(getSiteBuildProgress(applied).stages[0]?.detail, '当前版本已上传');
});

test('栏目审核完成不受原页历史变体和片段影响', () => {
  const analysis = { ...scope, state: 'ready', value: { state: 'COMPLETED', pages: [
    { state: 'READY', review: 'CONFIRMED', menuId: 396 },
    { state: 'READY', review: 'PENDING' }, { state: 'FAILED', review: 'PENDING' },
    { state: 'SKIPPED', review: 'PENDING' },
  ] } } as const;
  const result = getSiteBuildProgress({ ...applied, analysis });
  assert.equal(result.currentStageId, 'content');
  assert.equal(result.stages[1]?.status, 'complete');
  assert.equal(result.stages[1]?.step, 5);
  assert.deepEqual(result.analysisCounts, { total: 4, analyzed: 2, confirmed: 1, needsReview: 1, failed: 1, skipped: 1 });
  const noPlan = getSiteBuildProgress({ ...applied, structure: { ...structure, value: { plan: null, navigation: null } }, analysis });
  assert.equal(noPlan.stages[1]?.status, 'waiting');
  assert.deepEqual(noPlan.stages.slice(1).map(stage => stage.detail), stageGoals);
});

test('已读取空任务与未知证据分开，判断过程不依赖当前打开的单页', () => {
  const result = getSiteBuildProgress({ ...applied, checklist: { ...revision, state: 'ready', value: {
    content: { total: 0, pending: 0, unverified: 0 }, preview: null,
  } } });
  assert.equal(result.stages[2]?.status, 'complete');
  assert.equal(result.stages[3]?.status, 'unknown');
  assert.equal(result.currentStageId, 'preview');
  const incomplete = { ...applied, checklist: { ...revision, state: 'ready', value: {
    content: { total: 12, pending: 4, unverified: 2 }, preview: { total: 9, pending: 0, unverified: 1 },
  } } } as const;
  assert.equal(status(incomplete, 'content'), 'in-progress');
  assert.equal(status(incomplete, 'preview'), 'unknown');
  assert.equal(status({ ...incomplete, checklist: { ...revision, state: 'loading' } }, 'content'), 'unknown');
});

test('真实签收范围样本中只有已签模式计入完成，签收证明可覆盖旧待办', () => {
  // 碧朗湾阶段 161 的 37 项 CMS 和 38 项独立静态就绪及三项已审例外。
  const cms = { ...revision, state: 'ready', value: release(37, 3, 0, true) } as const;
  const independent = { ...revision, state: 'ready', value: release(38, 3, 0, true) } as const;
  const olderChecklist = { ...revision, state: 'ready', value: {
    content: { total: 12, pending: 9, unverified: 0 }, preview: null,
  } } as const;
  const input = { ...applied, checklist: olderChecklist, release: { CMS: cms } };
  const partial = getSiteBuildProgress(input);
  assert.deepEqual(partial.verifiedDeployments, ['CMS']);
  assert.equal(partial.currentStageId, 'release');
  assert.equal(partial.stages[2]?.status, 'complete');
  assert.equal(partial.stages[3]?.status, 'complete');
  assert.match(partial.stages[4]?.detail ?? '', /独立静态未读取/);
  assert.equal(getSiteBuildProgress({ ...input, requiredDeployments: ['CMS'] }).currentStageId, null);
  assert.deepEqual(getSiteBuildProgress({ ...input, release: { CMS: cms, STATIC: independent } }).verifiedDeployments,
    ['CMS', 'STATIC']);
  assert.equal(getSiteBuildProgress({ ...input, release: { CMS: cms, STATIC: independent } }).currentStageId, null);
});

test('部分签收、过期证据和有阻断的报告不能提前完成', () => {
  // 碧朗湾阶段 166 中间回执是 48/52 就绪、三项例外、一项未就绪且交互尚未签收。
  const middle = { ...revision, state: 'ready', value: release(48, 3, 1, false) } as const;
  assert.deepEqual(getSiteBuildProgress({ ...applied, release: { CMS: middle } }).verifiedDeployments, []);
  const passed = release(1, 0, 0, true);
  for (const value of [release(0, 0, 0, true), { ...passed, evidenceCurrent: false },
    { ...passed, evidenceCurrent: false, interactions: [{ kind: 'CUSTOM' }], evidence: [] },
    { ...passed, links: { ...passed.links, truncated: true } },
    { ...passed, links: { ...passed.links, items: [{ state: 'MISSING_ANCHOR' }] } }]) {
    assert.deepEqual(getSiteBuildProgress({ ...applied, release: { CMS: { ...revision, state: 'ready', value } } })
      .verifiedDeployments, []);
  }
  const noRequiredInteractions = { ...passed, evidenceCurrent: false, interactions: [], evidence: [] };
  assert.deepEqual(getSiteBuildProgress({ ...applied,
    release: { CMS: { ...revision, state: 'ready', value: noRequiredInteractions } } }).verifiedDeployments, ['CMS']);
  const loadingWithCache = { ...revision, state: 'loading', value: passed } as unknown as
    NonNullable<SiteBuildProgressInput['release']>['CMS'];
  const reloading = getSiteBuildProgress({ ...applied, release: { CMS: loadingWithCache } });
  assert.deepEqual(reloading.verifiedDeployments, []);
  assert.match(reloading.stages[4]?.detail ?? '', /CMS 托管读取中/);
});

test('隔离切站、换包、更新方案和更新导航时到达的旧快照', () => {
  const signed = { ...revision, state: 'ready', value: release(37, 3, 0, true) } as const;
  const input = { ...applied, release: { CMS: signed } };
  assert.deepEqual(getSiteBuildProgress({ ...input, switching: true }).stages.map(stage => stage.status), Array(5).fill('unknown'));
  assert.equal(status({ ...input, siteId: 58 }, 'package'), 'unknown');
  assert.equal(status({ ...input, package: { ...pack, value: { ...pack.value, version: 'new-package' } } }, 'structure'), 'unknown');
  const planChanged = { ...input, structure: { ...structure, value: { plan: { ...plan, revision: 3 }, navigation } } };
  assert.equal(status(planChanged, 'structure'), 'attention');
  assert.deepEqual(getSiteBuildProgress(planChanged).verifiedDeployments, []);
  const navChanged = { ...input, structure: { ...structure, value: { plan, navigation: { ...navigation, revision: 7 } } } };
  assert.equal(status(navChanged, 'structure'), 'complete');
  assert.equal(status(navChanged, 'release'), 'unknown');
  const drifted = { ...input, structure: { ...structure, value: { plan,
    navigation: { ...navigation, nodes: [{ menuId: 396, drift: true }] } } } };
  assert.equal(status(drifted, 'structure'), 'attention');
  assert.deepEqual(getSiteBuildProgress(drifted).verifiedDeployments, []);
});
