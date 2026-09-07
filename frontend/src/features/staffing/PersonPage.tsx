import { useParams } from 'react-router-dom';
import { useStaffingPerson } from '../../api/queries';
import { useCurrentUserId } from '../../auth/useAuth';
import { useCanInCompany } from '../../auth/useCan';
import { DataRegion, GridSkeleton } from '../../components/DataRegion';
import { PageHeader } from '../../components/PageHeader';
import { AbsencePanel } from './AbsencePanel';
import { PersonCard } from './PersonCard';
import { ProfilePanel } from './ProfilePanel';
import { SkillsPanel } from './SkillsPanel';
import { todayIso, windowOver } from './staffingWindow';

/**
 * `/directory/:userId` — one person's profile, skills and absence.
 *
 * <h2>THERE IS NO MEMBER PAGE IN THIS APP, and these three panels are why that matters (017 D-g)</h2>
 *
 * `member.html` draws them on a member page this product does not have: `/admin/teams` is
 * `features/admin/TeamsPage.tsx`, a **list**, and there is no `/admin/teams/:userId` route and no
 * ~~`MemberPage.tsx`~~ — struck because the absence IS the sentence, and `citedFiles.test.ts`
 * settles the claim from the other end: it walks `frontend/src` and would redden the moment
 * somebody created one. Verified again on 2026-09-04. The rest of that mockup — the company-role
 * toggles, the project-grants table, the "what Test Engineer can do" matrix, the assistants panel —
 * is `identity`'s surface and already lives on `TeamsPage` and `ProjectAccessPage`. Building a
 * member page to hang three staffing panels off would have this module porting another module's
 * screen.
 *
 * So they land here, on the directory's own detail view: the route `directory.html`'s `Profile`
 * button already pointed at, and where a reader looking for *"when is this person free"* is already
 * standing. If a member page is built later the panels move to it and this route stays as the
 * directory's detail.
 *
 * <h2>The gate is the contract's own: `staffing:directory`, OR the caller is this person</h2>
 *
 * `GET /staffing/people/{userId}` says exactly that, and this page does not pre-empt it. There is
 * no client-side arm that decides you may read your own row — the server decides, and
 * `DataRegion`'s Refused state renders the refusal for anybody else, naming the permission. A
 * client gate here would be a second copy of an authorization rule, which is the bug class plan
 * §6b names and is no less one for living on the client.
 *
 * What *is* decided here is 017 D-h's affordance: whether the Add control on the absence panel is
 * enabled. Recording your own absence needs no key; somebody else's needs `staffing:absence`.
 * `AbsencePanel` takes both facts and its own header says why.
 *
 * <h2>The card is reused, minus its skills and its Profile button</h2>
 *
 * `PersonCard` already draws the badge, the strapline, the free-from sentence and a one-person
 * `AllocationTimeline`, so this page renders it rather than a second card. Its two optional props
 * are both omitted: `profileTo`, because this is where it would go, and `onToggleSkill`, because
 * `SkillsPanel` below owns the skills and owns editing them — the chips would otherwise appear
 * twice on one screen under two different affordances.
 */

const TEST_ID = 'Person';

export function PersonPage() {
  const { userId = '' } = useParams();
  const callerId = useCurrentUserId();
  const person = useStaffingPerson(userId, '', '');

  /*
    `useCanInCompany()` takes an ENABLED flag, not a permission key — the key goes to the gate it
    returns. Both keys here are COMPANY scope, which is the scope this hook asks about.
  */
  const companyGate = useCanInCompany();
  const canProfile = companyGate.can('staffing:profile');
  const canAbsence = companyGate.can('staffing:absence');

  const isSelf = callerId !== undefined && callerId === userId;
  const displayName = person.data?.person.displayName;

  return (
    <div>
      <PageHeader
        testId={`${TEST_ID}.Header`}
        title={displayName ?? 'Person'}
        documentTitle={displayName ? `${displayName} · Directory` : 'Directory'}
        subtitle={
          isSelf ?
            'Your own staffing — contracted hours, skills, and the days you are away.'
          : 'Contracted hours, skills, and the days they are away.'
        }
      />

      <DataRegion
        testId={`${TEST_ID}.Region`}
        state={{ ...person, refetch: () => void person.refetch() }}
        skeleton={<GridSkeleton rows={4} />}
      >
        {(loaded) => (
          <div className="flex flex-col gap-4">
            <PersonCard
              person={loaded}
              /*
                The person read answers no `from`/`to` — unlike the capacity view, which carries the
                window it used — so the widest span in the answer is the only evidence of what the
                server defaulted to. `staffingWindow.ts` owns that reasoning and the directory draws
                its bars from the same function.
              */
              window={windowOver([loaded], { from: todayIso(), to: todayIso() })}
              testId={`${TEST_ID}.Card`}
            />

            <div className="grid gap-4 lg:grid-cols-[2fr_1fr]">
              <div className="flex flex-col gap-4">
                <ProfilePanel
                  userId={userId}
                  profile={loaded.profile}
                  canEdit={canProfile}
                  reason={companyGate.reason('staffing:profile')}
                  testId={`${TEST_ID}.Profile`}
                />
                <SkillsPanel
                  userId={userId}
                  skills={loaded.skills}
                  canEdit={canProfile}
                  reason={companyGate.reason('staffing:profile')}
                  testId={`${TEST_ID}.Skills`}
                />
              </div>

              <AbsencePanel
                userId={userId}
                /*
                  From the person read, not a second call: `PersonCapacity.absences` is this
                  window's absences, served beside the capacity figures they were already subtracted
                  from. `useAbsences` would answer over its own window, and the panel could then
                  list a day the hours above it do not account for.
                */
                absences={loaded.absences}
                isSelf={isSelf}
                canRecordForOthers={canAbsence}
                reason={companyGate.reason('staffing:absence')}
                testId={`${TEST_ID}.Absence`}
              />
            </div>
          </div>
        )}
      </DataRegion>
    </div>
  );
}
