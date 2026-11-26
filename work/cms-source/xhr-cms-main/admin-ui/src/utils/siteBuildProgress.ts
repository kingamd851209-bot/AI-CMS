export type SiteBuildStageId = 'package' | 'structure' | 'content' | 'preview' | 'release';
export type SiteBuildStageStatus = 'unknown' | 'waiting' | 'in-progress' | 'attention' | 'complete';
export type SiteBuildDeployment = 'CMS' | 'STATIC';

type ReadResult<T> =
  | { state: 'loading' | 'error'; value?: never }
  | { state: 'ready'; value: T };

export type SiteBuildProgressSnapshot<T> = ReadResult<T> & { siteId: number; packageVersion: string };
export type SiteBuildRevisionSnapshot<T> = SiteBuildProgressSnapshot<T> & {
  planId: string;
  planRevision: number;
  navigationRevision: number;
};

export interface SiteBuildAnalysisProgress {
  state: 'IDLE' | 'RUNNING' | 'PAUSED' | 'COMPLETED' | 'ATTENTION';
  pages: readonly {
    state: 'PENDING' | 'RUNNING' | 'READY' | 'FAILED' | 'SKIPPED';
    review: 'PENDING' | 'CONFIRMED' | 'NEEDS_REVIEW';
    menuId?: number | null;
  }[];
}

export interface SiteBuildStructureProgress {
  plan: { id: string; revision: number; status: 'DRAFT' | 'CONFIRMED' } | null;
  navigation: {
    revision: number;
    planRevision: number;
    status: 'DRAFT' | 'CONFIRMED' | 'APPLYING' | 'APPLIED';
    nodes: readonly { menuId: number | null; drift?: boolean }[];
  } | null;
}

export interface SiteBuildProgressCount {
  total: number;
  pending: number;
  unverified: number;
}

export interface SiteBuildChecklistProgress {
  content: SiteBuildProgressCount | null;
  preview: SiteBuildProgressCount | null;
}

export interface SiteBuildReleaseProgress {
  ready: boolean;
  evidenceCurrent: boolean;
  pages: readonly { state: string }[];
  links: { state: string; truncated: boolean; items: readonly { state: string }[] };
  interactions?: readonly unknown[];
  evidence?: readonly unknown[];
}

export interface SiteBuildProgressInput {
  siteId: number | null;
  switching?: boolean;
  package?: (ReadResult<{
    status: 'NOT_UPLOADED' | 'UPLOADED' | 'RUNNING' | 'STOPPED';
    version?: string | null;
  }> & { siteId: number }) | null;
  analysis?: SiteBuildProgressSnapshot<SiteBuildAnalysisProgress> | null;
  structure?: SiteBuildProgressSnapshot<SiteBuildStructureProgress> | null;
  checklist?: SiteBuildRevisionSnapshot<SiteBuildChecklistProgress> | null;
  release?: Partial<Record<SiteBuildDeployment, SiteBuildRevisionSnapshot<SiteBuildReleaseProgress> | null>>;
  requiredDeployments?: readonly SiteBuildDeployment[];
}

export interface SiteBuildStageProgress {
  id: SiteBuildStageId;
  label: string;
  status: SiteBuildStageStatus;
  detail: string;
  step: 0 | 5 | 6 | 7;
}

export interface SiteBuildAnalysisCounts {
  total: number;
  analyzed: number;
  confirmed: number;
  needsReview: number;
  failed: number;
  skipped: number;
}

export interface SiteBuildProgressResult {
  stages: SiteBuildStageProgress[];
  currentStageId: SiteBuildStageId | null;
  analysisCounts: SiteBuildAnalysisCounts | null;
  verifiedDeployments: SiteBuildDeployment[];
}

const stageLabels = {
  package: '项目与页面包',
  structure: '整包分析与栏目审核',
  content: '内容建模与绑定',
  preview: '模板与动态预览',
  release: '静态发布与验收',
} as const;
const stageIds = Object.keys(stageLabels) as SiteBuildStageId[];
const stageGoals: Record<Exclude<SiteBuildStageId, 'package'>, string> = {
  structure: '分析原包并确认栏目',
  content: '维护字段、初值与可增减记录',
  preview: '绑定模板并核对访客预览',
  release: '生成静态页并分别验收部署',
};
const defaultSteps = { package: 0, structure: 7, content: 6, preview: 6, release: 6 } as const;
const deployments = ['CMS', 'STATIC'] as const;
const deploymentLabels = { CMS: 'CMS 托管', STATIC: '独立静态' } as const;
const blockingLinks = new Set(['MISSING_ARTIFACT', 'PACKAGE_FALLBACK', 'MISSING_ANCHOR', 'UNVERIFIED']);

function stage(id: SiteBuildStageId, status: SiteBuildStageStatus, detail: string,
    step: SiteBuildStageProgress['step'] = defaultSteps[id]): SiteBuildStageProgress {
  return { id, label: stageLabels[id], status, detail, step };
}

function awaitingStages(status: 'unknown' | 'waiting', start: 1 | 2 = 1): SiteBuildStageProgress[] {
  return stageIds.slice(start).filter(id => id !== 'package').map(id => stage(id, status, stageGoals[id]));
}

function result(stages: SiteBuildStageProgress[], analysisCounts: SiteBuildAnalysisCounts | null = null,
    verifiedDeployments: SiteBuildDeployment[] = []): SiteBuildProgressResult {
  return {
    stages,
    currentStageId: stages.find(item => item.status !== 'complete')?.id ?? null,
    analysisCounts,
    verifiedDeployments,
  };
}

function analysisCounts(analysis: SiteBuildAnalysisProgress): SiteBuildAnalysisCounts {
  return {
    total: analysis.pages.length,
    analyzed: analysis.pages.filter(page => page.state === 'READY').length,
    confirmed: analysis.pages.filter(page => page.state === 'READY' && page.review === 'CONFIRMED').length,
    needsReview: analysis.pages.filter(page => page.state === 'READY' && page.review !== 'CONFIRMED').length,
    failed: analysis.pages.filter(page => page.state === 'FAILED').length,
    skipped: analysis.pages.filter(page => page.state === 'SKIPPED').length,
  };
}

function structureStage(structure?: SiteBuildProgressSnapshot<SiteBuildStructureProgress>,
    analysis?: SiteBuildProgressSnapshot<SiteBuildAnalysisProgress>): SiteBuildStageProgress {
  const analysisState = analysis?.state === 'ready' ? analysis.value.state : undefined;
  if (structure?.state === 'error') return stage('structure', 'attention', '整站结构读取失败，请重新读取');
  if (structure?.state !== 'ready') return analysisState === 'RUNNING'
    ? stage('structure', 'in-progress', '正在分析整包原页')
    : stage('structure', 'unknown', '等待读取当前包的审核方案和栏目');

  const { plan, navigation } = structure.value;
  if (!plan) {
    if (analysis?.state === 'error') return stage('structure', 'attention', '整包分析状态读取失败');
    if (analysisState === 'RUNNING') return stage('structure', 'in-progress', '正在分析整包原页');
    if (analysisState === 'ATTENTION') return stage('structure', 'attention', '请核对整包分析中的问题');
    if (analysisState === 'PAUSED') return stage('structure', 'in-progress', '整包分析已暂停');
    return stage('structure', 'waiting', stageGoals.structure);
  }
  if (plan.status !== 'CONFIRMED') return stage('structure', 'in-progress', '整站方案待审核', 5);
  if (!navigation) return stage('structure', 'unknown', '等待读取栏目应用回执', 5);
  if (navigation.planRevision !== plan.revision)
    return stage('structure', 'attention', '栏目回执与当前审核修订不一致', 5);
  if (navigation.status !== 'APPLIED') return stage('structure', 'in-progress',
    navigation.status === 'APPLYING' ? '正在应用已审核栏目' : '栏目对应待确认或应用', 5);
  if (!navigation.nodes.length) return stage('structure', 'attention', '栏目应用回执没有栏目范围', 5);
  const changed = navigation.nodes.filter(node => node.drift || !node.menuId).length;
  if (changed) return stage('structure', 'attention', `${changed} 个栏目与应用回执不符`, 5);
  return stage('structure', 'complete', `已审核并应用 ${navigation.nodes.length} 个栏目`, 5);
}

function checklistStage(id: 'content' | 'preview', snapshot?: SiteBuildRevisionSnapshot<SiteBuildChecklistProgress>): SiteBuildStageProgress {
  if (snapshot?.state === 'error') return stage(id, 'attention', '站点证据读取失败，请重新读取');
  const counts = snapshot?.state === 'ready' ? snapshot.value[id] : undefined;
  if (!counts) return stage(id, 'unknown', '尚未核实当前审核范围的站点证据');
  const { total, pending, unverified } = counts;
  if (![total, pending, unverified].every(value => Number.isSafeInteger(value) && value >= 0) || pending + unverified > total)
    return stage(id, 'unknown', '站点证据范围无法核对');
  if (pending || unverified) {
    const detail = [`已核实 ${total - pending - unverified}/${total} 项`];
    if (pending) detail.push(`${pending} 项待处理`);
    if (unverified) detail.push(`${unverified} 项待核实`);
    return stage(id, pending ? 'in-progress' : 'unknown', detail.join(' · '));
  }
  return stage(id, 'complete', total ? `已核实 ${total} 项` : '当前审核范围无需处理');
}

function verifiedRelease(report: SiteBuildReleaseProgress | undefined): boolean {
  return !!report?.ready && (report.evidenceCurrent || report.interactions?.length === 0 && report.evidence?.length === 0)
    && report.pages.length > 0 && report.pages.every(page => ['READY', 'EXCEPTION'].includes(page.state))
    && report.links.state === 'SCANNED' && !report.links.truncated
    && !report.links.items.some(link => blockingLinks.has(link.state));
}

export function getSiteBuildProgress(input: SiteBuildProgressInput): SiteBuildProgressResult {
  const { siteId, switching } = input;
  if (switching) return result([stage('package', 'unknown', '正在切换站点'), ...awaitingStages('unknown')]);
  if (!siteId) return result([stage('package', 'waiting', '请先选择站点'), ...awaitingStages('waiting')]);

  const pack = input.package?.siteId === siteId ? input.package : undefined;
  if (pack?.state !== 'ready') {
    const availability = pack?.state === 'error'
      ? stage('package', 'attention', '页面包读取失败，请重新读取')
      : stage('package', 'unknown', '等待读取本站页面包');
    return result([availability, ...awaitingStages('unknown')]);
  }
  if (pack.value.status === 'NOT_UPLOADED')
    return result([stage('package', 'waiting', '请先上传本站页面包'), ...awaitingStages('waiting')]);
  const packageVersion = pack.value.version;
  if (!packageVersion)
    return result([stage('package', 'unknown', '当前页面包版本无法核实'), ...awaitingStages('unknown')]);

  const packageStage = stage('package', 'complete', '当前版本已上传');
  const inScope = <T,>(item?: SiteBuildProgressSnapshot<T> | null) =>
    item?.siteId === siteId && item.packageVersion === packageVersion ? item : undefined;
  const analysis = inScope(input.analysis);
  const analysisSummary = analysis?.state === 'ready' ? analysisCounts(analysis.value) : undefined;
  const structure = inScope(input.structure);
  const structureProgress = structureStage(structure, analysis);
  const { plan, navigation } = structure?.value ?? {};
  if (structureProgress.status !== 'complete' || !plan || !navigation) {
    const status = structureProgress.status === 'unknown' || structureProgress.status === 'attention' ? 'unknown' : 'waiting';
    return result([packageStage, structureProgress, ...awaitingStages(status, 2)], analysisSummary);
  }

  const forRevision = <T,>(item?: SiteBuildRevisionSnapshot<T> | null) =>
    inScope(item) && item?.planId === plan.id && item.planRevision === plan.revision
      && item.navigationRevision === navigation.revision ? item : undefined;
  const checklist = forRevision(input.checklist);
  const reports = { CMS: forRevision(input.release?.CMS), STATIC: forRevision(input.release?.STATIC) };
  const verified = deployments.filter(mode => {
    const snapshot = reports[mode];
    return snapshot?.state === 'ready' && verifiedRelease(snapshot.value);
  });
  const required = input.requiredDeployments?.length ? input.requiredDeployments : deployments;
  const acceptedText = verified.map(mode => `${deploymentLabels[mode]}已验收`).join(' · ');
  const modes = [...new Set([...required, ...verified])];
  const releaseDetail = modes.map(mode => {
    if (verified.includes(mode)) return `${deploymentLabels[mode]}已验收`;
    if (reports[mode]?.state === 'error') return `${deploymentLabels[mode]}读取失败`;
    if (reports[mode]?.state === 'loading') return `${deploymentLabels[mode]}读取中`;
    if (reports[mode]?.state === 'ready') return `${deploymentLabels[mode]}待验收`;
    return `${deploymentLabels[mode]}未读取`;
  }).join(' · ');
  const releaseStatus = required.every(mode => verified.includes(mode)) ? 'complete'
    : required.some(mode => reports[mode]?.state === 'error') ? 'attention'
    : verified.length || required.some(mode => reports[mode]?.state === 'ready') ? 'in-progress' : 'unknown';
  return result([packageStage, structureProgress,
    verified.length ? stage('content', 'complete', acceptedText) : checklistStage('content', checklist),
    verified.length ? stage('preview', 'complete', acceptedText) : checklistStage('preview', checklist),
    stage('release', releaseStatus, releaseDetail),
  ], analysisSummary, verified);
}
