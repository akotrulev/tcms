package com.tcms.crm;

import com.tcms.api.ClientRef;
import com.tcms.project.ClientContactRef;
import com.tcms.project.ClientDirectory;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code crm}'s half of the inversion — see {@link ClientDirectory} for why the declaration lives in
 * {@code project} and the answer lives here.
 *
 * <p>Both methods run inside the <strong>caller's</strong> transaction, so
 * {@code app.current_company} is already on the connection and RLS applies. That is what makes
 * {@link #existsInCompany} a real company check where the foreign key is not one, and it is why
 * {@link #namesOf} may return fewer entries than it was given.
 *
 * <p>Package-private and {@code @Component}: nothing outside this module names the class, and
 * everything outside it names the interface.
 */
@Component
@Transactional(readOnly = true)
class CrmClientDirectory implements ClientDirectory {

    private final ClientRepository clients;
    private final ClientContactRepository contacts;

    CrmClientDirectory(ClientRepository clients, ClientContactRepository contacts) {
        this.clients = clients;
        this.contacts = contacts;
    }

    @Override
    public Map<UUID, String> namesOf(Collection<UUID> clientIds) {
        /*
          The empty case is not an optimisation, it is the common one: every projects list in a
          company that has no clients arrives here with an empty set, and `findByIdIn` with an empty
          collection is a query with an `IN ()` that some drivers refuse and none of them needs.
        */
        if (clientIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> byId = new HashMap<>();
        List<Client> found = clients.findByIdIn(List.copyOf(clientIds));
        for (Client client : found) {
            byId.put(client.getId(), client.getName());
        }
        return Map.copyOf(byId);
    }

    @Override
    public boolean existsInCompany(UUID clientId) {
        return clients.findById(clientId).isPresent();
    }

    /**
     * {@inheritDoc}
     *
     * <p>The same query {@link ClientService#pickableFor} starts from, and deliberately the same
     * one: "which clients is it meaningful to start something new for" is one question, and two
     * statements answering it are two orderings and two status filters to keep in step. What that
     * method adds on top — putting the project's own archived client back so a settings save cannot
     * silently detach it — is a property of the picker and not of the population, which is why the
     * addition is there and not here.
     */
    @Override
    public List<ClientRef> activeClients() {
        List<Client> active = clients.findByStatusOrderByNameAsc(ClientStatus.ACTIVE);
        List<ClientRef> refs = new ArrayList<>(active.size());
        for (Client client : active) {
            refs.add(new ClientRef(client.getId(), client.getName()));
        }
        return List.copyOf(refs);
    }

    /**
     * {@inheritDoc}
     *
     * <p>The client is resolved first, so a client this caller cannot see answers an empty list
     * rather than reaching the contacts at all — RLS covers both reads, and asking twice makes the
     * refusal a fact about the client rather than an accident of the second query returning nothing.
     *
     * <p>The same query and the same ordering {@link ClientService#read} renders the contacts panel
     * from, so an invoice's <em>Bill to</em> and the client page cannot disagree about who is first.
     */
    @Override
    public List<ClientContactRef> contactsOf(UUID clientId) {
        if (clientId == null || clients.findById(clientId).isEmpty()) {
            return List.of();
        }
        List<ClientContact> found = contacts.findByClientIdOrderByNameAsc(clientId);
        List<ClientContactRef> refs = new ArrayList<>(found.size());
        for (ClientContact contact : found) {
            refs.add(new ClientContactRef(
                    contact.getId(), contact.getName(), contact.getRole(),
                    contact.getEmail(), contact.getPhone()));
        }
        return List.copyOf(refs);
    }
}
