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

 Copyright 2025 (C) Y.Tory
 */
package org.airsonic.player.service;

import org.airsonic.player.controller.CoverArtController;
import org.airsonic.player.controller.JAXBWriter;
import org.airsonic.player.domain.Album;
import org.airsonic.player.domain.Contributors;
import org.airsonic.player.domain.CoverArt;
import org.airsonic.player.domain.Genres;
import org.airsonic.player.domain.MediaFile;
import org.airsonic.player.domain.Player;
import org.airsonic.player.domain.Playlist;
import org.airsonic.player.util.StringUtil;
import org.springframework.stereotype.Service;
import org.subsonic.restapi.AlbumID3;
import org.subsonic.restapi.ArtistID3;
import org.subsonic.restapi.Child;
import org.subsonic.restapi.Contributor;
import org.subsonic.restapi.DiscTitle;
import org.subsonic.restapi.ItemDate;
import org.subsonic.restapi.ItemGenre;
import org.subsonic.restapi.RecordLabel;
import org.subsonic.restapi.ReplayGain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class JaxbContentService {

    private final JAXBWriter jaxbWriter;
    private final ArtistService artistService;
    private final CoverArtService coverArtService;
    private final PlaylistService playlistService;
    private final AlbumService albumService;
    private final MediaFileService mediaFileService;
    private final MediaFolderService mediaFolderService;
    private final TranscodingService transcodingService;
    private final RatingService ratingService;
    private final SettingsService settingsService;

    JaxbContentService(
            JAXBWriter jaxbWriter,
            ArtistService artistService,
            CoverArtService coverArtService,
            PlaylistService playlistService,
            AlbumService albumService,
            MediaFileService mediaFileService,
            MediaFolderService mediaFolderService,
            TranscodingService transcodingService,
            RatingService ratingService,
            SettingsService settingsService) {
        this.jaxbWriter = jaxbWriter;
        this.artistService = artistService;
        this.coverArtService = coverArtService;
        this.playlistService = playlistService;
        this.albumService = albumService;
        this.mediaFileService = mediaFileService;
        this.mediaFolderService = mediaFolderService;
        this.transcodingService = transcodingService;
        this.ratingService = ratingService;
        this.settingsService = settingsService;
    }

    public <T extends ArtistID3> T createJaxbArtist(T jaxbArtist, org.airsonic.player.domain.Artist artist, String username) {
        jaxbArtist.setId(String.valueOf(artist.getId()));
        jaxbArtist.setName(artist.getName());
        jaxbArtist.setStarred(jaxbWriter.convertDate(artistService.getStarredDate(artist.getId(), username)));
        jaxbArtist.setAlbumCount(artist.getAlbumCount());
        if (!CoverArt.NULL_ART.equals(coverArtService.getArtistArt(artist.getId()))) {
            jaxbArtist.setCoverArt(CoverArtController.ARTIST_COVERART_PREFIX + artist.getId());
        }
        jaxbArtist.setMediaType("artist");
        jaxbArtist.setSortName(artist.getSortName());
        jaxbArtist.setMusicBrainzId(artist.getMusicBrainzArtistId());
        // Ratings key on MediaFile id, so resolve the artist's directory MediaFile (the
        // physical row that carries rating-eligible ids). Virtual artists derived from
        // albumArtist tags have no directory and resolve to null; RatingService treats a
        // null MediaFile as "no rating", so userRating and averageRating come back null
        // and JAXB omits the attributes — matching the #201 (#179) convention on the
        // MediaFile-keyed overload.
        MediaFile artistMediaFile = mediaFileService.getArtistByName(artist.getName(),
                mediaFolderService.getMusicFoldersForUser(username));
        jaxbArtist.setUserRating(ratingService.getRatingForUser(username, artistMediaFile));
        jaxbArtist.setAverageRating(ratingService.getAverageRating(artistMediaFile));
        return jaxbArtist;
    }

    private <T extends ArtistID3> T createJaxbArtist(T jaxbArtist, org.airsonic.player.domain.Artist artist, String username, ArtistRenderContext ctx) {
        jaxbArtist.setId(String.valueOf(artist.getId()));
        jaxbArtist.setName(artist.getName());
        jaxbArtist.setStarred(jaxbWriter.convertDate(ctx.starredDates.get(artist.getId())));
        jaxbArtist.setAlbumCount(artist.getAlbumCount());
        CoverArt artistArt = ctx.coverArtByArtistId.get(artist.getId());
        if (artistArt != null && !CoverArt.NULL_ART.equals(artistArt)) {
            jaxbArtist.setCoverArt(CoverArtController.ARTIST_COVERART_PREFIX + artist.getId());
        }
        jaxbArtist.setMediaType("artist");
        jaxbArtist.setSortName(artist.getSortName());
        jaxbArtist.setMusicBrainzId(artist.getMusicBrainzArtistId());
        MediaFile artistMediaFile = ctx.artistMediaFileByName.get(artist.getName());
        if (artistMediaFile != null) {
            jaxbArtist.setUserRating(ctx.userRatings.get(artistMediaFile.getId()));
            jaxbArtist.setAverageRating(ctx.averageRatings.get(artistMediaFile.getId()));
        }
        return jaxbArtist;
    }

    public org.subsonic.restapi.Artist createJaxbArtist(MediaFile artist, String username) {
        org.subsonic.restapi.Artist result = new org.subsonic.restapi.Artist();
        result.setId(String.valueOf(artist.getId()));
        result.setName(artist.getTitle() != null ? artist.getTitle() : artist.getArtist());
        Instant starred = mediaFileService.getMediaFileStarredDate(artist, username);
        result.setStarred(jaxbWriter.convertDate(starred));
        result.setUserRating(ratingService.getRatingForUser(username, artist));
        result.setAverageRating(ratingService.getAverageRating(artist));
        return result;
    }

    /**
     * Batched renderer for artist LIST endpoints (search3 artist branch, getArtists, starred).
     * Resolves every artist's starred date, cover art and rating-eligible directory MediaFile with a
     * handful of {@code IN} queries instead of ~5 round-trips per artist, then renders each artist
     * identically to {@link #createJaxbArtist(ArtistID3, Artist, String)}.
     */
    public <T extends ArtistID3> List<T> createJaxbArtists(List<org.airsonic.player.domain.Artist> artists, String username, Function<org.airsonic.player.domain.Artist, T> factory) {
        if (artists == null || artists.isEmpty()) {
            return List.of();
        }
        ArtistRenderContext ctx = preloadArtistContext(artists, username);
        return artists.stream()
                .map(a -> createJaxbArtist(factory.apply(a), a, username, ctx))
                .toList();
    }

    private ArtistRenderContext preloadArtistContext(List<org.airsonic.player.domain.Artist> artists, String username) {
        Set<Integer> ids = artists.stream().map(org.airsonic.player.domain.Artist::getId).filter(Objects::nonNull).collect(Collectors.toSet());
        Set<String> names = artists.stream().map(org.airsonic.player.domain.Artist::getName).filter(Objects::nonNull).collect(Collectors.toSet());
        List<org.airsonic.player.domain.MusicFolder> folders = mediaFolderService.getMusicFoldersForUser(username);
        Map<Integer, CoverArt> coverArtByArtistId = coverArtService.getArtistArts(ids);
        Map<String, MediaFile> artistMediaFileByName = mediaFileService.getArtistsByName(names, folders);
        Map<Integer, Integer> userRatings = ratingService.getRatingsForUser(username, artistMediaFileByName.values());
        Map<Integer, Double> averageRatings = ratingService.getAverageRatings(artistMediaFileByName.values());
        return new ArtistRenderContext(artistService.getStarredDates(ids, username), coverArtByArtistId, artistMediaFileByName, userRatings, averageRatings);
    }

    /**
     * Per-page preloaded data used by {@link #createJaxbArtists} to avoid per-artist DB round-trips.
     */
    static final class ArtistRenderContext {
        final Map<Integer, Instant> starredDates;
        final Map<Integer, CoverArt> coverArtByArtistId;
        final Map<String, MediaFile> artistMediaFileByName;
        final Map<Integer, Integer> userRatings;
        final Map<Integer, Double> averageRatings;

        ArtistRenderContext(Map<Integer, Instant> starredDates,
                Map<Integer, CoverArt> coverArtByArtistId,
                Map<String, MediaFile> artistMediaFileByName,
                Map<Integer, Integer> userRatings,
                Map<Integer, Double> averageRatings) {
            this.starredDates = starredDates;
            this.coverArtByArtistId = coverArtByArtistId;
            this.artistMediaFileByName = artistMediaFileByName;
            this.userRatings = userRatings;
            this.averageRatings = averageRatings;
        }
    }

    public <T extends AlbumID3> T createJaxbAlbum(T jaxbAlbum, Album album, String username) {
        // Load album tracks so discTitles and cover art can be populated.
        // The single getSongsForAlbum call is shared for both discTitles and the
        // cover art fallback in the 4-arg overload.
        List<MediaFile> albumTracks = mediaFileService.getSongsForAlbum(album.getArtist(), album.getName());
        return createJaxbAlbum(jaxbAlbum, album, username, albumTracks);
    }

    public <T extends AlbumID3> T createJaxbAlbum(T jaxbAlbum, Album album, String username, List<MediaFile> albumTracks) {
        return createJaxbAlbum(jaxbAlbum, album, username, albumTracks, null);
    }

    private <T extends AlbumID3> T createJaxbAlbum(T jaxbAlbum, Album album, String username, List<MediaFile> albumTracks, AlbumRenderContext ctx) {
        jaxbAlbum.setId(String.valueOf(album.getId()));
        jaxbAlbum.setName(album.getName());
        if (album.getArtist() != null) {
            jaxbAlbum.setArtist(album.getArtist());
            org.airsonic.player.domain.Artist artist = ctx != null ? ctx.artistsByName.get(album.getArtist()) : artistService.getArtist(album.getArtist());
            if (artist != null) {
                jaxbAlbum.setArtistId(String.valueOf(artist.getId()));
            }
        }
        CoverArt albumArt = ctx != null ? ctx.albumArtByAlbumId.get(album.getId()) : coverArtService.getAlbumArt(album.getId());
        if (albumArt != null && !CoverArt.NULL_ART.equals(albumArt)) {
            jaxbAlbum.setCoverArt(CoverArtController.ALBUM_COVERART_PREFIX + album.getId());
        } else if (albumTracks != null && !albumTracks.isEmpty()) {
            // Fallback: use first track's parent directory cover art (same logic as songs)
            // This works even when cover_art table lacks ALBUM entries (e.g., after fresh scan)
            MediaFile firstTrack = albumTracks.get(0);
            MediaFile parent = ctx != null ? ctx.firstTrackParents.get(firstTrack) : mediaFileService.getParentOf(firstTrack);
            String coverArt = findCoverArt(firstTrack, parent, ctx != null ? ctx.coverArtByMediaFileId : null);
            if (coverArt != null) {
                jaxbAlbum.setCoverArt(coverArt);
            }
        }
        jaxbAlbum.setSongCount(album.getSongCount());
        jaxbAlbum.setDuration((int) Math.round(album.getDuration()));
        jaxbAlbum.setCreated(jaxbWriter.convertDate(album.getCreated()));
        jaxbAlbum.setStarred(jaxbWriter.convertDate(ctx != null ? ctx.starredDates.get(album.getId()) : albumService.getAlbumStarredDate(album.getId(), username)));
        jaxbAlbum.setPlayCount((long) album.getPlayCount());
        jaxbAlbum.setYear(album.getYear());
        jaxbAlbum.setGenre(album.getGenre());
        jaxbAlbum.setPlayed(jaxbWriter.convertDate(album.getLastPlayed()));
        jaxbAlbum.setMusicBrainzId(album.getMusicBrainzReleaseId());
        jaxbAlbum.setDisplayArtist(album.getArtist());
        jaxbAlbum.setSortName(album.getSortName());
        jaxbAlbum.setIsCompilation(album.getCompilation());
        jaxbAlbum.setOriginalReleaseDate(parseItemDate(album.getOriginalReleaseDate()));
        jaxbAlbum.setReleaseDate(parseItemDate(album.getReleaseDate()));
        for (String releaseType : splitMultiValue(album.getReleaseTypes())) {
            jaxbAlbum.getReleaseTypes().add(releaseType);
        }
        for (String labelName : splitMultiValue(album.getRecordLabels())) {
            RecordLabel label = new RecordLabel();
            label.setName(labelName);
            jaxbAlbum.getRecordLabels().add(label);
        }
        for (DiscTitle discTitle : buildDiscTitles(albumTracks)) {
            jaxbAlbum.getDiscTitles().add(discTitle);
        }
        jaxbAlbum.setReplayGain(buildReplayGain(album));
        return jaxbAlbum;
    }

    /**
     * Batched renderer for album LIST endpoints (search3, getAlbumList2, starred, artist albums). Resolves every
     * album's tracks in ONE query via {@link MediaFileService#getSongsForAlbums} and feeds them into the 4-arg
     * {@link #createJaxbAlbum(AlbumID3, Album, String, List)}, so discTitles and the cover-art fallback are preserved
     * exactly as the per-album 3-arg overload would produce them — but without an N+1 query per album. Albums with no
     * tracks (null lookup) behave identically to an empty list in the 4-arg overload.
     */
    public <T extends AlbumID3> List<T> createJaxbAlbums(List<Album> albums, String username, Function<Album, T> factory) {
        if (albums == null || albums.isEmpty()) {
            return List.of();
        }
        AlbumRenderContext ctx = preloadAlbumContext(albums, username);
        return albums.stream()
                .map(a -> createJaxbAlbum(factory.apply(a), a, username, ctx.tracksByAlbum.get(MediaFileService.AlbumKey.of(a)), ctx))
                .toList();
    }

    /**
     * Variant of {@link #createJaxbAlbums(List, String, Function)} that also populates each album's {@code <song>}
     * entries (used by /getArtist so a client can fetch an artist's whole discography track list in a single request).
     * Songs reuse the single batched track lookup from {@link #preloadAlbumContext} plus ONE shared child-render
     * context, so the entire artist renders in a handful of queries regardless of album count. List endpoints that do
     * not need songs keep using the 3-arg overload.
     */
    public <T extends AlbumID3> List<T> createJaxbAlbums(Player player, List<Album> albums, String username, Function<Album, T> factory) {
        if (albums == null || albums.isEmpty()) {
            return List.of();
        }
        AlbumRenderContext ctx = preloadAlbumContext(albums, username);
        List<MediaFile> allTracks = albums.stream()
                .map(a -> ctx.tracksByAlbum.get(MediaFileService.AlbumKey.of(a)))
                .filter(Objects::nonNull)
                .flatMap(List::stream)
                .toList();
        ChildRenderContext childCtx = allTracks.isEmpty() ? null : preloadChildContext(allTracks, username);
        return albums.stream()
                .map(a -> {
                    T jaxbAlbum = createJaxbAlbum(factory.apply(a), a, username, ctx.tracksByAlbum.get(MediaFileService.AlbumKey.of(a)), ctx);
                    if (player != null) {
                        List<MediaFile> albumTracks = ctx.tracksByAlbum.get(MediaFileService.AlbumKey.of(a));
                        if (albumTracks != null) {
                            for (MediaFile mf : albumTracks) {
                                jaxbAlbum.getSong().add(createJaxbChild(new Child(), player, mf, username, childCtx));
                            }
                        }
                    }
                    return jaxbAlbum;
                })
                .toList();
    }

    private AlbumRenderContext preloadAlbumContext(List<Album> albums, String username) {
        Map<MediaFileService.AlbumKey, List<MediaFile>> tracksByAlbum = mediaFileService.getSongsForAlbums(albums);
        Set<Integer> ids = albums.stream().map(Album::getId).filter(Objects::nonNull).collect(Collectors.toSet());
        Set<String> artistNames = albums.stream().map(Album::getArtist).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Integer, CoverArt> albumArtByAlbumId = coverArtService.getAlbumArts(ids);
        Map<String, org.airsonic.player.domain.Artist> artistsByName = artistService.getArtistsByName(artistNames);
        Map<Integer, Instant> starredDates = albumService.getAlbumStarredDates(ids, username);
        // The cover_art table rarely has ALBUM rows (fresh scans only write MEDIA_FILE arts), so nearly
        // every album falls through to the first-track parent fallback. Batch that parent lookup once.
        List<MediaFile> firstTracks = tracksByAlbum.values().stream()
                .filter(list -> list != null && !list.isEmpty())
                .map(list -> list.get(0))
                .toList();
        Map<MediaFile, MediaFile> parents = mediaFileService.getParentsOf(firstTracks);
        Set<Integer> parentIds = parents.values().stream().map(MediaFile::getId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Integer, CoverArt> coverArtByMediaFileId = coverArtService.getMediaFileArts(parentIds);
        return new AlbumRenderContext(tracksByAlbum, albumArtByAlbumId, artistsByName, starredDates, parents, coverArtByMediaFileId);
    }

    /**
     * Per-page preloaded data used by {@link #createJaxbAlbums} to avoid per-album DB round-trips
     * (artist, album cover art, starred date, and the first-track parent cover-art fallback).
     */
    static final class AlbumRenderContext {
        final Map<MediaFileService.AlbumKey, List<MediaFile>> tracksByAlbum;
        final Map<Integer, CoverArt> albumArtByAlbumId;
        final Map<String, org.airsonic.player.domain.Artist> artistsByName;
        final Map<Integer, Instant> starredDates;
        final Map<MediaFile, MediaFile> firstTrackParents;
        final Map<Integer, CoverArt> coverArtByMediaFileId;

        AlbumRenderContext(Map<MediaFileService.AlbumKey, List<MediaFile>> tracksByAlbum,
                Map<Integer, CoverArt> albumArtByAlbumId,
                Map<String, org.airsonic.player.domain.Artist> artistsByName,
                Map<Integer, Instant> starredDates,
                Map<MediaFile, MediaFile> firstTrackParents,
                Map<Integer, CoverArt> coverArtByMediaFileId) {
            this.tracksByAlbum = tracksByAlbum;
            this.albumArtByAlbumId = albumArtByAlbumId;
            this.artistsByName = artistsByName;
            this.starredDates = starredDates;
            this.firstTrackParents = firstTrackParents;
            this.coverArtByMediaFileId = coverArtByMediaFileId;
        }
    }

    /**
     * Builds the album's disc-title list by grouping its tracks by disc number and taking the
     * first non-blank {@code disc_subtitle} per disc, sorted by disc ascending. Discs without
     * any subtitled track are skipped. Returns an empty list when no tracks are provided or
     * none carry subtitles — callers naturally omit the {@code <discTitles>} elements then.
     * <p>
     * Called only by the 4-arg {@code createJaxbAlbum} overload, so list endpoints never pay
     * for the grouping (they pass {@code null} via the 3-arg overload).
     */
    static List<DiscTitle> buildDiscTitles(List<MediaFile> albumTracks) {
        if (albumTracks == null || albumTracks.isEmpty()) {
            return List.of();
        }
        Map<Integer, String> subtitleByDisc = new TreeMap<>();
        for (MediaFile track : albumTracks) {
            Integer disc = track.getDiscNumber();
            if (disc == null || subtitleByDisc.containsKey(disc)) {
                continue;
            }
            String subtitle = track.getDiscSubtitle();
            if (subtitle == null) {
                continue;
            }
            String trimmed = subtitle.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            subtitleByDisc.put(disc, trimmed);
        }
        List<DiscTitle> result = new ArrayList<>(subtitleByDisc.size());
        for (Map.Entry<Integer, String> entry : subtitleByDisc.entrySet()) {
            DiscTitle dt = new DiscTitle();
            dt.setDisc(entry.getKey());
            dt.setTitle(entry.getValue());
            result.add(dt);
        }
        return result;
    }

    /**
     * Internal delimiter for packed multi-value AlbumID3 columns (releaseTypes, recordLabels).
     * Must match the constant of the same name in {@link MediaFileService}.
     */
    static final String MULTI_VALUE_DELIMITER = "\n";

    /**
     * Splits a packed multi-value column back into a list of trimmed, non-blank values for
     * response emission. Returns an empty list (not null) so callers can iterate without
     * null-checking; an empty list naturally omits the repeated element entirely.
     */
    static List<String> splitMultiValue(String packed) {
        if (packed == null || packed.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String token : packed.split(MULTI_VALUE_DELIMITER, -1)) {
            String trimmed = token.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    private static final java.util.regex.Pattern ITEM_DATE_PATTERN =
            java.util.regex.Pattern.compile("^(\\d{4})(?:-(\\d{1,2})(?:-(\\d{1,2}))?)?$");

    /**
     * Parses a raw tag date string into an {@link ItemDate}. Accepts {@code YYYY},
     * {@code YYYY-MM}, or {@code YYYY-MM-DD}; tolerates a trailing time component by taking
     * only the date part before {@code T} or whitespace. Returns {@code null} when the input
     * is blank or doesn't match the expected shape, so the element is omitted from the
     * response rather than emitted with an empty/garbage date.
     */
    static ItemDate parseItemDate(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return null;
        }
        // Strip an optional trailing time component: "2003-10-12T00:00:00" -> "2003-10-12".
        int splitAt = s.length();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == 'T' || c == ' ') {
                splitAt = i;
                break;
            }
        }
        s = s.substring(0, splitAt);
        java.util.regex.Matcher m = ITEM_DATE_PATTERN.matcher(s);
        if (!m.matches()) {
            return null;
        }
        ItemDate date = new ItemDate();
        date.setYear(Integer.valueOf(m.group(1)));
        if (m.group(2) != null) {
            date.setMonth(Integer.valueOf(m.group(2)));
        }
        if (m.group(3) != null) {
            date.setDay(Integer.valueOf(m.group(3)));
        }
        return date;
    }

    public <T extends org.subsonic.restapi.Playlist> T createJaxbPlaylist(T jaxbPlaylist, Playlist playlist) {
        jaxbPlaylist.setId(String.valueOf(playlist.getId()));
        jaxbPlaylist.setName(playlist.getName());
        jaxbPlaylist.setComment(playlist.getComment());
        jaxbPlaylist.setOwner(playlist.getUsername());
        jaxbPlaylist.setPublic(playlist.getShared());
        jaxbPlaylist.setSongCount(playlist.getFileCount());
        jaxbPlaylist.setDuration((int) Math.round(playlist.getDuration()));
        jaxbPlaylist.setCreated(jaxbWriter.convertDate(playlist.getCreated()));
        jaxbPlaylist.setChanged(jaxbWriter.convertDate(playlist.getChanged()));
        jaxbPlaylist.setCoverArt(CoverArtController.PLAYLIST_COVERART_PREFIX + playlist.getId());

        for (String username : playlistService.getPlaylistUsers(playlist.getId())) {
            jaxbPlaylist.getAllowedUser().add(username);
        }
        return jaxbPlaylist;
    }

    public Child createJaxbChild(Player player, MediaFile mediaFile, String username) {
        return createJaxbChild(new Child(), player, mediaFile, username);
    }

    public <T extends Child> T createJaxbChild(T child, Player player, MediaFile mediaFile, String username) {
        return createJaxbChild(child, player, mediaFile, username, null);
    }

    /**
     * Per-file rendering context preloaded once per page so list endpoints (search3, getAlbum, starred,
     * now-playing, …) avoid the per-child N+1 round-trips (parent, cover art, starred date, ratings,
     * album, artist). When {@code ctx} is {@code null} every lookup falls back to the original
     * per-file service call, preserving behavior for single-item call sites.
     */
    private <T extends Child> T createJaxbChild(T child, Player player, MediaFile mediaFile, String username, ChildRenderContext ctx) {
        MediaFile parent = ctx != null ? ctx.parents.get(mediaFile) : mediaFileService.getParentOf(mediaFile);
        child.setId(String.valueOf(mediaFile.getId()));
        try {
            if (Objects.nonNull(parent) && !mediaFileService.isRoot(parent)) {
                child.setParent(String.valueOf(parent.getId()));
            }
        } catch (SecurityException x) {
            // Ignored.
        }
        child.setTitle(mediaFile.getName());
        child.setSortName(mediaFile.getSortName());
        child.setAlbum(mediaFile.getAlbumName());
        child.setArtist(mediaFile.getArtist());
        child.setIsDir(mediaFile.isDirectory());
        child.setCoverArt(findCoverArt(mediaFile, parent, ctx));
        child.setYear(mediaFile.getYear());
        child.setBpm(mediaFile.getBpm());
        child.setGenre(mediaFile.getGenre());
        for (String genreName : Genres.split(mediaFile.getGenres(), settingsService.getGenreSeparators())) {
            ItemGenre itemGenre = new ItemGenre();
            itemGenre.setName(genreName);
            child.getGenres().add(itemGenre);
        }
        child.setCreated(jaxbWriter.convertDate(mediaFile.getCreated()));
        child.setStarred(jaxbWriter.convertDate(ctx != null ? ctx.starredDates.get(mediaFile.getId()) : mediaFileService.getMediaFileStarredDate(mediaFile, username)));
        child.setUserRating(ctx != null ? ctx.userRatings.get(mediaFile.getId()) : ratingService.getRatingForUser(username, mediaFile));
        child.setAverageRating(ctx != null ? ctx.averageRatings.get(mediaFile.getId()) : ratingService.getAverageRating(mediaFile));
        child.setPlayCount((long) mediaFile.getPlayCount());
        child.setPlayed(jaxbWriter.convertDate(mediaFile.getLastPlayed()));
        child.setMusicBrainzId(mediaFile.getMusicBrainzRecordingId());
        child.setDisplayArtist(mediaFile.getArtist());
        child.setDisplayAlbumArtist(mediaFile.getAlbumArtist());
        child.setReplayGain(buildReplayGain(mediaFile));
        for (Contributor contributor : buildContributors(mediaFile, ctx)) {
            child.getContributors().add(contributor);
        }
        if (mediaFile.getMediaType() != null) {
            child.setMediaType(mediaFile.getMediaType().name().toLowerCase());
        }

        if (mediaFile.isFile()) {
            Double mediaFileDuration = mediaFile.getDuration();
            child.setDuration((int) Math.round(mediaFileDuration == null ? 0 : mediaFileDuration));
            child.setBitRate(mediaFile.getBitRate());
            child.setTrack(mediaFile.getTrackNumber());
            child.setDiscNumber(mediaFile.getDiscNumber());
            child.setSize(mediaFile.getFileSize());
            String suffix = mediaFile.getFormat();
            child.setSuffix(suffix);
            child.setContentType(StringUtil.getMimeType(suffix));
            child.setIsVideo(mediaFile.isVideo());
            child.setPath(mediaFile.getPath());

            Album album = ctx != null ? ctx.albumsByKey.get(MediaFileService.AlbumKey.of(mediaFile)) : albumService.getAlbumByMediaFile(mediaFile);

            if (album != null) {
                child.setAlbumId(String.valueOf(album.getId()));
            }
            org.airsonic.player.domain.Artist artist = ctx != null ? ctx.artistsByName.get(mediaFile.getArtist()) : artistService.getArtist(mediaFile.getArtist());
            if (artist != null) {
                child.setArtistId(String.valueOf(artist.getId()));
            }
            switch (mediaFile.getMediaType()) {
                case MUSIC -> child.setType(org.subsonic.restapi.MediaType.MUSIC);
                case PODCAST -> child.setType(org.subsonic.restapi.MediaType.PODCAST);
                case AUDIOBOOK -> child.setType(org.subsonic.restapi.MediaType.AUDIOBOOK);
                case VIDEO -> {
                    child.setType(org.subsonic.restapi.MediaType.VIDEO);
                    child.setOriginalWidth(mediaFile.getWidth());
                    child.setOriginalHeight(mediaFile.getHeight());
                }
                default -> { }
            }

            if (transcodingService.isTranscodingRequired(mediaFile, player)) {
                String transcodedSuffix = transcodingService.getSuffix(player, mediaFile, null);
                child.setTranscodedSuffix(transcodedSuffix);
                child.setTranscodedContentType(StringUtil.getMimeType(transcodedSuffix));
            }
        }
        return child;
    }

    /**
     * Batched renderer for song/directory LIST endpoints (search3 song branch, getRandomSongs, starred,
     * now-playing, …). Resolves every child's parent, cover art, starred date, ratings, album and artist
     * with a handful of {@code IN} queries (instead of ~7 round-trips per child), then renders each child
     * identically to {@link #createJaxbChild(Player, MediaFile, String)}.
     */
    public <T extends Child> List<T> createJaxbChildren(Player player, List<MediaFile> mediaFiles, String username, Function<MediaFile, T> factory) {
        if (mediaFiles == null || mediaFiles.isEmpty()) {
            return List.of();
        }
        ChildRenderContext ctx = preloadChildContext(mediaFiles, username);
        return mediaFiles.stream()
                .map(mf -> createJaxbChild(factory.apply(mf), player, mf, username, ctx))
                .toList();
    }

    private ChildRenderContext preloadChildContext(List<MediaFile> mediaFiles, String username) {
        Map<MediaFile, MediaFile> parents = mediaFileService.getParentsOf(mediaFiles);
        Set<Integer> dirIds = mediaFiles.stream()
                .map(mf -> mf.isDirectory() ? mf : parents.get(mf))
                .filter(Objects::nonNull)
                .map(MediaFile::getId)
                .collect(Collectors.toSet());
        Map<Integer, CoverArt> coverArtByMediaFileId = coverArtService.getMediaFileArts(dirIds);
        Map<Integer, Instant> starredDates = mediaFileService.getMediaFileStarredDates(mediaFiles, username);
        Map<Integer, Integer> userRatings = ratingService.getRatingsForUser(username, mediaFiles);
        Map<Integer, Double> averageRatings = ratingService.getAverageRatings(mediaFiles);
        Map<MediaFileService.AlbumKey, Album> albumsByKey = albumService.getAlbumsByMediaFiles(mediaFiles);
        Map<String, org.airsonic.player.domain.Artist> artistsByName = artistService.getArtistsByName(
                mediaFiles.stream().map(MediaFile::getArtist).filter(Objects::nonNull).collect(Collectors.toSet()));
        return new ChildRenderContext(parents, coverArtByMediaFileId, starredDates, userRatings, averageRatings, albumsByKey, artistsByName);
    }

    /**
     * Per-page preloaded data used by {@link #createJaxbChildren} to avoid per-child DB round-trips.
     */
    static final class ChildRenderContext {
        final Map<MediaFile, MediaFile> parents;
        final Map<Integer, CoverArt> coverArtByMediaFileId;
        final Map<Integer, Instant> starredDates;
        final Map<Integer, Integer> userRatings;
        final Map<Integer, Double> averageRatings;
        final Map<MediaFileService.AlbumKey, Album> albumsByKey;
        final Map<String, org.airsonic.player.domain.Artist> artistsByName;

        ChildRenderContext(Map<MediaFile, MediaFile> parents,
                Map<Integer, CoverArt> coverArtByMediaFileId,
                Map<Integer, Instant> starredDates,
                Map<Integer, Integer> userRatings,
                Map<Integer, Double> averageRatings,
                Map<MediaFileService.AlbumKey, Album> albumsByKey,
                Map<String, org.airsonic.player.domain.Artist> artistsByName) {
            this.parents = parents;
            this.coverArtByMediaFileId = coverArtByMediaFileId;
            this.starredDates = starredDates;
            this.userRatings = userRatings;
            this.averageRatings = averageRatings;
            this.albumsByKey = albumsByKey;
            this.artistsByName = artistsByName;
        }
    }

    List<Contributor> buildContributors(MediaFile mediaFile) {
        return buildContributors(mediaFile, null);
    }

    List<Contributor> buildContributors(MediaFile mediaFile, ChildRenderContext ctx) {
        List<org.airsonic.player.domain.Contributor> records = Contributors.split(mediaFile.getContributors());
        if (records.isEmpty()) {
            return List.of();
        }
        List<Contributor> result = new ArrayList<>(records.size());
        for (org.airsonic.player.domain.Contributor record : records) {
            Contributor jaxb = new Contributor();
            jaxb.setRole(record.role());
            jaxb.setSubRole(record.subRole());
            jaxb.setArtist(createJaxbArtistByName(record.name(), ctx));
            result.add(jaxb);
        }
        return result;
    }

    ArtistID3 createJaxbArtistByName(String name) {
        return createJaxbArtistByName(name, null);
    }

    ArtistID3 createJaxbArtistByName(String name, ChildRenderContext ctx) {
        org.airsonic.player.domain.Artist artist = ctx != null ? ctx.artistsByName.get(name) : artistService.getArtist(name);
        if (artist != null) {
            ArtistID3 jaxb = new ArtistID3();
            jaxb.setId(String.valueOf(artist.getId()));
            jaxb.setName(artist.getName());
            return jaxb;
        }
        // Uncatalogued tag-derived contributor: no Airsonic Artist record matches this name,
        // so there is no resolvable id under the local XSD's required-id rule. Emit a "" id
        // sentinel with the raw tag name; a stable synthetic id is a possible future refinement
        // if clients struggle with the empty value.
        ArtistID3 jaxb = new ArtistID3();
        jaxb.setId("");
        jaxb.setName(name);
        jaxb.setAlbumCount(0);
        return jaxb;
    }

    private ReplayGain buildReplayGain(MediaFile mediaFile) {
        Double trackGain = mediaFile.getReplayGainTrackGain();
        Double albumGain = mediaFile.getReplayGainAlbumGain();
        Double trackPeak = mediaFile.getReplayGainTrackPeak();
        Double albumPeak = mediaFile.getReplayGainAlbumPeak();
        // baseGain is the Opus OpusHead output_gain (codec-level header gain); per the OpenSubsonic
        // spec it is meaningful only for codecs that define a header gain, so it is emitted for Opus
        // only — never for MP3/FLAC/MP4 — even when those carry other ReplayGain values.
        Double baseGain = "opus".equalsIgnoreCase(mediaFile.getFormat()) ? mediaFile.getReplayGainBaseGain() : null;
        // Operator-supplied fallback emitted on every replayGain element when configured —
        // clients are expected to apply it only when the per-track values are absent. When
        // unset (the default), the attribute is omitted and there is no behavior change.
        Double fallbackGain = settingsService.getReplayGainFallback();
        if (trackGain == null && albumGain == null && trackPeak == null && albumPeak == null
                && baseGain == null && fallbackGain == null) {
            return null;
        }
        ReplayGain replayGain = new ReplayGain();
        replayGain.setTrackGain(trackGain);
        replayGain.setAlbumGain(albumGain);
        replayGain.setTrackPeak(trackPeak);
        replayGain.setAlbumPeak(albumPeak);
        replayGain.setBaseGain(baseGain);
        replayGain.setFallbackGain(fallbackGain);
        return replayGain;
    }

    private ReplayGain buildReplayGain(Album album) {
        // Album-level ReplayGain carries only albumGain / albumPeak (aggregated last-non-null
        // from member tracks during scan) plus the operator-configured fallbackGain. trackGain
        // and trackPeak are per-track and have no album-level meaning, so they're left null —
        // JAXB omits the unset attributes on the reused ReplayGain complexType. baseGain is the
        // per-file Opus header gain and likewise has no album-level meaning, so it is never set.
        Double albumGain = album.getReplayGainAlbumGain();
        Double albumPeak = album.getReplayGainAlbumPeak();
        Double fallbackGain = settingsService.getReplayGainFallback();
        if (albumGain == null && albumPeak == null && fallbackGain == null) {
            return null;
        }
        ReplayGain replayGain = new ReplayGain();
        replayGain.setAlbumGain(albumGain);
        replayGain.setAlbumPeak(albumPeak);
        replayGain.setFallbackGain(fallbackGain);
        return replayGain;
    }

    private String findCoverArt(MediaFile mediaFile, MediaFile parent) {
        return findCoverArt(mediaFile, parent, (Map<Integer, CoverArt>) null);
    }

    private String findCoverArt(MediaFile mediaFile, MediaFile parent, ChildRenderContext ctx) {
        return findCoverArt(mediaFile, parent, ctx != null ? ctx.coverArtByMediaFileId : null);
    }

    private String findCoverArt(MediaFile mediaFile, MediaFile parent, Map<Integer, CoverArt> coverArtByMediaFileId) {
        MediaFile dir = mediaFile.isDirectory() ? mediaFile : parent;
        if (dir != null) {
            CoverArt art = coverArtByMediaFileId != null ? coverArtByMediaFileId.get(dir.getId()) : coverArtService.getMediaFileArt(dir.getId());
            if (art != null && !CoverArt.NULL_ART.equals(art)) {
                return String.valueOf(dir.getId());
            }
        }
        return null;
    }
}
