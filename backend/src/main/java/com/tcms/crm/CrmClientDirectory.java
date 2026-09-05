package com.tcms.crm;

import com.tcms.project.ClientDirectory;
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

    CrmClientDirectory(ClientRepository clients) {
        this.clients = clients;
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
}
