import { useState } from 'react';
import { useAddClientContact, useRemoveClientContact } from '../../api/queries';
import type { ClientContact } from '../../api/types';
import { Panel } from '../../components/PageHeader';
import { Field } from '../../components/kit/Field';
import { Table, TableBody, TableCell, TableHead, TableHeaderCell, TableRow } from '../../components/kit';
import { Button, Input } from '../../components/ui';

const TEST_ID = 'Client.Contacts';

/**
 * A client's contacts — *"a record of a person to talk to"*, and the panel says so.
 *
 * <h2>The sentence under the table is not decoration</h2>
 *
 * *"None of them can sign in, and adding one creates no account"* is `client.html`'s own line, and
 * it is the one thing a reader is most likely to assume wrongly: every other list of people in this
 * product **is** a list of accounts. Scope D1 rests on that difference — a client never logs in, and
 * if that ever changes D1 is reopened rather than extended.
 *
 * <h2>Inline add rather than a dialog</h2>
 *
 * `ui-rules` §7's fourth question: a repeated, low-ceremony task on a screen whose context the
 * reader needs. Four short fields under the table beats a modal that hides the contacts already
 * there — which is exactly what somebody adding a second one is checking against.
 */
export function ClientContactsPanel({
  clientId,
  contacts,
  canEdit,
}: {
  clientId: string;
  contacts: readonly ClientContact[];
  canEdit: boolean;
}) {
  const [adding, setAdding] = useState(false);
  const [name, setName] = useState('');
  const [role, setRole] = useState('');
  const [email, setEmail] = useState('');
  const [phone, setPhone] = useState('');

  const add = useAddClientContact(clientId);
  const remove = useRemoveClientContact(clientId);

  const reset = () => {
    setName('');
    setRole('');
    setEmail('');
    setPhone('');
    setAdding(false);
  };

  const submit = async () => {
    if (name.trim() === '') return;
    await add.mutateAsync({
      name: name.trim(),
      role: role.trim() || null,
      email: email.trim() || null,
      phone: phone.trim() || null,
    });
    reset();
  };

  return (
    <Panel
      title={`Contacts (${contacts.length})`}
      testId={TEST_ID}
      actions={
        canEdit && !adding ?
          <Button size="sm" onClick={() => setAdding(true)} data-testid={`${TEST_ID}.Add`}>
            Add contact
          </Button>
        : undefined
      }
    >
      {contacts.length === 0 && !adding ?
        <p className="m-0 px-3.5 py-3 text-[12.5px] text-ink-3" data-testid={`${TEST_ID}.None`}>
          Nobody recorded yet. A contact is a name and a way to reach them — never a login.
        </p>
      : <Table data-testid={`${TEST_ID}.Table`}>
          <TableHead>
            <TableRow>
              <TableHeaderCell>Name</TableHeaderCell>
              <TableHeaderCell>Role</TableHeaderCell>
              <TableHeaderCell>Email</TableHeaderCell>
              <TableHeaderCell>Phone</TableHeaderCell>
              {canEdit ? <TableHeaderCell>{''}</TableHeaderCell> : null}
            </TableRow>
          </TableHead>
          <TableBody>
            {contacts.map((contact) => (
              <TableRow key={contact.id} data-testid={`${TEST_ID}.Row.${contact.id}`}>
                <TableCell>{contact.name}</TableCell>
                <TableCell>{contact.role ?? '—'}</TableCell>
                <TableCell>{contact.email ?? '—'}</TableCell>
                <TableCell>{contact.phone ?? '—'}</TableCell>
                {canEdit ?
                  <TableCell>
                    <Button
                      size="sm"
                      variant="quiet"
                      disabled={remove.isPending}
                      onClick={() => void remove.mutateAsync(contact.id)}
                      data-testid={`${TEST_ID}.Remove.${contact.id}`}
                    >
                      Remove
                    </Button>
                  </TableCell>
                : null}
              </TableRow>
            ))}
          </TableBody>
        </Table>
      }

      {adding ?
        <div className="flex flex-col gap-3 border-t border-line px-3.5 py-3" data-testid={`${TEST_ID}.Form`}>
          <Field label="Name" htmlFor="contact-name" help="The only thing this needs.">
            <Input
              id="contact-name"
              value={name}
              onChange={(event) => setName(event.target.value)}
              data-testid={`${TEST_ID}.Name`}
            />
          </Field>
          <div className="grid gap-3 sm:grid-cols-3">
            <Field label="Role" htmlFor="contact-role">
              <Input
                id="contact-role"
                value={role}
                onChange={(event) => setRole(event.target.value)}
                data-testid={`${TEST_ID}.Role`}
              />
            </Field>
            <Field label="Email" htmlFor="contact-email">
              <Input
                id="contact-email"
                value={email}
                onChange={(event) => setEmail(event.target.value)}
                data-testid={`${TEST_ID}.Email`}
              />
            </Field>
            <Field label="Phone" htmlFor="contact-phone">
              <Input
                id="contact-phone"
                value={phone}
                onChange={(event) => setPhone(event.target.value)}
                data-testid={`${TEST_ID}.Phone`}
              />
            </Field>
          </div>
          <div className="flex gap-2">
            <Button
              size="sm"
              variant="primary"
              disabled={name.trim() === '' || add.isPending}
              onClick={() => void submit()}
              data-testid={`${TEST_ID}.Save`}
            >
              {add.isPending ? 'Adding…' : 'Add contact'}
            </Button>
            <Button size="sm" onClick={reset} data-testid={`${TEST_ID}.Cancel`}>
              Cancel
            </Button>
          </div>
        </div>
      : null}

      <p
        className="m-0 border-t border-line px-3.5 py-2.5 text-[11.5px] leading-[1.55] text-ink-4"
        data-testid={`${TEST_ID}.NotLogins`}
      >
        A contact is a record of a person to talk to. None of them can sign in, and adding one
        creates no account.
      </p>
    </Panel>
  );
}
