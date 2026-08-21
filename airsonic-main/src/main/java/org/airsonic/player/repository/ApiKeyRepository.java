/*
 This file is part of Airsonic.

 Airsonic is free software: you can redistribute it and/or modify
 it under the terms of the GNU General Public License as published by
 the Free Software Foundation, either version 3 of the License, or
 (at your option) any later version.

 Airsonic is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with Airsonic.  If not, see <http://www.gnu.org/licenses/>.

 Copyright 2026 (C) Airsonic Authors
 */
package org.airsonic.player.repository;

import org.airsonic.player.domain.ApiKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface ApiKeyRepository extends JpaRepository<ApiKey, Integer> {

    Optional<ApiKey> findByKeyHash(String keyHash);

    List<ApiKey> findByUsernameOrderByCreatedAsc(String username);

    /**
     * Atomically refresh {@code last_used} when it is null or older than {@code threshold}. The
     * staleness check lives in the WHERE clause, so concurrent auth hits on the same key (e.g. a
     * client library-sync firing many parallel /rest requests) cannot read-modify-write the same
     * row and trip MariaDB's optimistic-lock detection ("Record has changed since last read",
     * error 1020), which otherwise aborts the write at commit time on every contended request.
     */
    @Modifying
    @Transactional
    @Query("UPDATE ApiKey k SET k.lastUsed = :now WHERE k.id = :id AND (k.lastUsed IS NULL OR k.lastUsed < :threshold)")
    int markUsed(@Param("id") Integer id, @Param("now") Instant now, @Param("threshold") Instant threshold);
}
