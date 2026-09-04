import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useCreateSkill, usePutPersonSkills, useSkills } from '../../api/queries';
import type { Skill } from '../../api/types';
import { Panel } from '../../components/PageHeader';
import { Banner, Chip } from '../../components/kit';
import { Button, Input } from '../../components/ui';
import { NativeSelect } from '../../components/ui/native-select';
import { describeError } from '../../lib/describeError';

/**
 * What this person knows — the chips `directory.html` filters on, from the person's own end.
 *
 * <h2>The payload is the SET, not a delta, and that is the whole risk in this panel</h2>
 *
 * `PUT /staffing/people/{userId}/skills` replaces the lot: a body naming one skill takes the other
 * four off. So every control here — add, remove, and adding a brand-new skill — computes the
 * **complete next set** and sends that. A handler that sent only what it touched would silently
 * clear somebody's record, with a `200` and no symptom until the directory stopped finding them.
 *
 * <h2>Two vocabularies, one control</h2>
 *
 * A skill is a company-wide row, so adding one to a person is picking from the company's list, and
 * a skill nobody has recorded yet has to be created first. `useCreateSkill` answers `409` on a name
 * the company already holds, which is not an error worth a banner — it means the reader typed a
 * word that is already in the picker — so the refusal says exactly that.
 *
 * <h2>A chip goes to the directory, filtered</h2>
 *
 * `Chip`'s `onClick` is required because *"a chip with nothing behind it is a badge wearing the
 * wrong component"*, and there is a genuine action here: the question a reader has while looking at
 * somebody's skills is *who else knows this*, which is `/directory?skill=<id>` — the same filter the
 * chips on that screen toggle. Removing is the `×`, so the two are not the same gesture.
 */
export interface SkillsPanelProps {
  userId: string;
  /** This person's skills, as the person read served them. */
  skills: readonly Skill[];
  canEdit: boolean;
  /** The gate's own sentence, so the tooltip and the server's refusal agree. */
  reason: string | undefined;
  testId: string;
}

export function SkillsPanel({ userId, skills, canEdit, reason, testId }: SkillsPanelProps) {
  const navigate = useNavigate();
  const vocabulary = useSkills();
  const save = usePutPersonSkills(userId);
  const createSkill = useCreateSkill();
  const [newName, setNewName] = useState('');

  const held = skills.map((skill) => skill.id);
  const unheld = (vocabulary.data ?? []).filter((skill) => !held.includes(skill.id));

  /** Always the complete next set — see the file header on why nothing here sends a delta. */
  const replaceWith = (skillIds: string[]) => save.mutate({ skillIds });

  const addNew = async () => {
    const name = newName.trim();
    if (name === '') return;
    const existing = (vocabulary.data ?? []).find(
      (skill) => skill.name.toLowerCase() === name.toLowerCase(),
    );
    // Already in the vocabulary — add it to this person rather than asking the server to refuse a
    // name it already has. The 409 would be correct and useless.
    if (existing) {
      setNewName('');
      replaceWith([...held, existing.id]);
      return;
    }
    const created = await createSkill.mutateAsync({ name });
    setNewName('');
    replaceWith([...held, created.id]);
  };

  const failure =
    save.error ? describeError(save.error).body
    : createSkill.error ? describeError(createSkill.error).body
    : null;

  return (
    <Panel testId={testId} title="Skills">
      <div className="flex flex-col gap-3">
        {failure && (
          <div data-testid={`${testId}.Error`}>
            <Banner tone="bad">
              <span role="alert">{failure}</span>
            </Banner>
          </div>
        )}

        {skills.length === 0 ?
          <p className="m-0 text-[12.5px] text-ink-4" data-testid={`${testId}.None`}>
            No skills recorded. Until there are, this person answers no question on the directory’s
            skill filter — they are found by name and by nothing else.
          </p>
        : <div className="flex flex-wrap gap-1.5" data-testid={`${testId}.Chips`}>
            {skills.map((skill) => (
              <Chip
                key={skill.id}
                onClick={() => navigate(`/directory?skill=${encodeURIComponent(skill.id)}`)}
                onRemove={
                  canEdit ?
                    () => replaceWith(held.filter((id) => id !== skill.id))
                  : undefined
                }
                removeLabel={`Remove ${skill.name}`}
                data-testid={`${testId}.${skill.id}`}
              >
                {skill.name}
              </Chip>
            ))}
          </div>
        }

        {/*
          Disabled with the reason, never absent (§31): a reader who cannot maintain skills should
          still learn that somebody can, and what it costs. The `×` on a chip is the one control
          that goes away instead — `Chip` renders no remove button at all without a handler, and a
          dead glyph inside a pill is worse than a pill without one.
        */}
        <div className="flex flex-wrap items-end gap-2" title={canEdit ? undefined : reason}>
          <NativeSelect
            aria-label="Add a skill"
            className="w-[180px]"
            value=""
            disabled={!canEdit || save.isPending || unheld.length === 0}
            onChange={(event) => {
              if (event.target.value) replaceWith([...held, event.target.value]);
            }}
            data-testid={`${testId}.Add`}
          >
            <option value="">
              {unheld.length === 0 ? 'Every skill already held' : '+ Skill'}
            </option>
            {unheld.map((skill) => (
              <option key={skill.id} value={skill.id}>
                {skill.name}
              </option>
            ))}
          </NativeSelect>

          <Input
            aria-label="New skill name"
            className="w-[180px]"
            placeholder="Or a new one…"
            maxLength={100}
            disabled={!canEdit}
            value={newName}
            onChange={(event) => setNewName(event.target.value)}
            data-testid={`${testId}.New`}
          />
          <Button
            size="sm"
            disabled={!canEdit || newName.trim() === '' || createSkill.isPending || save.isPending}
            onClick={() => void addNew()}
            data-testid={`${testId}.Create`}
          >
            {createSkill.isPending ? 'Adding…' : 'Add'}
          </Button>
        </div>
      </div>
    </Panel>
  );
}
