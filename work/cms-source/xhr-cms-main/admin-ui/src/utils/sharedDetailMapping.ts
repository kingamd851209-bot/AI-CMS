import type { FieldPlan, FieldPlanReference } from '@/api/templateFieldPlan';
import type { PairedMappingWorkspace, PairedOptions, PairedUnit, PairingItem } from '@/api/templateListPairing';

type Source = PairedMappingWorkspace['sources'][number];
type Choices = Record<string, string>;
type Proposal = {side: 'list' | 'detail'; id: string; field: string};

function position(card: PairingItem, ref: FieldPlanReference | undefined, source: Source | undefined, role = '') {
  const prefix = card.selector + ' > ';
  if (!ref || !source || !source.selector.startsWith(prefix)
    || ref.start !== 0 || ref.end !== source.rawValue.length
    || !card.list.evidence.some(evidence => role + evidence.sourceId === source.id
      && evidence.kind === source.kind && evidence.selector === source.selector)) return;
  return JSON.stringify([source.kind, source.selector.slice(prefix.length)]);
}

export function reuseSharedDetailMapping(plan: FieldPlan, previous: PairingItem, current: PairingItem,
  options: PairedOptions, workspace: PairedMappingWorkspace, list: Choices, detail: Choices) {
  const currentSources = new Map(workspace.sources.map(source => [source.id, source]));
  const previousSources = new Map(plan.sources.map(source => [source.id, source]));
  const currentUnits = new Map(workspace.units.map(unit => [unit.id, unit]));
  const candidates = options.list.units.flatMap(unit => {
    const saved = currentUnits.get(unit.id);
    if (!saved || saved.sources.length !== 1 || saved.fieldType !== unit.fieldType) return [];
    const ref = saved.sources[0];
    const slot = position(current, ref, currentSources.get(ref.sourceId));
    return slot ? [{unit, slot}] : [];
  });
  const fitting = (unit: PairedUnit, fieldName: string) => options.fields.some(field =>
    field.fieldName === fieldName && field.writable && field.fieldType === unit.fieldType
    && field.capacity >= unit.valueLength);
  const result = { list: {...list}, detail: {...detail}, added: 0 };
  const used = new Set([...Object.values(list), ...Object.values(detail)].filter(Boolean));
  const proposals = plan.bindings.flatMap<Proposal>(binding => {
    const field = binding.fieldName;
    if (binding.action !== 'REUSE' || !field || used.has(field) || binding.sources.length !== 1) return [];
    const ref = binding.sources[0], source = previousSources.get(ref.sourceId);
    if (binding.unitId.startsWith('LIST:')) {
      const slot = position(previous, ref, source, 'LIST:');
      const matches = candidates.filter(candidate => candidate.slot === slot && candidate.unit.fieldType === binding.fieldType);
      if (matches.length !== 1 || list[matches[0].unit.id] || !fitting(matches[0].unit, field)) return [];
      return [{side: 'list', id: matches[0].unit.id, field}];
    }
    if (binding.unitId.startsWith('DETAIL:') && !Object.values(detail).some(Boolean)
      && binding.fieldType === 'Editor' && source?.kind === 'HTML_REGION' && source.id.startsWith('DETAIL:')) {
      const unit = options.detail.units.find(unit => unit.id === binding.unitId.slice('DETAIL:'.length));
      if (unit?.fieldType === 'Editor' && fitting(unit, field))
        return [{side: 'detail', id: unit.id, field}];
    }
    return [];
  });
  for (const proposal of proposals) {
    if (proposals.some(other => other !== proposal && (other.field === proposal.field
      || other.side === proposal.side && other.id === proposal.id))) continue;
    result[proposal.side][proposal.id] = proposal.field;
    result.added++;
  }
  return result;
}
