import type { Skill } from '../../api/types';
import { Chip, FilterStrip, type AppliedFilter } from '../../components/kit';
import { Input } from '../../components/ui';
import { NativeSelect } from '../../components/ui/native-select';
import { formatDate } from '../../lib/format';

/**
 * `directory.html`'s filter row — skill chips and *Free from*, **held in the URL**.
 *
 * <h2>The URL is what makes the acceptance criterion true</h2>
 *
 * *"Who is free next month with skill X"* has to be an **answer somebody can send to somebody
 * else**, and a filter in component state is an answer that only exists in one browser tab. So this
 * component owns no state at all: it reads what the page read out of `useSearchParams` and calls
 * back with the next set. That also makes the query key move with the filters, which is what stops
 * a slow answer for *Playwright* landing under a heading that now says *Playwright + Java*.
 *
 * <h2>Skills are a set, and they are ANDed</h2>
 *
 * The server ANDs `skill`: a person must hold **every** one, which is what "who knows Playwright
 * and Java" means. An OR would answer a question nobody asks when staffing — anybody who knows one
 * of these — so the chips read as a narrowing list and each one removes itself.
 *
 * The picker is the platform `<select>` rather than the kit's `MultiPicker`, because the vocabulary
 * here is a closed company-owned list of plain words with no second line and no glyph: `MultiPicker`
 * earns its weight where an option is a person or a status, and here it would be a combobox over
 * fifteen nouns. Choosing one adds a chip and resets the control, so the applied set and the picker
 * never show the same skill twice.
 */
export interface DirectoryFiltersProps {
  skills: readonly Skill[];
  /** The selected skill ids, in URL order. */
  selected: readonly string[];
  freeFrom: string;
  onToggleSkill: (skillId: string) => void;
  onFreeFrom: (date: string) => void;
  onClear: () => void;
  /** `3 of 11 people match` — **served**, never counted from a filtered array. */
  summary?: string;
  testId: string;
}

export function DirectoryFilters({
  skills,
  selected,
  freeFrom,
  onToggleSkill,
  onFreeFrom,
  onClear,
  summary,
  testId,
}: DirectoryFiltersProps) {
  const byId = new Map(skills.map((skill) => [skill.id, skill]));

  const applied: AppliedFilter[] = [
    ...selected.map((id) => ({
      key: `skill:${id}`,
      // A skill the vocabulary read has not answered for yet still shows — as its id, briefly —
      // rather than vanishing: a chip that disappears while a second request is in flight looks
      // like the filter came off.
      label: `Skill · ${byId.get(id)?.name ?? id}`,
      onRemove: () => onToggleSkill(id),
    })),
    ...(freeFrom ?
      [
        {
          key: 'freeFrom',
          label: `Free from · ${formatDate(freeFrom)}`,
          onRemove: () => onFreeFrom(''),
        },
      ]
    : []),
  ];

  const unpicked = skills.filter((skill) => !selected.includes(skill.id));

  return (
    <FilterStrip
      data-testid={testId}
      applied={applied}
      summary={summary}
      onClear={applied.length > 0 ? onClear : undefined}
      pickers={
        <>
          <NativeSelect
            aria-label="Add a skill filter"
            className="w-[168px]"
            value=""
            onChange={(event) => {
              if (event.target.value) onToggleSkill(event.target.value);
            }}
            data-testid={`${testId}.Skill`}
          >
            <option value="">+ Skill</option>
            {unpicked.map((skill) => (
              <option key={skill.id} value={skill.id}>
                {skill.name}
              </option>
            ))}
          </NativeSelect>

          <label className="flex items-center gap-1.5 text-[12px] text-ink-3">
            Free from
            <Input
              type="date"
              className="w-[152px]"
              value={freeFrom}
              onChange={(event) => onFreeFrom(event.target.value)}
              data-testid={`${testId}.FreeFrom`}
            />
          </label>
        </>
      }
    />
  );
}

/**
 * The vocabulary as read-only chips, for a caller that wants to show a person's skills.
 *
 * Here rather than in `PersonCard` because the highlight rule is a *filter* fact — a chip is filled
 * when it is one of the skills the reader asked for — and that rule belongs with the filters it
 * describes rather than duplicated on every surface that lists somebody's skills.
 */
export function SkillChips({
  skills,
  highlighted,
  onToggle,
  testId,
}: {
  skills: readonly Skill[];
  highlighted: readonly string[];
  onToggle: (skillId: string) => void;
  testId: string;
}) {
  if (skills.length === 0) {
    return (
      <p className="m-0 text-[12px] text-ink-4" data-testid={`${testId}.None`}>
        No skills recorded.
      </p>
    );
  }

  return (
    <div className="flex flex-wrap gap-1.5" data-testid={testId}>
      {skills.map((skill) => (
        <Chip
          key={skill.id}
          selected={highlighted.includes(skill.id)}
          onClick={() => onToggle(skill.id)}
          data-testid={`${testId}.${skill.id}`}
        >
          {skill.name}
        </Chip>
      ))}
    </div>
  );
}
