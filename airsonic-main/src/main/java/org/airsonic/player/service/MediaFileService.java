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

 Copyright 2023 (C) Y.Tory, Yetangitu
 Copyright 2016 (C) Airsonic Authors
 Based upon Subsonic, Copyright 2009 (C) Sindre Mehus
 */
package org.airsonic.player.service;

import com.google.common.math.DoubleMath;
import com.ibm.icu.text.CharsetDetector;
import com.ibm.icu.text.CharsetMatch;
import org.airsonic.player.ajax.MediaFileEntry;
import org.airsonic.player.controller.HomeController;
import org.airsonic.player.domain.*;
import org.airsonic.player.domain.CoverArt.EntityType;
import org.airsonic.player.domain.MediaFile.MediaType;
import org.airsonic.player.domain.MusicFolder.Type;
import org.airsonic.player.domain.entity.StarredMediaFile;
import org.airsonic.player.i18n.LocaleResolver;
import org.airsonic.player.repository.AlbumRepository;
import org.airsonic.player.repository.GenreRepository;
import org.airsonic.player.repository.MediaFileRepository;
import org.airsonic.player.repository.MediaFileSpecifications;
import org.airsonic.player.repository.MusicFileInfoRepository;
import org.airsonic.player.repository.OffsetBasedPageRequest;
import org.airsonic.player.repository.StarredMediaFileRepository;
import org.airsonic.player.service.cache.MediaFileCache;
import org.airsonic.player.service.cue.CueParser;
import org.airsonic.player.service.metadata.Chapter;
import org.airsonic.player.service.metadata.FFmpegParser;
import org.airsonic.player.service.metadata.JaudiotaggerParser;
import org.airsonic.player.service.metadata.MetaData;
import org.airsonic.player.service.metadata.MetaDataParser;
import org.airsonic.player.service.metadata.MetaDataParserFactory;
import org.airsonic.player.util.FileUtil;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.digitalmediaserver.cuelib.CueSheet;
import org.digitalmediaserver.cuelib.FileData;
import org.digitalmediaserver.cuelib.Position;
import org.digitalmediaserver.cuelib.TrackData;
import org.digitalmediaserver.cuelib.io.FLACReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * Provides services for instantiating and caching media files and cover art.
 *
 * @author Sindre Mehus
 */
@Service
public class MediaFileService {

    private static final Logger LOG = LoggerFactory.getLogger(MediaFileService.class);

    @Autowired
    private SecurityService securityService;
    @Autowired
    private SettingsService settingsService;
    @Autowired
    private MediaFolderService mediaFolderService;
    @Autowired
    private AlbumRepository albumRepository;
    @Autowired
    private MetaDataParserFactory metaDataParserFactory;
    @Autowired
    private CoverArtService coverArtService;
    @Autowired
    private MediaFileRepository mediaFileRepository;
    @Autowired
    private StarredMediaFileRepository starredMediaFileRepository;
    @Autowired
    private LocaleResolver localeResolver;
    @Autowired
    private MessageSource messageSource;
    @Autowired
    private MusicFileInfoRepository musicFileInfoRepository;
    @Autowired
    private GenreRepository genreRepository;
    @Autowired
    private MediaFileCache mediaFileCache;
    @Autowired
    private FFmpegParser ffmpegParser;

    private final double DURATION_EPSILON = 1e-2;

    private final Set<String> CUE_EXTENSIONS = Set.of("cue", "flac");

    private final Map<Integer, Pair<Integer, Instant>> lastPlayed = new ConcurrentHashMap<>();

    private boolean hasBOM(byte[] bom, int bytesRead) {
        return bytesRead == 3 && bom[0] == (byte) 0xEF && bom[1] == (byte) 0xBB && bom[2] == (byte) 0xBF;
    }

    public MediaFile getMediaFile(String pathName) {
        return getMediaFile(Paths.get(pathName));
    }

    public MediaFile getMediaFile(Path fullPath) {
        return getMediaFile(fullPath, settingsService.isFastCacheEnabled());
    }

    // This may be an expensive op
    public MediaFile getMediaFile(Path fullPath, boolean minimizeDiskAccess) {
        return mediaFolderService.getMusicFolderForFile(fullPath, true, true)
            .map(folder -> {
                try {
                    Path relativePath = folder.getPath().relativize(fullPath);
                    return getMediaFile(relativePath, folder, minimizeDiskAccess);
                } catch (Exception e) {
                    // ignore
                    return null;
                }
            }).orElse(null);
    }

    public MediaFile getMediaFile(String relativePath, Integer folderId) {
        return getMediaFile(relativePath, mediaFolderService.getMusicFolderById(folderId));
    }

    public MediaFile getMediaFile(String relativePath, MusicFolder folder) {
        return getMediaFile(Paths.get(relativePath), folder);
    }

    public MediaFile getMediaFile(Path relativePath, MusicFolder folder) {
        return getMediaFile(relativePath, folder, settingsService.isFastCacheEnabled());
    }

    public MediaFile getMediaFile(Path relativePath, MusicFolder folder, boolean minimizeDiskAccess) {
        return getMediaFile(relativePath, folder, MediaFile.NOT_INDEXED, minimizeDiskAccess);
    }

    public MediaFile getMediaFile(Path relativePath, MusicFolder folder, Double startPosition, boolean minimizeDiskAccess) {

        if (folder == null || relativePath == null) {
            return null;
        }
        MediaFile result = mediaFileCache.getMediaFileByPath(relativePath, folder, startPosition);

        // Look in database.
        if (result == null) {
        // Look in database.
            result = mediaFileRepository.findByPathAndFolderAndStartPosition(relativePath.toString(), folder, startPosition)
                .map(file -> checkLastModified(file, minimizeDiskAccess))
                .orElseGet(() -> {
                    if (!Files.exists(folder.getPath().resolve(relativePath))) {
                        return null;
                    }
                    if (startPosition == null || startPosition > MediaFile.NOT_INDEXED) {
                        return null;
                    }
                    // Not found in database, must read from disk.
                    MediaFile mediaFile = createMediaFileByFile(relativePath, folder);
                    // Put in database.
                    if (mediaFile != null) {
                        updateMediaFile(mediaFile);
                    }
                    return mediaFile;
                });

            // cache the result
            mediaFileCache.putMediaFileByPath(relativePath, folder, startPosition, result);
        }
        return result;
    }

    /**
     * Returns the media file for checking last modified.
     *
     * @param id The media file id.
     * @return mediafile for the given id.
     */
    public MediaFile getMediaFile(@Nullable Integer id) {
        return getMediaFile(id, false);
    }

    /**
     * Returns the media file for checking last modified.
     *
     * @param id The media file id.
     * @param ignoreCache Whether to ignore the cache
     * @return mediafile for the given id.
     */
    public MediaFile getMediaFile(@Nullable Integer id, boolean ignoreCache) {
        if (Objects.isNull(id)) return null;
        MediaFile result = ignoreCache ? null : mediaFileCache.getMediaFileById(id);
        if (result == null) {
            result = mediaFileRepository.findById(id).map(mediaFile -> checkLastModified(mediaFile, settingsService.isFastCacheEnabled())).orElse(null);
            mediaFileCache.putMediaFileById(id, result);
        }
        return result;
    }

    public List<MediaFile> getMediaFilesByRelativePath(Path relativePath) {
        return mediaFileRepository.findByPath(relativePath.toString());
    }

    public MediaFile getParentOf(MediaFile mediaFile) {
        return getParentOf(mediaFile, settingsService.isFastCacheEnabled());
    }

    /**
     * Returns the parent of the given media file.
     *
     * @param mediaFile The media file. Must not be {@code null}.
     * @param minimizeDiskAccess Whether to refrain from checking for new or changed files
     * @return The parent of the given media file. May be {@code null}.
     */
    @Nullable
    public MediaFile getParentOf(@Nonnull MediaFile mediaFile, boolean minimizeDiskAccess) {
        if (mediaFile.getParentPath() == null) {
            return null;
        }
        return getMediaFile(Paths.get(mediaFile.getParentPath()), mediaFile.getFolder(), minimizeDiskAccess);
    }

    private boolean needsUpdate(MediaFile mediaFile, boolean minimizeDiskAccess) {
        return !minimizeDiskAccess
                && !mediaFile.isIndexedTrack() // ignore virtual track
                && (mediaFile.getVersion() < MediaFile.VERSION
                    || settingsService.getFullScan()
                    || mediaFile.isChanged()
                    || (mediaFile.hasIndex() && mediaFile.getChanged().truncatedTo(ChronoUnit.MICROS).compareTo(FileUtil.lastModified(mediaFile.getFullIndexPath()).truncatedTo(ChronoUnit.MICROS)) < 0)
                );
    }

    /**
     * Return the media file for checking last modified.
     *
     * @param mediaFile The media file.
     * @return updated media file.
     */
    public MediaFile checkLastModified(MediaFile mediaFile) {
        return checkLastModified(mediaFile, settingsService.isFastCacheEnabled());
    }

    private MediaFile checkLastModified(MediaFile mediaFile, boolean minimizeDiskAccess) {
        MusicFolder folder = mediaFile.getFolder();
        if (!needsUpdate(mediaFile, minimizeDiskAccess)) {
            LOG.debug("Detected unmodified file (id {}, path {} in folder {} ({}))", mediaFile.getId(), mediaFile.getPath(), folder.getId(), folder.getName());
            return mediaFile;
        }
        LOG.debug("Updating database file from disk (id {}, path {} in folder {} ({}))", mediaFile.getId(), mediaFile.getPath(), folder.getId(), folder.getName());
        if (!Files.exists(mediaFile.getFullPath())) {
            mediaFile.setPresent(false);
            mediaFile.setChildrenLastUpdated(Instant.ofEpochMilli(1));
            updateMediaFile(mediaFile);
        } else {
            if (mediaFile.hasIndex()) {
                if (!Files.exists(mediaFile.getFullIndexPath())) {
                    // Delete children that no longer exist on disk
                    mediaFile.setPresent(true);
                    mediaFile.setChildrenLastUpdated(Instant.ofEpochMilli(1));
                    mediaFile.setIndexPath(null);
                    updateMediaFile(mediaFile);
                } else {
                    // update media file
                    Instant mediaChanged = FileUtil.lastModified(mediaFile.getFullPath());
                    Instant cueChanged = FileUtil.lastModified(mediaFile.getFullIndexPath());
                    // update cue tracks
                    try {
                        createIndexedTracks(mediaFile);
                        // update media file
                        mediaFile.setChanged(mediaChanged.compareTo(cueChanged) >= 0 ? mediaChanged : cueChanged);
                        updateMediaFile(mediaFile);
                    } catch (Exception e) {
                        LOG.error("create indexed tracks error: {}", mediaFile.getFullPath(), e);
                    }
                }
            } else {
                mediaFile = updateMediaFileByFile(mediaFile, true);
                updateMediaFile(mediaFile);
            }
        }
        return mediaFile;
    }

    /**
     * Returns all user-visible media files that are children of a given media file
     *
     * visibility depends on the return value of showMediaFile(mediaFile)
     *
     * @param sort               Whether to sort files in the same directory
     * @return All children media files which pass this::showMediaFile
     */
    public List<MediaFile> getVisibleChildrenOf(MediaFile parent, boolean includeDirectories, boolean sort) {
        return new ArrayList<>(getChildrenOf(parent, true, includeDirectories, sort).stream()
                .filter(this::showMediaFile)
                .toList());
    }

    /**
     * Returns all media files that are children of a given media file.
     *
     * @param includeFiles       Whether files should be included in the result.
     * @param includeDirectories Whether directories should be included in the result.
     * @param sort               Whether to sort files in the same directory.
     * @return All children media files.
     */
    public List<MediaFile> getChildrenOf(MediaFile parent, boolean includeFiles, boolean includeDirectories, boolean sort) {
        return getChildrenOf(parent, includeFiles, includeDirectories, sort, settingsService.isFastCacheEnabled());
    }

    /**
     * Returns all media files that are children of a given media file.
     *
     * @param includeFiles       Whether files should be included in the result.
     * @param includeDirectories Whether directories should be included in the result.
     * @param sort               Whether to sort files in the same directory.
     * @param minimizeDiskAccess Whether to refrain from checking for new or changed files
     * @return All children media files.
     */
    public List<MediaFile> getChildrenOf(MediaFile parent, boolean includeFiles, boolean includeDirectories, boolean sort, boolean minimizeDiskAccess) {

        if (!parent.isDirectory()) {
            return Collections.emptyList();
        }

        Stream<MediaFile> resultStream = null;

        // Make sure children are stored and up-to-date in the database.
        try {
            if (!minimizeDiskAccess) {
                resultStream = Optional.ofNullable(updateChildren(parent)).map(x -> x.parallelStream()).orElse(null);
            }

            if (resultStream == null) {
                resultStream = mediaFileRepository.findByFolderAndParentPathAndPresentTrue(parent.getFolder(), parent.getPath(), Sort.by("startPosition")).parallelStream()
                        .map(x -> checkLastModified(x, minimizeDiskAccess))
                        .filter(this::includeMediaFile);
            }

            resultStream = resultStream.filter(x -> (includeDirectories && x.isDirectory()) || (includeFiles && x.isFile()));

            if (sort) {
                resultStream = resultStream.sorted(new MediaFileComparator(settingsService.isSortAlbumsByYear()));
            }

            return resultStream.toList();
        } catch (Exception e) {
            LOG.error("get children of {} failed", parent.getPath(), e);
            return Collections.emptyList();
        }
    }

    /**
     * Returns all media files that are in given folders.
     *
     * @param folders The folders to search.
     * @param count  Maximum number of files to return.
     * @param offset Number of files to skip.
     * @return All media files in the given folders.
     */
    public List<MediaFile> getSongs(List<MusicFolder> folders, int count, int offset) {
        if (CollectionUtils.isEmpty(folders)) {
            return Collections.emptyList();
        }
        return mediaFileRepository.findByFolderInAndMediaTypeInAndPresentTrue(folders, MediaType.playableTypes(), new OffsetBasedPageRequest(offset, count, Sort.by("id")));
    }

    /**
     * Returns all songs in the album
     *
     * @param artist The album artist name.
     * @param album The album name.
     * @return All songs in the album.
     */
    public List<MediaFile> getSongsForAlbum(String artist, String album) {
        return mediaFileRepository.findByAlbumArtistAndAlbumNameAndMediaTypeInAndPresentTrue(artist, album, MediaType.audioTypes(), Sort.by("discNumber", "trackNumber"));
    }

    /**
     * Stable key identifying an (albumArtist, album) pair. Used to group batched track lookups so list
     * endpoints can render a whole page of albums with a single query instead of an N+1 per-album
     * {@link #getSongsForAlbum} round-trip (which made search3 / getAlbumList2 pages take seconds on
     * large libraries).
     */
    public record AlbumKey(String albumArtist, String albumName) {
        public static AlbumKey of(Album album) {
            return new AlbumKey(album.getArtist(), album.getName());
        }

        public static AlbumKey of(MediaFile mediaFile) {
            return new AlbumKey(mediaFile.getAlbumArtist(), mediaFile.getAlbumName());
        }
    }

    /**
     * Returns every track for the given albums in a single query, grouped by {@link AlbumKey}. Only
     * present audio tracks are returned, matching {@link #getSongsForAlbum}. Albums without tracks
     * simply have no entry in the map.
     */
    public Map<AlbumKey, List<MediaFile>> getSongsForAlbums(List<Album> albums) {
        if (CollectionUtils.isEmpty(albums)) {
            return Map.of();
        }
        Set<String> albumNames = albums.stream().map(Album::getName).filter(Objects::nonNull).collect(Collectors.toSet());
        Set<String> artistNames = albums.stream().map(Album::getArtist).filter(Objects::nonNull).collect(Collectors.toSet());
        if (albumNames.isEmpty()) {
            return Map.of();
        }
        return mediaFileRepository
                .findByAlbumArtistInAndAlbumNameInAndMediaTypeInAndPresentTrue(artistNames, albumNames, MediaType.audioTypes())
                .stream()
                .collect(Collectors.groupingBy(m -> new AlbumKey(m.getAlbumArtist(), m.getAlbumName())));
    }

    /**
     * Returns songs in a genre.
     *
     * @param offset      Number of songs to skip.
     * @param count      Maximum number of songs to return.
     * @param genre      The genre name.
     * @param musicFolders Only return songs in these folders.
     * @return Songs in the genre.
     */
    public List<MediaFile> getSongsByGenre(int offset, int count, String genre, List<MusicFolder> musicFolders) {
        if (CollectionUtils.isEmpty(musicFolders)) {
            return Collections.emptyList();
        }
        return mediaFileRepository.findByFolderInAndMediaTypeInAndGenreAndPresentTrue(musicFolders, MediaType.audioTypes(), genre, new OffsetBasedPageRequest(offset, count, Sort.by("id")));
    }

    /**
     * Returns songs by a given artist.
     *
     * @param offset      Number of songs to skip.
     * @param count     Maximum number of songs to return.
     * @param artist    The artist name.
     * @return Songs by the artist.
     */
    public List<MediaFile> getSongsByArtist(int offset, int count, String artist) {
        return mediaFileRepository.findByMediaTypeInAndArtistAndPresentTrue(MediaType.audioTypes(), artist, new OffsetBasedPageRequest(offset, count, Sort.by("id")));
    }

    /**
     * Returns song by a given artist and title.
     * @param artist The artist name.
     * @param title The title name.
     * @param musicFolders Only return songs in these folders.
     * @return Song by the artist and title.
     */
    public MediaFile getSongByArtistAndTitle(@Nullable String artist, @Nullable String title, @Nullable List<MusicFolder> musicFolders) {
        if (CollectionUtils.isEmpty(musicFolders) || StringUtils.isBlank(artist) || StringUtils.isBlank(title)) {
            return null;
        }
        List<MediaFile> results = mediaFileRepository.findByFolderInAndMediaTypeAndArtistAndTitleAndPresentTrue(musicFolders, MediaType.MUSIC, artist, title);
        if (results.isEmpty()) {
            return null;
        }
        return results.getFirst();
    }

    /**
     * Returns the most recently starred songs.
     *
     * @param offset       Number of songs to skip.
     * @param count        Maximum number of songs to return.
     * @param username     Returns songs starred by this user.
     * @param musicFolders Only return songs from these folders.
     * @return The most recently starred songs for this user.
     */
    @Transactional
    public List<MediaFile> getStarredSongs(int offset, int count, String username, List<MusicFolder> musicFolders) {
        if (CollectionUtils.isEmpty(musicFolders)) {
            return Collections.emptyList();
        }
        return starredMediaFileRepository
                .findByUsernameAndMediaFileMediaTypeInAndMediaFileFolderInAndMediaFilePresentTrue(username,
                        MediaType.audioTypes(), musicFolders,
                        new OffsetBasedPageRequest(offset, count, Sort.by("created").descending().and(Sort.by("id"))))
                .stream().map(StarredMediaFile::getMediaFile).toList();
    }

    /**
     * Returns artist info for the given artist.
     *
     * @param artist The artist name.
     * @param folders The music folders to search.
     * @return Artist info for the given artist.
     */
    public MediaFile getArtistByName(String artist, List<MusicFolder> folders) {
        if (CollectionUtils.isEmpty(folders)) {
            return null;
        }

        List<MediaFile> results = mediaFileRepository.findByFolderInAndMediaTypeAndArtistAndPresentTrue(folders,
                MediaType.DIRECTORY, artist);

        if (results.isEmpty()) {
            return null;
        }
        // return the first result
        return results.getFirst();
    }

    /**
     * Batches {@link #getArtistByName(String, List)} for a whole page of artist names into a single
     * query keyed by artist name. Artists with no directory media file are absent.
     */
    public Map<String, MediaFile> getArtistsByName(Collection<String> artistNames, List<MusicFolder> folders) {
        if (CollectionUtils.isEmpty(folders) || artistNames == null || artistNames.isEmpty()) {
            return Map.of();
        }
        Set<String> names = artistNames.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        if (names.isEmpty()) {
            return Map.of();
        }
        return mediaFileRepository.findByFolderInAndMediaTypeAndArtistInAndPresentTrue(folders, MediaType.DIRECTORY, names)
                .stream()
                .collect(Collectors.toMap(MediaFile::getArtist, Function.identity(), (a, b) -> a));
    }

    /**
     * Returns the most recently starred artists.
     *
     * @param offset       Number of artists to skip.
     * @param count        Maximum number of artists to return.
     * @param username     Returns artists starred by this user.
     * @param musicFolders Only return artists from these folders.
     * @return The most recently starred artists for this user.
     */
    @Transactional
    public List<MediaFile> getStarredArtists(int offset, int count, String username, List<MusicFolder> musicFolders) {
        if (CollectionUtils.isEmpty(musicFolders)) {
            return Collections.emptyList();
        }
        return starredMediaFileRepository
                .findByUsernameAndMediaFileMediaTypeAndMediaFileFolderInAndMediaFilePresentTrue(username,
                        MediaType.DIRECTORY, musicFolders,
                        new OffsetBasedPageRequest(offset, count, Sort.by("created").descending().and(Sort.by("id"))))
                .stream().map(StarredMediaFile::getMediaFile).toList();
    }

     /**
     * Returns all videos in folders
     *
     * @param artist The album artist name.
     * @param album The album name.
     * @return All songs in the album.
     */
    public List<MediaFile> getVideos(List<MusicFolder> folders, int count, int offset) {
        if (CollectionUtils.isEmpty(folders)) {
            return Collections.emptyList();
        }
        return mediaFileRepository.findByFolderInAndMediaTypeAndPresentTrue(folders, MediaType.VIDEO, new OffsetBasedPageRequest(offset, count, Sort.by("title")));
    }


    /**
     * Returns whether the given file is the root of a media folder.
     *
     * @param mediaFile The file in question. Must not be {@code null}.
     * @return Whether the given file is the root of a media folder.
     * @see MusicFolder
     */
    public boolean isRoot(MediaFile mediaFile) {
        return StringUtils.isEmpty(mediaFile.getPath()) &&
                mediaFolderService.getAllMusicFolders(true, true).parallelStream()
                        .anyMatch(x -> mediaFile.getFolder().getId().equals(x.getId()));
    }

    /**
     * Returns all genres in the music collection.
     *
     * @param sortByAlbum Whether to sort by album count, rather than song count.
     * @return Sorted list of genres.
     */
    public List<Genre> getGenres(boolean sortByAlbum) {
        Sort sort = sortByAlbum ? Sort.by("albumCount") : Sort.by("songCount");
        return genreRepository.findAll(sort.and(Sort.by(Direction.ASC, "name")));
    }

    /**
     * update genres
     *
     * @param genres The genres to update.
     * @return The updated genres.
     */
    @Transactional
    public List<Genre> updateGenres(List <Genre> genres) {
        return genreRepository.saveAll(genres);
    }

    /**
     * Returns the most frequently played albums.
     *
     * @param offset       Number of albums to skip.
     * @param count        Maximum number of albums to return.
     * @param musicFolders Only return albums in these folders.
     * @return The most frequently played albums.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<MediaFile> getMostFrequentlyPlayedAlbums(int offset, int count, List<MusicFolder> musicFolders) {
        if (CollectionUtils.isEmpty(musicFolders)) {
            return Collections.emptyList();
        }
        return mediaFileRepository.findByFolderInAndMediaTypeAndPlayCountGreaterThanAndPresentTrue(musicFolders, MediaType.ALBUM, 0, new OffsetBasedPageRequest(offset, count, Sort.by("playCount").descending().and(Sort.by("id"))));
    }

    /**
     * Returns the most recently played albums.
     *
     * @param offset       Number of albums to skip.
     * @param count        Maximum number of albums to return.
     * @param musicFolders Only return albums in these folders.
     * @return The most recently played albums.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<MediaFile> getMostRecentlyPlayedAlbums(int offset, int count, List<MusicFolder> musicFolders) {
        if (CollectionUtils.isEmpty(musicFolders)) {
            return Collections.emptyList();
        }
        return mediaFileRepository.findByFolderInAndMediaTypeAndPlayCountGreaterThanAndPresentTrue(musicFolders, MediaType.ALBUM, 0, new OffsetBasedPageRequest(offset, count, Sort.by("lastPlayed").descending().and(Sort.by("id"))));
    }

    /**
     * Returns the most recently added albums.
     *
     * @param offset       Number of albums to skip.
     * @param count        Maximum number of albums to return.
     * @param musicFolders Only return albums in these folders.
     * @return The most recently added albums.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<MediaFile> getNewestAlbums(int offset, int count, List<MusicFolder> musicFolders) {
        if (CollectionUtils.isEmpty(musicFolders)) {
            return Collections.emptyList();
        }
        return mediaFileRepository.findByFolderInAndMediaTypeAndPresentTrue(musicFolders, MediaType.ALBUM, new OffsetBasedPageRequest(offset, count, Sort.by("created").descending().and(Sort.by("id"))));
    }

    /**
     * Returns the most recently starred albums.
     *
     * @param offset       Number of albums to skip.
     * @param count        Maximum number of albums to return.
     * @param username     Returns albums starred by this user.
     * @param musicFolders Only return albums from these folders.
     * @return The most recently starred albums for this user.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<MediaFile> getStarredAlbums(int offset, int count, String username, List<MusicFolder> musicFolders) {
        if (CollectionUtils.isEmpty(musicFolders)) {
            return Collections.emptyList();
        }
        return starredMediaFileRepository
                .findByUsernameAndMediaFileMediaTypeAndMediaFileFolderInAndMediaFilePresentTrue(username,
                        MediaType.ALBUM, musicFolders,
                        new OffsetBasedPageRequest(offset, count, Sort.by("created").descending().and(Sort.by("id"))))
                .stream().map(StarredMediaFile::getMediaFile).toList();
    }

    /**
     * Returns albums in alphabetical order.
     *
     * @param offset       Number of albums to skip.
     * @param count        Maximum number of albums to return.
     * @param byArtist     Whether to sort by artist name
     * @param musicFolders Only return albums in these folders.
     * @return Albums in alphabetical order.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<MediaFile> getAlphabeticalAlbums(int offset, int count, boolean byArtist, List<MusicFolder> musicFolders) {
        if (CollectionUtils.isEmpty(musicFolders)) {
            return Collections.emptyList();
        }
        Sort sort = byArtist ? Sort.by("artist", "albumName", "id") : Sort.by("albumName", "id");
        return mediaFileRepository.findByFolderInAndMediaTypeAndPresentTrue(musicFolders, MediaType.ALBUM, new OffsetBasedPageRequest(offset, count, sort));
    }

    /**
     * Returns albums within a year range.
     *
     * @param offset       Number of albums to skip.
     * @param count        Maximum number of albums to return.
     * @param fromYear     The first year in the range.
     * @param toYear       The last year in the range.
     * @param musicFolders Only return albums in these folders.
     * @return Albums in the year range.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<MediaFile> getAlbumsByYear(int offset, int count, int fromYear, int toYear, List<MusicFolder> musicFolders) {

        if (CollectionUtils.isEmpty(musicFolders)) {
            return Collections.emptyList();
        }

        if (fromYear <= toYear) {
            return mediaFileRepository.findByFolderInAndMediaTypeAndYearBetweenAndPresentTrue(musicFolders, MediaType.ALBUM, fromYear, toYear, new OffsetBasedPageRequest(offset, count, Sort.by("year", "id")));
        } else {
            return mediaFileRepository.findByFolderInAndMediaTypeAndYearBetweenAndPresentTrue(musicFolders, MediaType.ALBUM, toYear, fromYear, new OffsetBasedPageRequest(offset, count, Sort.by("year").descending().and(Sort.by("id"))));
        }
    }

    /**
     * Returns albums in a genre.
     *
     * @param offset       Number of albums to skip.
     * @param count        Maximum number of albums to return.
     * @param genre        The genre name.
     * @param musicFolders Only return albums in these folders.
     * @return Albums in the genre.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<MediaFile> getAlbumsByGenre(int offset, int count, String genre, List<MusicFolder> musicFolders) {
        if (CollectionUtils.isEmpty(musicFolders)) {
            return Collections.emptyList();
        }
        return mediaFileRepository.findByFolderInAndMediaTypeAndGenreAndPresentTrue(musicFolders, MediaType.ALBUM, genre, new OffsetBasedPageRequest(offset, count, Sort.by("id")));
    }


    /**
     * Returns random songs for the given parent.
     *
     * @param parent The parent.
     * @param count  Max number of songs to return.
     * @return Random songs.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<MediaFile> getRandomSongsForParent(MediaFile parent, int count) {
        List<MediaFile> children = getDescendantsOf(parent, false);
        removeVideoFiles(children);

        if (children.isEmpty()) {
            return children;
        }
        Collections.shuffle(children);
        return children.subList(0, Math.min(count, children.size()));
    }

    /**
     * Returns random songs matching search criteria.
     *
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<MediaFile> getRandomSongs(RandomSearchCriteria criteria, String username) {
        if (criteria == null || CollectionUtils.isEmpty(criteria.musicFolders())) {
            return Collections.emptyList();
        }
        return mediaFileRepository.findAll(MediaFileSpecifications.matchCriteria(criteria, username, settingsService.getDatabaseType()), Pageable.ofSize(criteria.count()));
    }

    /**
     * Removes video files from the given list.
     */
    public void removeVideoFiles(List<MediaFile> files) {
        files.removeIf(MediaFile::isVideo);
    }

    public Instant getMediaFileStarredDate(MediaFile mediaFile, String username) {
        return starredMediaFileRepository.findByUsernameAndMediaFile(username, mediaFile).map(StarredMediaFile::getCreated).orElse(null);
    }

    /**
     * Batches {@link #getMediaFileStarredDate(MediaFile, String)} for a whole page of media files into a
     * single {@code IN} query keyed by media file id. Files that are not starred are absent.
     */
    public Map<Integer, Instant> getMediaFileStarredDates(List<MediaFile> mediaFiles, String username) {
        if (mediaFiles == null || mediaFiles.isEmpty()) {
            return Map.of();
        }
        return starredMediaFileRepository
                .findByUsernameAndMediaFileIn(username, mediaFiles)
                .stream()
                .collect(Collectors.toMap(smf -> smf.getMediaFile().getId(), StarredMediaFile::getCreated, (a, b) -> a));
    }

    public void populateStarredDate(List<MediaFile> mediaFiles, String username) {
        if (mediaFiles == null || mediaFiles.isEmpty()) {
            return;
        }
        Map<MediaFile, Instant> byMediaFile = starredMediaFileRepository
                .findByUsernameAndMediaFileIn(username, mediaFiles)
                .stream()
                .collect(Collectors.toMap(StarredMediaFile::getMediaFile, StarredMediaFile::getCreated, (a, b) -> a));
        for (MediaFile mediaFile : mediaFiles) {
            mediaFile.setStarredDate(byMediaFile.get(mediaFile));
        }
    }

    public void populateStarredDate(MediaFile mediaFile, String username) {
        Instant starredDate = starredMediaFileRepository.findByUsernameAndMediaFile(username, mediaFile).map(StarredMediaFile::getCreated).orElse(null);
        mediaFile.setStarredDate(starredDate);
    }

    /**
     * Batches the {@code getParentOf} lookup for a whole page of media files into a handful of
     * {@code IN} queries (one per distinct music folder) instead of one round-trip per file. Files
     * whose parent cannot be resolved are simply absent from the returned map.
     */
    public Map<MediaFile, MediaFile> getParentsOf(List<MediaFile> mediaFiles) {
        if (mediaFiles == null || mediaFiles.isEmpty()) {
            return Map.of();
        }
        Map<MediaFile, MediaFile> result = new HashMap<>();
        mediaFiles.stream()
                .filter(mf -> mf.getParentPath() != null)
                .collect(Collectors.groupingBy(MediaFile::getFolder))
                .forEach((folder, files) -> {
                    Set<String> parentPaths = files.stream().map(MediaFile::getParentPath).collect(Collectors.toSet());
                    Map<String, MediaFile> byPath = mediaFileRepository.findByFolderAndPathIn(folder, parentPaths)
                            .stream()
                            .collect(Collectors.toMap(MediaFile::getPath, Function.identity(), (a, b) -> a));
                    files.forEach(mf -> {
                        MediaFile parent = byPath.get(mf.getParentPath());
                        if (parent != null) {
                            result.put(mf, parent);
                        }
                    });
                });
        return result;
    }

    @Nullable
    private List<MediaFile> updateChildren(@Nonnull MediaFile parent) {

        // Check timestamps.
        if (parent.getChildrenLastUpdated().compareTo(parent.getChanged()) > 0) {
            return null;
        }

        MusicFolder folder = parent.getFolder();
        Map<Pair<String, Double>, MediaFile> storedChildrenMap = mediaFileRepository.findByFolderAndParentPath(folder, parent.getPath(), Sort.by("startPosition")).parallelStream()
            .collect(Collectors.toConcurrentMap(i -> Pair.of(i.getPath(), i.getStartPosition()), i -> i));

        boolean isEnableCueIndexing = settingsService.getEnableCueIndexing();

        // cue sheets
        Map<String, CueSheet> cueSheets = new ConcurrentHashMap<>();
        // media files that are not indexed
        Map<String, MediaFile> bareFiles = new ConcurrentHashMap<>();
        // m4b files are treated as audio books
        Map<String, MediaFile> audioBooks = new ConcurrentHashMap<>();

        // Collect all children.
        try (Stream<Path> children = Files.list(parent.getFullPath())) {
            children.parallel()
                .filter(x -> mediaFolderService.getMusicFolderForFile(x, true, true).map(f -> f.equals(folder)).orElse(false))
                .forEach(x -> {
                    Path relativePath = folder.getPath().relativize(x);
                    if (includeMediaFileByPath(x)) {
                        MediaFile mediaFile = storedChildrenMap.remove(Pair.of(relativePath.toString(), MediaFile.NOT_INDEXED));
                        if (mediaFile == null) { // Not found in database, must read from disk.
                            mediaFile = createMediaFileByFile(relativePath, folder);
                            if (mediaFile != null) {
                                updateMediaFile(mediaFile);
                            }
                        } else if (!mediaFile.hasIndex()) {
                            mediaFile = checkLastModified(mediaFile, false); // has to be false, only time it's called
                        }
                        // Add children that are not already stored.
                        if (mediaFile != null) {
                            if ("m4b".equals(mediaFile.getFormat())) {
                                audioBooks.put(relativePath.toString(), mediaFile);
                            } else {
                                bareFiles.put(FilenameUtils.getName(mediaFile.getPath()), mediaFile);
                            }
                        }
                    }
                    if (isEnableCueIndexing && includeCueSheetByPath(x)) {
                        LOG.debug("Cue indexing enabled");
                        CueSheet cueSheet = getCueSheet(x);
                        if (cueSheet != null) {
                            cueSheets.put(relativePath.toString(), cueSheet);
                        }
                    }
                });
        } catch (IOException e) {
            LOG.warn("Could not retrieve and update all the children for {} in folder {}. Will skip", parent.getPath(), folder.getId(), e);
            return null;
        }

        // collect indexed tracks, if any
        List<MediaFile> result = new ArrayList<>();

        // cue tracks
        if (isEnableCueIndexing) {
            // External .cue files take priority over embedded .flac cue sheets: a FLAC that
            // carries an embedded CUE and also has an external .cue sidecar should be indexed
            // from the external one. Split the two groups, process external first, and silently
            // skip (DEBUG) any embedded cue whose base file was already claimed by an external cue.
            Map<String, CueSheet> externalCueSheets = new LinkedHashMap<>();
            Map<String, CueSheet> embeddedCueSheets = new LinkedHashMap<>();
            cueSheets.forEach((indexPath, cueSheet) -> {
                if (FilenameUtils.getExtension(indexPath).equalsIgnoreCase("cue")) {
                    externalCueSheets.put(indexPath, cueSheet);
                } else {
                    embeddedCueSheets.put(indexPath, cueSheet);
                }
            });
            Set<String> claimedBasePaths = new HashSet<>();

            // Sequential outer stream: each FILE block mutates shared scan state (bareFiles.remove,
            // storedChildrenMap) without synchronization, so iterating cue sheets in parallel was a
            // latent data race. A directory holds few cue sheets, so sequential costs nothing.
            List<MediaFile> indexedTracks = externalCueSheets.entrySet().stream()
                    .flatMap(e -> processCueSheetEntry(e, parent, folder, bareFiles, storedChildrenMap, claimedBasePaths))
                    .toList();
            indexedTracks = Stream.concat(
                    indexedTracks.stream(),
                    embeddedCueSheets.entrySet().stream()
                            .flatMap(e -> processCueSheetEntry(e, parent, folder, bareFiles, storedChildrenMap, claimedBasePaths)))
                    .toList();
            result.addAll(indexedTracks);

            // Override parent directory metadata from CUE.
            // CUE sheet metadata takes priority over audio file embedded tags
            // for single-file + CUE albums.
            if (!indexedTracks.isEmpty()) {
                MediaFile firstTrack = indexedTracks.get(0);
                if (firstTrack.getAlbumName() != null) {
                    parent.setAlbumName(firstTrack.getAlbumName());
                    // Also set title so getName() uses it instead of folder name
                    parent.setTitle(firstTrack.getAlbumName());
                }
                if (firstTrack.getAlbumArtist() != null) {
                    parent.setArtist(firstTrack.getAlbumArtist());
                    parent.setAlbumArtist(firstTrack.getAlbumArtist());
                }
            }
        }

        // m4b audio books
        List<MediaFile> audioBookTracks = audioBooks.entrySet().parallelStream().flatMap(e -> {
            String basePath = e.getKey();
            MediaFile base = e.getValue();
            List<MediaFile> tracks = createAudioBookTracks(base);
            if (!CollectionUtils.isEmpty(tracks)) {
                base.setIndexPath(basePath); // update indexPath in mediaFile
                updateMediaFile(base);
            }
            tracks.forEach(t -> storedChildrenMap.remove(Pair.of(t.getPath(), t.getStartPosition())));
            tracks.add(base);
            return tracks.stream();
        }).toList();
        result.addAll(audioBookTracks);

        // remove indexPath for deleted cuesheets, if any
        List<MediaFile> nonIndexedTracks = bareFiles.values().stream().parallel()
            .map(m -> {
                if (m.hasIndex()) {
                    m.setIndexPath(null);
                    updateMediaFile(m);
                }
                return m;
            })
            .toList();
        result.addAll(nonIndexedTracks);

        // Delete children that no longer exist on disk.
        storedChildrenMap.values().forEach(f -> delete(f));

        // Update timestamp in parent.
        parent.setChildrenLastUpdated(parent.getChanged());
        parent.setPresent(true);
        updateMediaFile(parent);

        return result;
    }

    /**
     * Resolves and materialises the indexed tracks for a single cue sheet entry — one FILE block
     * per audio file (multifile CUE support). External {@code .cue} sheets are processed before
     * embedded {@code .flac} cue sheets: {@code claimedBasePaths} records every base file already
     * indexed by an earlier (external) cue sheet, so an embedded cue that would re-index the same
     * base is skipped silently at DEBUG instead of logging a WARN.
     */
    private Stream<MediaFile> processCueSheetEntry(Map.Entry<String, CueSheet> entry,
            MediaFile parent, MusicFolder folder,
            Map<String, MediaFile> bareFiles,
            Map<Pair<String, Double>, MediaFile> storedChildrenMap,
            Set<String> claimedBasePaths) {
        String indexPath = entry.getKey();
        CueSheet cueSheet = entry.getValue();
        // One FILE block per audio file: resolve each block to its own base media file and
        // materialise that block's tracks against it (multifile CUE support).
        return cueSheet.getFileData().stream().flatMap(fileData -> {
            String filePath = fileData.getFile();
            // Prefer a bare-name match (existing behaviour: single-file CUE albums whose
            // FILE block names the sibling audio file in the same directory).
            MediaFile base = bareFiles.remove(FilenameUtils.getName(filePath));

            if (Objects.isNull(base)) {
                // Fall back to resolving the FILE block's path relative to the CUE's parent
                // directory. Many multi-disc albums (disc 1/01 … .flac) reference audio files in
                // subdirectories, which are never direct children of the parent and therefore
                // absent from `bareFiles`. getMediaFile resolves DB + disk (and creates the
                // MediaFile row if needed), so those bases index correctly too. Windows-authored
                // CUE sheets separate subdirectories with '\' — normalize to '/' first so the
                // path resolves on POSIX hosts where '\' is a legal filename character.
                Path baseRelative = Paths.get(parent.getPath()).resolve(normalizeCueFilePath(filePath)).normalize();
                base = getMediaFile(baseRelative, folder);
                if (Objects.nonNull(base)) {
                    bareFiles.remove(FilenameUtils.getName(base.getPath()));
                }
            }

            if (Objects.nonNull(base)) {
                if (!claimedBasePaths.add(base.getPath())) {
                    // Base file already indexed by an earlier (external) cue sheet — this embedded
                    // cue is redundant. Silently skip rather than re-indexing or logging a WARN.
                    LOG.debug("Embedded cue sheet {} superseded by external cue for base file {}", indexPath, base.getPath());
                    return Stream.<MediaFile>empty();
                }
                base.setIndexPath(indexPath); // update indexPath in mediaFile
                Instant mediaChanged = FileUtil.lastModified(base.getFullPath());
                Instant cueChanged = FileUtil.lastModified(base.getFullIndexPath());
                base.setChanged(mediaChanged.compareTo(cueChanged) >= 0 ? mediaChanged : cueChanged);
                updateMediaFile(base);
                List<MediaFile> tracks = createIndexedTracks(base, cueSheet, fileData);
                // remove stored children that are now indexed
                tracks.forEach(t -> storedChildrenMap.remove(Pair.of(t.getPath(), t.getStartPosition())));
                tracks.add(base);
                return tracks.stream();
            } else {
                // A missing/unresolvable FILE block is skipped without aborting the scan; the
                // remaining blocks and cue sheets still materialise.
                LOG.warn("Could not find base file '{}' for cue sheet {}", filePath, indexPath);
                return Stream.<MediaFile>empty();
            }
        });
    }

    /**
     * Normalizes Windows-style backslash path separators in a CUE FILE reference to forward
     * slashes. CUE sheets authored on Windows commonly reference audio files in subdirectories
     * with '\' (e.g. {@code disc 1\01-Track.flac}); on POSIX hosts '\' is a legal filename
     * character, so without normalization the reference would be treated as a literal filename.
     *
     * @param filePath the raw FILE reference from a CUE sheet
     * @return the reference with '\' replaced by '/'
     */
    static String normalizeCueFilePath(String filePath) {
        return filePath.replace('\\', '/');
    }

    /**
     * hide specific file types in player and API
     */
    public boolean showMediaFile(MediaFile media) {
        boolean isRealMedia = !(media.hasIndex() || media.isIndexedTrack());
        boolean isHidden = settingsService.getHideVirtualTracks() ^ media.isIndexedTrack();
        return isRealMedia || isHidden;
    }

    private boolean includeMediaFile(MediaFile candidate) {
        return includeMediaFileByPath(candidate.getFullPath());
    }

    private boolean includeMediaFileByPath(Path candidate) {
        String suffix = FilenameUtils.getExtension(candidate.toString()).toLowerCase();
        return (!isExcluded(candidate) && (Files.isDirectory(candidate) || isAudioFile(suffix) || isVideoFile(suffix)));
    }

    private boolean includeCueSheetByPath(Path candidate) {
        String suffix = FilenameUtils.getExtension(candidate.toString()).toLowerCase();
        return CUE_EXTENSIONS.contains(suffix);
    }

    private boolean isAudioFile(String suffix) {
        return settingsService.getMusicFileTypesSet().contains(suffix.toLowerCase());
    }

    private boolean isVideoFile(String suffix) {
        return settingsService.getVideoFileTypesSet().contains(suffix.toLowerCase());
    }

    /**
     * Returns whether the given file is excluded.
     *
     * @param file The child file in question.
     * @return Whether the child file is excluded.
     */
    private boolean isExcluded(Path file) {
        if (settingsService.getIgnoreSymLinks() && Files.isSymbolicLink(file)) {
            LOG.info("excluding symbolic link {}", file);
            return true;
        }
        String name = file.getFileName().toString();
        if (settingsService.getExcludePattern() != null && settingsService.getExcludePattern().matcher(name).find()) {
            LOG.info("excluding file which matches exclude pattern {}: {}", settingsService.getExcludePatternString(), file.toString());
            return true;
        }

        // Exclude all hidden files starting with a single "." or "@eaDir" (thumbnail dir created on Synology devices).
        return (name.startsWith(".") && !name.startsWith("..")) || name.startsWith("@eaDir") || "Thumbs.db".equals(name);
    }

    /**
     * Create media file from file system. Do not set non existing file.
     *
     * @param relativePath relative path
     * @param folder      music folder
     * @return media file reflected from file system
     */
    private @Nullable MediaFile createMediaFileByFile(Path relativePath, MusicFolder folder) {
        MediaFile mediaFile = new MediaFile();
        mediaFile.setPath(relativePath.toString());
        mediaFile.setFolder(folder);
        MediaFile result = updateMediaFileByFile(mediaFile, true);
        return result.isPresent() ? result : null;
    }

    /**
     * update media file by file
     *
     * @param mediaFile media file to reflect. Must not be null. path must be set.
     * @return media file reflected from file system
     */
    private MediaFile updateMediaFileByFile(MediaFile mediaFile) {
        return updateMediaFileByFile(mediaFile, false);
    }

    /**
     * return media file reflected from file system
     *
     * @param mediaFile   media file to reflect. Must not be null. path must be set.
     * @param isCheckedExistence whether to check file existence
     * @param folder     music folder
     * @return media file reflected from file system
     */
    private MediaFile updateMediaFileByFile(MediaFile mediaFile, boolean isCheckedExistence) {

        if (mediaFile == null || mediaFile.getFolder() == null || mediaFile.getPath() == null) {
            throw new IllegalArgumentException("mediaFile, folder and mediaFile.path must not be null");
        }

        Path relativePath = mediaFile.getRelativePath();
        Path file = mediaFile.getFullPath();
        if (!isCheckedExistence && !Files.exists(file)) {
            // file not found
            mediaFile.setPresent(false);
            mediaFile.setChildrenLastUpdated(Instant.ofEpochMilli(1));
            return mediaFile;
        }

        //sanity check
        MusicFolder folderActual = mediaFolderService.getMusicFolderForFile(file, true, true).orElse(mediaFile.getFolder());
        if (!folderActual.getId().equals(mediaFile.getFolder().getId())) {
            LOG.warn("Inconsistent Mediafile folder for media file with path: {}, folder id should be {} and is instead {}", file, folderActual.getId(), mediaFile.getFolder().getId());
        }
        // distinguish between null (no parent, like root folder), "" (root parent), and else
        String parentPath = null;
        if (StringUtils.isNotEmpty(relativePath.toString())) {
            parentPath = relativePath.getParent() == null ? "" : relativePath.getParent().toString();
        }

        Instant lastModified = FileUtil.lastModified(file);
        mediaFile.setParentPath(parentPath);
        mediaFile.setChanged(lastModified);
        mediaFile.setLastScanned(Instant.now());
        mediaFile.setChildrenLastUpdated(Instant.ofEpochMilli(1)); //distant past, can't use Instant.MIN due to HSQL incompatibility
        mediaFile.setCreated(lastModified);
        mediaFile.setMediaType(MediaFile.MediaType.DIRECTORY);
        mediaFile.setPresent(true);

        if (Files.isRegularFile(file)) {

            MetaDataParser parser = metaDataParserFactory.getParser(file);
            if (parser != null) {
                MetaData metaData = parser.getMetaData(file);
                mediaFile.setArtist(metaData.getArtist());
                mediaFile.setAlbumArtist(metaData.getAlbumArtist());
                mediaFile.setAlbumName(metaData.getAlbumName());
                mediaFile.setAlbumSortName(metaData.getAlbumSortName());
                mediaFile.setTitle(metaData.getTitle());
                mediaFile.setSortName(metaData.getSortName());
                mediaFile.setDiscNumber(metaData.getDiscNumber());
                mediaFile.setDiscSubtitle(metaData.getDiscSubtitle());
                mediaFile.setTrackNumber(metaData.getTrackNumber());
                mediaFile.setGenre(metaData.getGenre());
                mediaFile.setGenres(packGenres(metaData.getGenres()));
                mediaFile.setYear(metaData.getYear());
                mediaFile.setReleaseDate(metaData.getReleaseDate());
                mediaFile.setOriginalReleaseDate(metaData.getOriginalReleaseDate());
                mediaFile.setCompilation(metaData.getCompilation());
                mediaFile.setReleaseTypes(packMultiValue(metaData.getReleaseTypes()));
                mediaFile.setRecordLabels(packMultiValue(metaData.getRecordLabels()));
                mediaFile.setContributors(Contributors.pack(metaData.getContributors()));
                mediaFile.setBpm(metaData.getBpm());
                mediaFile.setDuration(metaData.getDuration());
                mediaFile.setBitRate(metaData.getBitRate());
                mediaFile.setVariableBitRate(metaData.getVariableBitRate());
                mediaFile.setHeight(metaData.getHeight());
                mediaFile.setWidth(metaData.getWidth());
                mediaFile.setMusicBrainzReleaseId(metaData.getMusicBrainzReleaseId());
                mediaFile.setMusicBrainzRecordingId(metaData.getMusicBrainzRecordingId());
                mediaFile.setMusicBrainzArtistId(metaData.getMusicBrainzArtistId());
                mediaFile.setArtistSortName(metaData.getArtistSortName());
                mediaFile.setReplayGainTrackGain(metaData.getReplayGainTrackGain());
                mediaFile.setReplayGainAlbumGain(metaData.getReplayGainAlbumGain());
                mediaFile.setReplayGainTrackPeak(metaData.getReplayGainTrackPeak());
                mediaFile.setReplayGainAlbumPeak(metaData.getReplayGainAlbumPeak());
                mediaFile.setReplayGainBaseGain(metaData.getBaseGain());
            }
            String format = StringUtils.trimToNull(StringUtils.lowerCase(FilenameUtils.getExtension(mediaFile.getPath())));
            mediaFile.setFormat(format);
            mediaFile.setFileSize(FileUtil.size(file));
            mediaFile.setMediaType(getMediaType(mediaFile));

        } else {

            MusicFolder folder = mediaFile.getFolder();
            // Is this an album?
            if (!isRoot(mediaFile)) {
                try (Stream<Path> stream = Files.list(file)) {
                    List<Path> children = stream.parallel().toList();
                    Path firstChild = children.parallelStream()
                            .filter(this::includeMediaFileByPath)
                            .filter(x -> Files.isRegularFile(x))
                            .findFirst().orElse(null);

                    if (firstChild != null) {
                        mediaFile.setMediaType(MediaFile.MediaType.ALBUM);

                        // Guess artist/album name, year and genre.
                        MetaDataParser parser = metaDataParserFactory.getParser(firstChild);
                        if (parser != null) {
                            MetaData metaData = parser.getMetaData(firstChild);
                            mediaFile.setArtist(metaData.getAlbumArtist());
                            mediaFile.setAlbumName(metaData.getAlbumName());
                            mediaFile.setYear(metaData.getYear());
                            mediaFile.setGenre(metaData.getGenre());
                            mediaFile.setGenres(packGenres(metaData.getGenres()));
                        }
                    } else {
                        mediaFile.setArtist(file.getFileName().toString());
                    }
                    // Look for cover art.
                    Path coverArt = findCoverArt(children);
                    if (coverArt != null) {
                        // placeholder to be persisted later
                        mediaFile.setArt(new CoverArt(-1, EntityType.MEDIA_FILE, folder.getPath().relativize(coverArt).toString(), folder, false));
                    }

                } catch (IOException e) {
                    LOG.warn("Could not retrieve children for {}.", file.toString(), e);

                    mediaFile.setArtist(file.getFileName().toString());
                }
            } else {
                // root folders need to have a title
                mediaFile.setTitle(folder.getName());
            }
        }

        return mediaFile;

    }

    /**
     * Returns m4b audio book tracks from the given audio book file.
     *
     * @param base The audio book file.
     * @return The audio book tracks.
     */
    @Nonnull
    private List<MediaFile> createAudioBookTracks(@Nonnull MediaFile base) {
        List<MediaFile> children = new ArrayList<>();
        Path audioFile = base.getFullPath();
        Map<Long, MediaFile> storedChildrenMap = new ConcurrentHashMap<>();

        try {
            List<Chapter> chapters = ffmpegParser.getMetaData(audioFile).getChapters();
            if (CollectionUtils.isEmpty(chapters)) {
                return children;
            }
            // get existing children
            MusicFolder baseFolder = base.getFolder();
            String basePath = base.getPath();
            storedChildrenMap = mediaFileRepository.findByFolderAndPath(baseFolder, basePath).parallelStream()
                    .filter(MediaFile::isIndexedTrack)
                    .collect(Collectors.toConcurrentMap(i -> Math.round(i.getStartPosition() * 10), i -> i));

            boolean update = needsUpdate(base, settingsService.isFastCacheEnabled());

            // get base properties
            Instant lastModified = FileUtil.lastModified(audioFile);
            Instant childrenLastUpdated = Instant.now().plusSeconds(100 * 365 * 24 * 60 * 60); // now + 100 years,
                                                                                               // tracks do not have
                                                                                               // children

            for (Chapter chapter : chapters) {
                if (chapter.getStartTimeSeconds() == null || chapter.getEndTimeSeconds() == null) {
                    continue;
                }
                Double duration = chapter.getEndTimeSeconds() - chapter.getStartTimeSeconds();
                MediaFile existingFile = storedChildrenMap.remove(Math.round(chapter.getStartTimeSeconds() * 10));
                if (existingFile != null
                        && !DoubleMath.fuzzyEquals(existingFile.getDuration(), duration, DURATION_EPSILON)) {
                    existingFile = null;
                }
                MediaFile track = existingFile;
                if (update || existingFile == null) {
                    track = existingFile != null ? existingFile : new MediaFile();
                    track.setPath(basePath);
                    track.setAlbumArtist(base.getAlbumArtist());
                    track.setAlbumName(base.getAlbumName());
                    track.setTitle(chapter.getTitle());
                    track.setArtist(base.getArtist());
                    track.setParentPath(base.getParentPath());
                    track.setFolder(baseFolder);
                    track.setChanged(lastModified);
                    track.setLastScanned(Instant.now());
                    track.setChildrenLastUpdated(childrenLastUpdated);
                    track.setCreated(lastModified);
                    track.setPresent(true);
                    track.setStartPosition(chapter.getStartTimeSeconds());
                    track.setDuration(duration);
                    track.setTrackNumber(chapter.getId().intValue());
                    track.setDiscNumber(base.getDiscNumber());
                    track.setGenre(base.getGenre());
                    track.setYear(base.getYear());
                    track.setBitRate(base.getBitRate());
                    track.setVariableBitRate(base.isVariableBitRate());
                    track.setHeight(base.getHeight());
                    track.setWidth(base.getWidth());
                    track.setFormat(base.getFormat());
                    track.setMusicBrainzRecordingId(base.getMusicBrainzRecordingId());
                    track.setMusicBrainzReleaseId(base.getMusicBrainzReleaseId());
                    Double baseDuration = base.getDuration();
                    double wholeFileLength = baseDuration != null ? baseDuration : 0.0;
                    long estimatedSize = (long) (duration / wholeFileLength * Files.size(audioFile));
                    if (estimatedSize > 0 && estimatedSize < Files.size(audioFile)) {
                        track.setFileSize(estimatedSize);
                    } else {
                        track.setFileSize(Files.size(audioFile) / chapters.size());
                    }
                    track.setPlayCount((existingFile == null) ? 0 : existingFile.getPlayCount());
                    track.setLastPlayed((existingFile == null) ? null : existingFile.getLastPlayed());
                    track.setComment((existingFile == null) ? null : existingFile.getComment());
                    track.setMediaType(base.getMediaType());

                    updateMediaFile(track);
                }
                children.add(track);
            }
            return children;
        } catch (Exception e) {
            LOG.warn("Could not retrieve chapters for {}.", audioFile.toString(), e);
            return new ArrayList<>();
        } finally {
            storedChildrenMap.values().forEach(this::delete);
        }
    }

    @Nonnull
    private List<MediaFile> createIndexedTracks(@Nonnull MediaFile base, @Nullable CueSheet cueSheet) {
        return createIndexedTracks(base, cueSheet, getFileDataForBase(base, cueSheet));
    }

    private List<MediaFile> createIndexedTracks(@Nonnull MediaFile base, @Nullable CueSheet cueSheet, @Nullable FileData fileData) {

        Map<Pair<String, Double>, MediaFile> storedChildrenMap = mediaFileRepository.findByFolderAndPath(base.getFolder(), base.getPath()).parallelStream()
            .filter(MediaFile::isIndexedTrack).collect(Collectors.toConcurrentMap(i -> Pair.of(i.getPath(), i.getStartPosition()), i -> i));

        List<MediaFile> children = new ArrayList<>();

        try {
            if (Objects.isNull(cueSheet) || Objects.isNull(fileData)) {
                base.setIndexPath(null);
                updateMediaFile(base);
                return children;
            }

            Path audioFile = base.getFullPath();
            long wholeFileSize = Files.size(audioFile);
            Double baseDuration = base.getDuration();
            double wholeFileLength = baseDuration != null ? baseDuration : 0.0;

            String basePath = base.getPath();
            String parentPath = base.getParentPath();
            String performer = cueSheet.getPerformer();
            String albumName = cueSheet.getTitle();
            MediaFile.MediaType mediaType = getMediaType(base);
            Instant lastModified = FileUtil.lastModified(audioFile);
            Instant childrenLastUpdated = Instant.now().plusSeconds(100 * 365 * 24 * 60 * 60); // now + 100 years, tracks do not have children
            MusicFolder baseFolder = base.getFolder();

            // Only this FILE block's tracks materialise against this base media file.
            List<TrackData> cueTracks = fileData.getTrackData();
            int trackSize = cueTracks.size();

            if (trackSize > 0) {
                TrackData lastTrackData = cueTracks.get(trackSize - 1);
                double lastTrackStart = getStartPosition(lastTrackData);
                if (lastTrackStart >= wholeFileLength) {
                    base.setIndexPath(null);
                    updateMediaFile(base);
                    return children;
                }
            }

            for (int i = 0; i < trackSize; i++) {
                TrackData trackData = cueTracks.get(i);
                double currentStart = getStartPosition(trackData);
                // duration runs to the next track in THIS block; the last track runs to the file end
                double nextStart = (trackSize - 1 != i) ? getStartPosition(cueTracks.get(i + 1)) : wholeFileLength;

                double duration = nextStart - currentStart;

                // Refresh the existing indexed track in place (title/artist/track number/duration may
                // have changed) while preserving playCount/lastPlayed/comment; create a fresh row when
                // none exists for this start position.
                MediaFile existingFile = storedChildrenMap.remove(Pair.of(basePath, currentStart));
                MediaFile track = (existingFile != null) ? existingFile : new MediaFile();
                track.setPath(basePath);
                track.setAlbumArtist(performer);
                track.setAlbumName(albumName);
                track.setTitle(trackData.getTitle());
                track.setArtist(trackData.getPerformer());
                track.setParentPath(parentPath);
                track.setFolder(baseFolder);
                track.setChanged(lastModified);
                track.setLastScanned(Instant.now());
                track.setChildrenLastUpdated(childrenLastUpdated);
                track.setCreated(lastModified);
                track.setPresent(true);
                track.setTrackNumber(trackData.getNumber());
                track.setDiscNumber(base.getDiscNumber());
                track.setGenre(base.getGenre());
                track.setYear(base.getYear());
                track.setBitRate(base.getBitRate());
                track.setVariableBitRate(base.isVariableBitRate());
                track.setHeight(base.getHeight());
                track.setWidth(base.getWidth());
                track.setFormat(base.getFormat());
                track.setStartPosition(currentStart);
                track.setDuration(duration);
                // estimate file size based on duration and whole file size
                long estimatedSize = (long) (duration / wholeFileLength * wholeFileSize);
                // if estimated size is within of whole file size, use it. Otherwise use whole file size divided by number of tracks
                if (estimatedSize > 0 && estimatedSize <= wholeFileSize) {
                    track.setFileSize(estimatedSize);
                } else {
                    track.setFileSize((long)(wholeFileSize / trackSize));
                }
                track.setPlayCount((existingFile == null) ? 0 : existingFile.getPlayCount());
                track.setLastPlayed((existingFile == null) ? null : existingFile.getLastPlayed());
                track.setComment((existingFile == null) ? null : existingFile.getComment());
                track.setMediaType(mediaType);

                updateMediaFile(track);

                children.add(track);
            }
            return children;
        } catch (IOException e) {
            LOG.warn("Not found: {}", base.getFullIndexPath());
            return new ArrayList<MediaFile>();
        } catch (IndexOutOfBoundsException e) {
            LOG.warn("Invalid CUE sheet: {}", base.getFullIndexPath());
            return new ArrayList<MediaFile>();
        } finally {
            storedChildrenMap.values().forEach(m -> delete(m));
        }
    }

    private List<MediaFile> createIndexedTracks(MediaFile base) {
        CueSheet cueSheet = getCueSheet(base);
        return createIndexedTracks(base, cueSheet);
    }

    /**
     * Selects the FILE block that owns the given base media file (single-file path / 2-arg overload):
     * the block whose FILE name matches the base file name, falling back to the first block. Returns
     * null only when the cue sheet is null or has no FILE blocks.
     */
    @Nullable
    private FileData getFileDataForBase(@Nonnull MediaFile base, @Nullable CueSheet cueSheet) {
        if (Objects.isNull(cueSheet) || cueSheet.getFileData().isEmpty()) {
            return null;
        }
        String baseFileName = FilenameUtils.getName(base.getPath());
        return cueSheet.getFileData().stream()
            .filter(fileData -> FilenameUtils.getName(fileData.getFile()).equals(baseFileName))
            .findFirst()
            .orElseGet(() -> cueSheet.getFileData().get(0));
    }

    /**
     * Converts a track's first INDEX position to fractional seconds. CUE timestamps are
     * minutes:seconds:frames at 75 frames/second; dividing frames by 75.0 (not int 75) preserves the
     * sub-second component that would otherwise truncate to zero.
     */
    private double getStartPosition(TrackData trackData) {
        Position position = trackData.getIndices().get(0).getPosition();
        return position.getMinutes() * 60 + position.getSeconds() + (position.getFrames() / 75.0);
    }

    private MediaFile.MediaType getMediaType(MediaFile mediaFile) {
        MusicFolder folder = mediaFile.getFolder();
        if (folder.getType() == Type.PODCAST) {
            return MediaType.PODCAST;
        }
        if (isVideoFile(mediaFile.getFormat())) {
            return MediaFile.MediaType.VIDEO;
        }
        String path = mediaFile.getPath().toLowerCase();
        String genre = StringUtils.trimToEmpty(mediaFile.getGenre()).toLowerCase();
        if (path.contains("podcast") || genre.contains("podcast") || path.contains("netcast") || genre.contains("netcast")) {
            return MediaFile.MediaType.PODCAST;
        }
        if (path.contains("audiobook") || genre.contains("audiobook")
                || path.contains("audio book") || genre.contains("audio book")
                || path.contains("audio/book") || path.contains("audio\\book")) {
            return MediaFile.MediaType.AUDIOBOOK;
        }

        return MediaFile.MediaType.MUSIC;
    }

    @Transactional
    public void refreshMediaFile(MediaFile mediaFile) {
        mediaFile = updateMediaFileByFile(mediaFile);
        updateMediaFile(mediaFile);
    }

    public void setMemoryCacheEnabled(boolean memoryCacheEnabled) {
        mediaFileCache.clear();
        mediaFileCache.setEnabled(memoryCacheEnabled);
    }

    /**
     * get Cue sheet from cue file absolute path
     *
     * @param cueFile absolute path of cue or embedded flac file
     * @return if parse success return cue sheet, otherwise null
     */
    private CueSheet getCueSheet(@Nonnull Path cueFile) {
        try {
            CueSheet cueSheet = null;
            String ext = FilenameUtils.getExtension(cueFile.toString()).toLowerCase();
            switch (ext) {
                case "cue":
                    cueSheet = parseCueFile(cueFile);
                    break;
                case "flac":
                    // External .cue sidecar wins over the embedded cue sheet. If a sibling .cue
                    // parses successfully, the embedded cue is redundant — skip it so a failing
                    // embedded parse doesn't surface a spurious cuelib ERROR.
                    if (siblingExternalCueParses(cueFile)) {
                        return null;
                    }
                    cueSheet = FLACReader.getCueSheet(cueFile);
                    break;
                default:
                    return null;
            }
            // validation
            if (cueSheet == null || cueSheet.getFileData() == null || cueSheet.getFileData().size() == 0) {
                if ("cue".equals(ext)) {
                    // Warn if no file data found in cue sheet
                    LOG.warn("Error parsing cuesheet {}", cueFile);
                }
                return null;
            }
            return cueSheet;
        } catch (IOException e) {
            LOG.warn("Error getting cuesheet for {} ", cueFile);
            return null;
        }
    }

    /**
     * Returns a parsed CueSheet for the given mediaFile
     */
    private CueSheet getCueSheet(MediaFile media) {
        return getCueSheet(media.getFullIndexPath());
    }

    /**
     * Parses an external {@code .cue} file, detecting BOM/charset and dropping sheets that carry
     * parser warnings.
     *
     * @param cueFile absolute path of the cue file
     * @return parsed cue sheet, or {@code null} if unreadable or warning-bearing
     */
    private CueSheet parseCueFile(Path cueFile) {
        Charset cs = Charset.forName("UTF-8"); // default to UTF-8
        // Detect encoding and BOM from the file
        boolean bomFound = false;
        int THRESHOLD = 35; // 0-100, the higher the more certain the guess
        CueSheet cueSheet = null;
        try (FileInputStream fis = new FileInputStream(cueFile.toFile());
             BufferedInputStream bis = new BufferedInputStream(fis)) {
            // Check for BOM first
            bis.mark(3);
            byte[] bom = new byte[3];
            int bytesRead = bis.read(bom, 0, 3);
            if (hasBOM(bom, bytesRead)) {
                bomFound = true;
                cs = Charset.forName("UTF-8");
                LOG.debug("Detected BOM for cuesheet file {}, forcing UTF-8", cueFile);
            } else {
                bis.reset();
            }

            // Detect encoding (CharsetDetector consumes the stream)
            if (!bomFound) {
                CharsetDetector cd = new CharsetDetector();
                cd.setText(bis);
                CharsetMatch cm = cd.detect();
                if (cm != null && cm.getConfidence() > THRESHOLD) {
                    cs = Charset.forName(cm.getName());
                }
                LOG.debug("Detected charset for cuesheet file {}: Charset detected as {}", cueFile, cs);
            }

            // Parse: for BOM files use positioned stream, for others re-open
            if (bomFound) {
                cueSheet = CueParser.parse(bis, cs);
            } else {
                cueSheet = CueParser.parse(cueFile, cs);
            }
        } catch (IOException e) {
            LOG.warn("Error parsing cuesheet {}, defaulting to UTF-8", cueFile, e);
        }
        if (cueSheet != null) {
            if (cueSheet.getMessages().stream().filter(m -> m.toString().toLowerCase().contains("warning"))
                    .map(m -> {
                        LOG.warn("Parsing {} at line {} : {}", cueFile, m.getLineNumber(), m.getMessage());
                        return m;
                    }).findFirst().isPresent()) {
                cueSheet = null;
            }
        }
        return cueSheet;
    }

    /**
     * Returns {@code true} when a sibling {@code .cue} file next to the given FLAC parses into a
     * usable cue sheet — in which case the embedded cue is redundant and may be skipped.
     */
    private boolean siblingExternalCueParses(Path flacFile) {
        Path sibling = flacFile.resolveSibling(FilenameUtils.getBaseName(flacFile.toString()) + ".cue");
        if (!Files.isRegularFile(sibling)) {
            return false;
        }
        CueSheet cue = parseCueFile(sibling);
        return cue != null && cue.getFileData() != null && !cue.getFileData().isEmpty();
    }

    /**
     * Finds a cover art image for the given directory, by looking for it on the disk.
     */
    private Path findCoverArt(Collection<Path> candidates) {
        Path candidate = null;
        var coverArtSource = settingsService.getCoverArtSource();
        switch (coverArtSource) {
            case TAGFILE:
                candidate = findTagCover(candidates);
                if (candidate != null) {
                    return candidate;
                } else {
                    return findFileCover(candidates);
                }
            case FILE:
                return findFileCover(candidates);
            case TAG:
                return findTagCover(candidates);
            case FILETAG:
            default:
                candidate = findFileCover(candidates);
                if (candidate != null) {
                    return candidate;
                } else {
                    return findTagCover(candidates);
                }
        }
    }

    private Path findFileCover(Collection<Path> candidates) {
        for (String mask : settingsService.getCoverArtFileTypesSet()) {
            Path cand = candidates.parallelStream().filter(c -> {
                String candidate = c.getFileName().toString().toLowerCase();
                return candidate.endsWith(mask) && !candidate.startsWith(".") && Files.isRegularFile(c);
            }).findAny().orElse(null);

            if (cand != null) {
                return cand;
            }
        }
        return null;
    }

    private Path findTagCover(Collection<Path> candidates) {
        // Look for embedded images in audiofiles.
        return candidates.stream()
                .filter(JaudiotaggerParser::isImageAvailable)
                .findFirst()
                .orElse(null);
    }


    /**
     * Returns all media files that are children, grand-children etc of a given media file.
     * Directories are not included in the result.
     *
     * @param sort Whether to sort files in the same directory.
     * @return All descendant music files.
     */
    public List<MediaFile> getDescendantsOf(MediaFile ancestor, boolean sort) {

        if (ancestor.isFile()) {
            return Arrays.asList(ancestor);
        }

        List<MediaFile> result = new ArrayList<MediaFile>();

        for (MediaFile child : getVisibleChildrenOf(ancestor, true, sort)) {
            if (child.isDirectory()) {
                result.addAll(getDescendantsOf(child, sort));
            } else {
                result.add(child);
            }
        }
        return result;
    }

    public void setMetaDataParserFactory(MetaDataParserFactory metaDataParserFactory) {
        this.metaDataParserFactory = metaDataParserFactory;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void updateMediaFile(@Nonnull MediaFile mediaFile) {
        mediaFileCache.removeMediaFile(mediaFile);
        if (mediaFile.getId() != null && mediaFileRepository.existsById(mediaFile.getId())) {
            mediaFileRepository.save(mediaFile);
        } else {
            mediaFileRepository.findByPathAndFolderAndStartPosition(mediaFile.getPath(), mediaFile.getFolder(), mediaFile.getStartPosition()).ifPresentOrElse(m -> {
                mediaFile.setId(m.getId());
                mediaFileRepository.save(mediaFile);
            }, () -> {
                    MusicFolder folder = mediaFile.getFolder();
                    if (folder != null) {
                        musicFileInfoRepository.findByPath(mediaFile.getFullPath().toString()).ifPresent(musicFileInfo -> {
                            mediaFile.setComment(musicFileInfo.getComment());
                            mediaFile.setLastPlayed(musicFileInfo.getLastPlayed());
                            mediaFile.setPlayCount(musicFileInfo.getPlayCount());
                        });
                    }
                    mediaFileRepository.save(mediaFile);
                });
        }

        // persist cover art if not overridden
        coverArtService.persistIfNeeded(mediaFile);
    }

    /**
     * Increments the play count and last played date for the given media file and its
     * directory and album.
     */
    @Transactional
    public void incrementPlayCount(Player player, MediaFile file) {
        Instant now = Instant.now();

        Pair<Integer, Instant> lastPlayedInfo = lastPlayed.computeIfAbsent(player.getId(), k -> Pair.of(file.getId(), now));
        if (lastPlayedInfo.getLeft() == file.getId()) {
            Double threshold = Math.max(1.0, file.getDuration() / 2);
            if (Duration.between(lastPlayedInfo.getRight(), now).getSeconds() < threshold) {
                return;
            }
        }
        file.setLastPlayed(now);
        file.setPlayCount(file.getPlayCount() + 1);
        updateMediaFile(file);

        MediaFile parent = getParentOf(file);
        if (Objects.nonNull(parent) && !isRoot(parent)) {
            parent.setLastPlayed(now);
            parent.setPlayCount(parent.getPlayCount() + 1);
            updateMediaFile(parent);
        }

        albumRepository.findByArtistAndName(file.getAlbumArtist(), file.getAlbumName()).ifPresent(album -> {
                album.setLastPlayed(now);
                album.incrementPlayCount();
                albumRepository.save(album);
            }
        );

        lastPlayed.put(player.getId(), Pair.of(file.getId(), now));
    }

    public List<MediaFileEntry> toMediaFileEntryList(List<MediaFile> files, String username, boolean calculateStarred, boolean calculateFolderAccess,
            Function<MediaFile, String> streamUrlGenerator, Function<MediaFile, String> remoteStreamUrlGenerator,
            Function<MediaFile, String> remoteCoverArtUrlGenerator) {
        Locale locale = Optional.ofNullable(username).map(localeResolver::resolveLocale).orElse(null);
        List<MediaFileEntry> entries = new ArrayList<>(files.size());
        for (MediaFile file : files) {
            String streamUrl = Optional.ofNullable(streamUrlGenerator).map(g -> g.apply(file)).orElse(null);
            String remoteStreamUrl = Optional.ofNullable(remoteStreamUrlGenerator).map(g -> g.apply(file)).orElse(null);
            String remoteCoverArtUrl = Optional.ofNullable(remoteCoverArtUrlGenerator).map(g -> g.apply(file)).orElse(null);

            boolean starred = calculateStarred && username != null && getMediaFileStarredDate(file, username) != null;
            boolean folderAccess = !calculateFolderAccess || username == null || securityService.isFolderAccessAllowed(file, username);
            entries.add(MediaFileEntry.fromMediaFile(file, locale, starred, folderAccess, streamUrl, remoteStreamUrl, remoteCoverArtUrl));
        }

        return entries;
    }

    public int getAlbumCount(List<MusicFolder> musicFolders) {
        if (CollectionUtils.isEmpty(musicFolders)) {
            return 0;
        }
        return mediaFileRepository.countByFolderInAndMediaTypeAndPresentTrue(musicFolders, MediaType.ALBUM);
    }

    public int getPlayedAlbumCount(List<MusicFolder> musicFolders) {
        if (CollectionUtils.isEmpty(musicFolders)) {
            return 0;
        }
        return mediaFileRepository.countByFolderInAndMediaTypeAndPlayCountGreaterThanAndPresentTrue(musicFolders, MediaType.ALBUM, 0);
    }

    public int getStarredAlbumCount(String username, List<MusicFolder> musicFolders) {
        if (CollectionUtils.isEmpty(musicFolders)) {
            return 0;
        }
        return starredMediaFileRepository.countByUsernameAndMediaFileMediaTypeAndMediaFileFolderInAndMediaFilePresentTrue(username, MediaType.ALBUM, musicFolders);
    }

    /**
     * star media files
     *
     * @param ids     media file ids to star
     * @param username username who stars the media files
     */
    @Transactional
    public void starMediaFiles(List<Integer> ids, String username) {
        if (CollectionUtils.isEmpty(ids) || StringUtils.isEmpty(username)) {
            return;
        }
        List<MediaFile> mediaFiles = mediaFileRepository.findAllById(ids);
        mediaFiles.forEach(m -> {
            starredMediaFileRepository.findByUsernameAndMediaFile(username, m).ifPresentOrElse(starredMediaFile -> {
                starredMediaFile.setCreated(Instant.now().truncatedTo(ChronoUnit.MICROS));
                starredMediaFileRepository.save(starredMediaFile);
            }, () -> {
                    StarredMediaFile starredMediaFile = new StarredMediaFile(m, username, Instant.now().truncatedTo(ChronoUnit.MICROS));
                    starredMediaFileRepository.save(starredMediaFile);
                });
        });
    }

    /**
     * unstar media files
     *
     * @param ids    media file ids to unstar
     * @param username username who unstars the media files
     */
    @Transactional
    public void unstarMediaFiles(List<Integer> ids, String username) {
        if (CollectionUtils.isEmpty(ids) || StringUtils.isEmpty(username)) {
            return;
        }
        starredMediaFileRepository.deleteAllByMediaFileIdInAndUsername(ids, username);
    }

    /**
     * mark media files present
     *
     * @param paths paths to mark present by folder id
     * @param lastScanned last scanned time
     * @return true if success, false otherwise
     */
    @Transactional
    public boolean markPresent(Map<Integer, Set<String>> paths, Instant lastScanned) {

        final int BATCH_SIZE = 30000;

        if (CollectionUtils.isEmpty(paths)) {
            return true;
        }
        try {
            paths.entrySet().parallelStream().map(e -> {
                MusicFolder folder = mediaFolderService.getMusicFolderById(e.getKey());
                if (folder == null) {
                    return true;
                }
                Set<String> pathsInFolder = e.getValue();
                int batches = (pathsInFolder.size() - 1) / BATCH_SIZE;
                List<String> pathsInFolderList = new ArrayList<>(pathsInFolder);
                Integer savedCount = IntStream.rangeClosed(0, batches).parallel().map(b -> {
                    try {
                        List<String> subList = pathsInFolderList.subList(b * BATCH_SIZE, Math.min((b + 1) * BATCH_SIZE, pathsInFolderList.size()));
                        return mediaFileRepository.markPresent(folder, subList, lastScanned);
                    } catch (Exception ex) {
                        LOG.warn("Error marking media files present", ex);
                        return 0;
                    }
                }).sum();
                return savedCount == pathsInFolder.size();
            }).reduce(true, (a, b) -> a && b);
            return true;
        } catch (Exception e) {
            LOG.warn("Error marking media files present", e);
            return false;
        }
    }

    /**
     * mark media files non present
     * @param lastScanned last scanned time before which media files are marked non present
     */
    @Transactional
    public void markNonPresent(Instant lastScanned) {
        mediaFileRepository.markNonPresent(Instant.ofEpochMilli(1), lastScanned);
    }

    /**
     * mark media files non present only within specified folders
     * @param folderIds folder IDs to restrict the mark to
     * @param lastScanned last scanned time before which media files are marked non present
     */
    @Transactional
    public void markNonPresent(List<Integer> folderIds, Instant lastScanned) {
        mediaFileRepository.markNonPresentByFolderIds(folderIds, Instant.ofEpochMilli(1), lastScanned);
    }

    /**
     * soft delete media file
     *
     * @param file media file to delete
     * @return deleted media file
     */
    @Transactional
    public MediaFile delete(MediaFile file) {
        if (file == null) {
            return null;
        }
        mediaFileCache.removeMediaFile(file);
        file.setPresent(false);
        file.setChildrenLastUpdated(Instant.ofEpochMilli(1));
        mediaFileRepository.save(file);
        coverArtService.delete(EntityType.MEDIA_FILE, file.getId());

        // delete children recursively
        if (file.isDirectory()) {
            List<MediaFile> children = mediaFileRepository.findByFolderAndParentPathAndPresentTrue(file.getFolder(), file.getPath());
            children.forEach(this::delete);
        }
        return file;
    }

    public String calcAlbumThirdCaption(HomeController.Album album, Locale locale) {
        String caption3 = "";

        if (album.getPlayCount() != null) {
            caption3 = messageSource.getMessage("home.playcount", new Object[] {album.getPlayCount()}, locale);
        }
        if (album.getLastPlayed() != null) {
            DateTimeFormatter dateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withZone(ZoneId.systemDefault()).withLocale(locale);
            String lastPlayedDate = dateFormat.format(album.getLastPlayed());
            caption3 = messageSource.getMessage("home.lastplayed", new Object[] {lastPlayedDate}, locale);
        }
        if (album.getCreated() != null) {
            DateTimeFormatter dateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withZone(ZoneId.systemDefault()).withLocale(locale);
            String creationDate = dateFormat.format(album.getCreated());
            caption3 = messageSource.getMessage("home.created", new Object[] {creationDate}, locale);
        }
        if (album.getYear() != null) {
            caption3 = album.getYear().toString();
        }

        return caption3;
    }

    /**
     * delete all media files that are not present on disk
     */
    @Transactional
    public void expunge() {
        mediaFileRepository.deleteAllByPresentFalse();
    }

    /**
     * Splits every raw GENRE tag value by the configured separators, maps each token through
     * {@link MetaDataParser#mapGenre(String)} (so ID3v1 numeric codes like {@code "(17)"} become
     * {@code "Rock"}, matching the single-genre column's behaviour), de-duplicates preserving
     * order, and joins with the primary separator into a packed column value. Returns null when
     * the input yields no genres so the column stays null for tagless tracks.
     */
    String packGenres(List<String> rawGenres) {
        if (rawGenres == null || rawGenres.isEmpty()) {
            return null;
        }
        String separators = settingsService.getGenreSeparators();
        List<String> clean = rawGenres.stream()
                .flatMap(g -> Genres.split(g, separators).stream())
                .map(MetaDataParser::mapGenre)
                .distinct()
                .toList();
        if (clean.isEmpty()) {
            return null;
        }
        String separator = (separators == null || separators.isEmpty()) ? ";" : separators.substring(0, 1);
        return String.join(separator, clean);
    }

    /**
     * Internal delimiter for packed multi-value AlbumID3 columns (releaseTypes, recordLabels).
     * Newline is collision-resistant: release-type controlled vocab and record-label names never
     * contain it, so a label like {@code "Sony/BMG; Columbia"} round-trips unmangled even though
     * the genre separator setting may include {@code ';'} or {@code ','}. Must match the constant
     * by the same name in {@link JaxbContentService}.
     */
    static final String MULTI_VALUE_DELIMITER = "\n";

    /**
     * Packs a multi-value tag list into a single column string for storage. The input is expected
     * to be already trimmed and blank-filtered by {@link org.airsonic.player.service.metadata.JaudiotaggerParser#getAllTagFields};
     * this helper de-duplicates preserving order and joins with {@link #MULTI_VALUE_DELIMITER}.
     * Returns null when the result is empty so the column stays null for tagless tracks.
     */
    String packMultiValue(List<String> rawValues) {
        if (rawValues == null || rawValues.isEmpty()) {
            return null;
        }
        List<String> clean = rawValues.stream().distinct().toList();
        if (clean.isEmpty()) {
            return null;
        }
        return String.join(MULTI_VALUE_DELIMITER, clean);
    }
}
