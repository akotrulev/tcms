/**
 * The files attached to a piece of **authored content** — a test case's definition or a
 * documentation page — and the two controls that add and remove one.
 *
 * ## One component, because the contract is one schema
 *
 * `Attachment` is a single schema over three owner types, "identical field for field *and* meaning
 * for meaning" (`openapi.yaml`), so two panels would be `ui-rules` §8's renderer divergence in its
 * purest form: two places deciding how a size renders, where the delete lives, what an empty list
 * says, and which of them gets the authenticated download right. `EvidencePanel` stays its own file
 * — evidence has a **caption**, an `OWN`-qualified delete and a frozen-run state, none of which
 * exist here — and the shared half is this.
 *
 * ## The download is a fetch, not a link, and that is the whole of the hard part
 *
 * `Attachment.url` is a server-composed `/api/v1/…` path and the obvious spellings both fail
 * **silently**:
 *
 *  - **`<a href={url}>` sends no credential.** The session is a bearer token this client sets on
 *    every request (`SecurityConfiguration` is `STATELESS`, there is no cookie anywhere), so an
 *    anchor answers **401** while rendering as a working link. `api/client.ts` names this hole and
 *    records that `EvidencePanel` still has it.
 *  - **`<img src={url}>` is the same request** and therefore the same 401 — which is why there is no
 *    inline thumbnail here even though the server serves these `inline` and the contract's own copy
 *    talks about a screenshot rendering beside the steps. A thumbnail would need the bytes fetched
 *    into an object URL, i.e. an eager full-size download of every image on page load, with no
 *    thumbnail endpoint to make it cheap. A broken image on every row is worse than no image; the
 *    inline preview is recorded as unbuilt rather than shipped broken.
 *
 * So a row's name is a **button** that runs `fetchArtifact` (same-origin guard, bearer token, 60s
 * budget) and hands the blob to `saveBlob`. That is `DownloadReportButton`'s path exactly; the two
 * are not merged because that one keys on a `ReportJob` and composes a filename the response may
 * not carry, and an attachment always has one.
 *
 * ## The delete gate has no author arm, deliberately
 *
 * The caller passes one `permission` for both controls and **no `authorId`**. Copying
 * `EvidencePanel`'s `OWN` arm is the tempting move and it is wrong here: a case attachment is part
 * of the case definition, which `case:edit` already owns wholesale — an authorship arm would make
 * removing the screenshot stricter than removing the step that references it. The plan document and
 * the endpoint's own description both say so; this comment is the third copy on purpose, because it
 * is the change a reviewer would otherwise "fix".
 */
import DeleteOutlineIcon from '@mui/icons-material/DeleteOutlineOutlined';
import UploadFileIcon from '@mui/icons-material/UploadFile';
import { Alert, Button, IconButton, Stack, Tooltip, Typography } from '@mui/material';
import { useRef, useState } from 'react';
import type { Attachment, PermissionKey } from '../api/types';
import { describeError } from '../lib/describeError';
import { formatBytes, formatDateTime } from '../lib/format';
import { uploadRefusal } from '../lib/attachments';
import { FileDownloadLink } from './FileDownloadLink';
import { Panel } from './PageHeader';
import { PermissionGate } from './PermissionGate';

export function AttachmentsPanel({
  projectId,
  permission,
  attachments,
  onAttach,
  attaching,
  attachError,
  onDelete,
  deleting,
  testId,
  emptyBody,
}: {
  projectId: string;
  /**
   * The one key behind **both** writes — `case:edit` for a case, `doc:edit` for a page. One prop
   * rather than two, because the slice's decision is precisely that attach and delete cost the
   * same thing; two props would make the asymmetry expressible and someone would express it.
   */
  permission: PermissionKey;
  attachments: Attachment[];
  onAttach: (file: File) => void;
  attaching: boolean;
  /** The upload's failure, rendered in place. `null`/`undefined` while nothing has failed. */
  attachError: unknown;
  onDelete: (attachmentId: string) => void;
  deleting: boolean;
  /** Locator root. Every control below hangs off it. */
  testId: string;
  /** What this owner's empty list says — a case's sentence is not a page's. */
  emptyBody: string;
}) {
  const input = useRef<HTMLInputElement>(null);
  const [refused, setRefused] = useState<string | null>(null);

  function choose(file: File | undefined) {
    if (!file) return;
    const refusal = uploadRefusal(file);
    if (refusal) {
      setRefused(refusal);
      return;
    }
    setRefused(null);
    onAttach(file);
  }

  return (
    <Panel
      title="Attachments"
      testId={testId}
      actions={
        <PermissionGate projectId={projectId} permission={permission}>
          {({ allowed, reason }) => (
            <Tooltip title={allowed ? '' : reason}>
              {/*
                The span is not decoration: MUI puts `pointer-events: none` on a disabled control,
                so a Tooltip wrapped directly around one greys out and explains nothing.
                `PermissionGate`'s own header records this; it is re-made here because this is a
                render-prop call site and the wrapper is the caller's job.
              */}
              <span>
                <Button
                  size="small"
                  startIcon={<UploadFileIcon />}
                  disabled={!allowed || attaching}
                  onClick={() => input.current?.click()}
                  data-testid={`${testId}.Attach`}
                >
                  {attaching ? 'Uploading…' : 'Attach a file'}
                </Button>
              </span>
            </Tooltip>
          )}
        </PermissionGate>
      }
    >
      <input
        ref={input}
        type="file"
        hidden
        data-testid={`${testId}.Input`}
        onChange={(event) => {
          choose(event.target.files?.[0]);
          // Cleared so picking the SAME file twice fires `change` again — without it a failed
          // upload cannot be retried by re-picking the identical file, which is the first thing
          // anyone tries. §67's finding, still true here.
          event.target.value = '';
        }}
      />

      {refused && (
        <Alert severity="warning" sx={{ mb: 2 }} data-testid={`${testId}.Refused`}>
          {refused}
        </Alert>
      )}
      {attachError != null && (
        <Alert severity="error" sx={{ mb: 2 }} data-testid={`${testId}.Error`}>
          {describeError(attachError).body}
        </Alert>
      )}

      {attachments.length === 0 ? (
        <Typography variant="body2" sx={{ color: 'text.secondary' }} data-testid={`${testId}.Empty`}>
          {emptyBody}
        </Typography>
      ) : (
        <Stack sx={{ gap: 1 }} data-testid={`${testId}.List`}>
          {/*
            Sorted newest first, and sorted at all because the server's order is not part of the
            contract — an unsorted list re-orders itself on a refetch and the row somebody was
            about to press moves. `id` breaks the tie so two files uploaded in the same second do
            not swap places between renders (`ui-rules`' deterministic-sort rule).
          */}
          {[...attachments]
            .sort((a, b) => b.uploadedAt.localeCompare(a.uploadedAt) || a.id.localeCompare(b.id))
            .map((item) => (
              <AttachmentRow
                key={item.id}
                projectId={projectId}
                permission={permission}
                item={item}
                onDelete={() => onDelete(item.id)}
                deleting={deleting}
                testId={testId}
              />
            ))}
        </Stack>
      )}
    </Panel>
  );
}

function AttachmentRow({
  projectId,
  permission,
  item,
  onDelete,
  deleting,
  testId,
}: {
  projectId: string;
  permission: PermissionKey;
  item: Attachment;
  onDelete: () => void;
  deleting: boolean;
  testId: string;
}) {
  return (
    <Stack sx={{ gap: 0.5 }} data-testid={`${testId}.Row`}>
      <Stack direction="row" sx={{ alignItems: 'center', gap: 1.5 }}>
        <Stack sx={{ minWidth: 0, flex: 1 }}>
          <FileDownloadLink
            url={item.url}
            filename={item.filename}
            testId={`${testId}.Download`}
            sx={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}
          />
          <Typography variant="caption" sx={{ color: 'text.secondary' }}>
            {formatBytes(item.sizeBytes)}
            {item.uploadedBy ? ` · ${item.uploadedBy.displayName}` : ''}
            {` · ${formatDateTime(item.uploadedAt)}`}
          </Typography>
        </Stack>

        {/*
          No `authorId`. See the module header: this gate asks whether the caller may edit the
          thing the file is attached to, and nothing about who attached it.
        */}
        <PermissionGate projectId={projectId} permission={permission}>
          {({ allowed, reason }) => (
            <Tooltip title={allowed ? 'Remove' : reason}>
              <span>
                <IconButton
                  size="small"
                  disabled={!allowed || deleting}
                  onClick={onDelete}
                  aria-label={`Remove ${item.filename}`}
                  data-testid={`${testId}.Delete`}
                >
                  <DeleteOutlineIcon fontSize="small" />
                </IconButton>
              </span>
            </Tooltip>
          )}
        </PermissionGate>
      </Stack>
    </Stack>
  );
}
