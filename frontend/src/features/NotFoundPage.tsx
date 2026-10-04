import { FolderX } from 'lucide-react';
import { Link as RouterLink } from 'react-router-dom';

import { PageHeader } from '../components/PageHeader';
import { buttonVariants } from '../components/ui';

/**
 * A real 404 with a way out. A blank page for an unmatched URL leaves the user with nothing
 * but the back button, and stale links into a test repository are common — keys get pasted
 * into tickets and outlive the cases they named.
 *
 * The render (`assets/renders/09-states.html`, section C) draws this **inside a project**, naming
 * the bound it broke: *"There is no TC-9999 in Reflex Payments. The highest case key in this
 * project is TC-1284."* This component cannot say that and is not the place to start: it is the
 * router's catch-all, mounted at `path="*"` and again by `ProjectGuard` for a project id that does
 * not resolve, so it has neither a key nor a project to name. The half of the render's copy that
 * survives without them is the half that explains the *cause* — keys are per project — and it is
 * kept below. A contextual 404 belongs to the screen that knows the context; when one is written,
 * it takes the render's first sentence with it.
 */
export function NotFoundPage() {
  return (
    <>
    <PageHeader title="Page not found" testId="NotFound.Header" />
    <div className="mx-auto max-w-[56ch] px-[34px] py-[52px] text-center" data-testid="NotFound">
      <FolderX aria-hidden className="mx-auto mb-3.5 size-[34px] text-ink-4" />
      <p className="mb-[18px] text-[13.5px] leading-[1.6] text-ink-4">
        That address doesn't match anything here. The case, plan, or run it pointed at may have been
        deleted or moved to another project — keys are per project, so the same number in another
        project is a different record.
      </p>
      <RouterLink
        to="/"
        className={buttonVariants({ variant: 'primary' })}
        data-testid="NotFound.HomeLink"
      >
        Go to projects
      </RouterLink>
    </div>
    </>
  );
}
