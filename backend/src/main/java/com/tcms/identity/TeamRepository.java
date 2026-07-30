package com.tcms.identity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Teams in the active company. Company-filtered; team names are unique per company. */
public interface TeamRepository extends JpaRepository<Team, UUID> {

    Optional<Team> findByName(String name);

    List<Team> findByParentTeamId(UUID parentTeamId);
}
