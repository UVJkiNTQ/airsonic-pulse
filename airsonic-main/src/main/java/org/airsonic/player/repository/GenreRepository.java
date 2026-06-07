package org.airsonic.player.repository;

import org.airsonic.player.domain.Genre;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface GenreRepository extends JpaRepository<Genre, String> {

    /**
     * Upsert a single genre row using MariaDB ON DUPLICATE KEY UPDATE.
     * Avoids the entity-lifecycle issues (persist vs merge, Persistable, detached
     * entities) that cause "Duplicate entry" PK violations during scan.
     */
    @Modifying
    @Query(value = "INSERT INTO genre (name, song_count, album_count) " +
                   "VALUES (:#{#genre.name}, :#{#genre.songCount}, :#{#genre.albumCount}) " +
                   "ON DUPLICATE KEY UPDATE song_count = VALUES(song_count), album_count = VALUES(album_count)",
           nativeQuery = true)
    void upsert(@Param("genre") Genre genre);
}
