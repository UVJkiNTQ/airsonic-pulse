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

 Copyright 2025 (C) Airsonic Authors
 Based upon Subsonic, Copyright 2009 (C) Sindre Mehus
 */
package org.airsonic.player.controller;

import org.airsonic.player.domain.MediaFile;
import org.airsonic.player.domain.Player;
import org.airsonic.player.domain.SearchCriteria;
import org.airsonic.player.service.AlbumService;
import org.airsonic.player.service.ArtistService;
import org.airsonic.player.service.JaxbContentService;
import org.airsonic.player.service.MediaFolderService;
import org.airsonic.player.service.SearchService;
import org.airsonic.player.service.SecurityService;
import org.airsonic.player.service.search.IndexType;
import org.apache.commons.lang.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.subsonic.restapi.AlbumID3;
import org.subsonic.restapi.ArtistID3;
import org.subsonic.restapi.Child;
import org.subsonic.restapi.Response;
import org.subsonic.restapi.SearchResult2;
import org.subsonic.restapi.SearchResult3;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.util.List;

import static org.springframework.web.bind.ServletRequestUtils.getIntParameter;

@Controller
@RequestMapping(value = {"/rest", "/ext"}, method = {RequestMethod.GET, RequestMethod.POST})
public class SubsonicSearchController extends AbstractSubsonicController {

    /**
     * Upper bound for any per-request page size on the search endpoints (#262/#285). Counts flow
     * to {@code SearchServiceImpl} as {@code searcher.search(query, offset + count)}, which makes
     * Lucene pre-allocate a {@code TopDocs} collector sized to {@code offset + count} before
     * collecting any document — an authenticated client passing {@code songCount=2147483647} could
     * OOM the JVM. Clamping every count to this ceiling bounds a single page.
     * <p>
     * Offsets are bounded separately by {@link #MAX_OFFSET}: the original #285 clamp applied the
     * same 500 ceiling to offsets, which silently broke deep pagination — a client paging a large
     * library (Symfonium's full-library scan pages artists/albums/songs in 500-entry slices)
     * requests offsets of 2000+ and received the slice at offset 500 every time. Offsets must
     * therefore be allowed to grow deep, while still being capped so {@code offset + count} cannot
     * overflow or allocate an unbounded TopDocs.
     */
    static final int MAX_COUNT = 500;

    /**
     * Upper bound for any per-request offset on the search endpoints. Large enough to page through
     * a multi-million-entry library (a 1M track library in 500-entry slices needs offsets to
     * ~1M), while capping the {@code searcher.search(query, offset + count)} TopDocs allocation at
     * ~{@code MAX_OFFSET} (~30 MB) — a single deep page is harmless, and the {@code
     * Integer.MAX_VALUE} overflow path (#285) is closed.
     */
    static final int MAX_OFFSET = 1_000_000;

    @Autowired
    private SearchService searchService;
    @Autowired
    private ArtistService artistService;
    @Autowired
    private AlbumService albumService;
    @Autowired
    private MediaFolderService mediaFolderService;
    @Autowired
    private SecurityService securityService;
    @Autowired
    private JaxbContentService jaxbContentService;

    @RequestMapping({"/search", "/search.view"})
    public void search(HttpServletRequest request, HttpServletResponse response) throws Exception {
        request = wrapRequest(request);
        String username = securityService.getCurrentUsername(request);
        Player player = playerService.getPlayer(request, response, username);

        String any = request.getParameter("any");
        String artist = request.getParameter("artist");
        String album = request.getParameter("album");
        String title = request.getParameter("title");

        StringBuilder query = new StringBuilder();
        if (any != null) {
            query.append(any).append(" ");
        }
        if (artist != null) {
            query.append(artist).append(" ");
        }
        if (album != null) {
            query.append(album).append(" ");
        }
        if (title != null) {
            query.append(title);
        }

        SearchCriteria criteria = new SearchCriteria();
        criteria.setQuery(query.toString().trim());
        criteria.setCount(clampCount(getIntParameter(request, "count", 20)));
        criteria.setOffset(clampOffset(getIntParameter(request, "offset", 0)));
        List<org.airsonic.player.domain.MusicFolder> musicFolders = mediaFolderService.getMusicFoldersForUser(username);

        org.airsonic.player.domain.SearchResult result = searchService.search(criteria, musicFolders, IndexType.SONG);
        org.subsonic.restapi.SearchResult searchResult = new org.subsonic.restapi.SearchResult();
        searchResult.setOffset(result.getOffset());
        searchResult.setTotalHits(result.getTotalHits());

        for (MediaFile mediaFile : result.getMediaFiles()) {
            if (mediaFileService.showMediaFile(mediaFile)) {
                searchResult.getMatch().add(jaxbContentService.createJaxbChild(player, mediaFile, username));
            }
        }
        Response res = createResponse();
        res.setSearchResult(searchResult);
        jaxbWriter.writeResponse(request, response, res);
    }

    @RequestMapping({"/search2", "/search2.view"})
    public void search2(HttpServletRequest request, HttpServletResponse response) throws Exception {
        request = wrapRequest(request);
        String username = securityService.getCurrentUsername(request);
        Player player = playerService.getPlayer(request, response, username);
        Integer musicFolderId = getIntParameter(request, "musicFolderId");
        List<org.airsonic.player.domain.MusicFolder> musicFolders = mediaFolderService.getMusicFoldersForUser(username, musicFolderId);

        SearchResult2 searchResult = new SearchResult2();

        String query = request.getParameter("query");
        SearchCriteria criteria = new SearchCriteria();
        criteria.setQuery(StringUtils.trimToEmpty(query));
        criteria.setCount(clampCount(getIntParameter(request, "artistCount", 20)));
        criteria.setOffset(clampOffset(getIntParameter(request, "artistOffset", 0)));
        org.airsonic.player.domain.SearchResult artists = searchService.search(criteria, musicFolders, IndexType.ARTIST);
        for (MediaFile mediaFile : artists.getMediaFiles()) {
            searchResult.getArtist().add(jaxbContentService.createJaxbArtist(mediaFile, username));
        }

        criteria.setCount(clampCount(getIntParameter(request, "albumCount", 20)));
        criteria.setOffset(clampOffset(getIntParameter(request, "albumOffset", 0)));
        org.airsonic.player.domain.SearchResult albums = searchService.search(criteria, musicFolders, IndexType.ALBUM);
        jaxbContentService.createJaxbChildren(player, albums.getMediaFiles(), username, mediaFile -> new Child())
                .forEach(searchResult.getAlbum()::add);

        criteria.setCount(clampCount(getIntParameter(request, "songCount", 20)));
        criteria.setOffset(clampOffset(getIntParameter(request, "songOffset", 0)));
        org.airsonic.player.domain.SearchResult songs = searchService.search(criteria, musicFolders, IndexType.SONG);
        jaxbContentService.createJaxbChildren(player, songs.getMediaFiles().stream().filter(mediaFileService::showMediaFile).toList(), username, mediaFile -> new Child())
                .forEach(searchResult.getSong()::add);

        Response res = createResponse();
        res.setSearchResult2(searchResult);
        jaxbWriter.writeResponse(request, response, res);
    }

    @RequestMapping({"/search3", "/search3.view"})
    public void search3(HttpServletRequest request, HttpServletResponse response) throws Exception {
        request = wrapRequest(request);
        String username = securityService.getCurrentUsername(request);
        Player player = playerService.getPlayer(request, response, username);
        Integer musicFolderId = getIntParameter(request, "musicFolderId");
        List<org.airsonic.player.domain.MusicFolder> musicFolders = mediaFolderService.getMusicFoldersForUser(username, musicFolderId);

        SearchResult3 searchResult = new SearchResult3();

        String query = request.getParameter("query");
        // replace empty string with null
        query = "\"\"".equals(query) ? null : query;
        int songCount = clampCount(getIntParameter(request, "songCount", 20));
        int songOffset = clampOffset(getIntParameter(request, "songOffset", 0));
        int albumCount = clampCount(getIntParameter(request, "albumCount", 20));
        int albumOffset = clampOffset(getIntParameter(request, "albumOffset", 0));
        int artistCount = clampCount(getIntParameter(request, "artistCount", 20));
        int artistOffset = clampOffset(getIntParameter(request, "artistOffset", 0));
        if (StringUtils.isEmpty(query)) {
            if (artistCount > 0) {
                jaxbContentService.createJaxbArtists(artistService.getArtists(musicFolders, artistCount, artistOffset), username, artist -> new ArtistID3())
                        .forEach(searchResult.getArtist()::add);
            }
            if (albumCount > 0) {
                jaxbContentService.createJaxbAlbums(albumService.getAlbums(musicFolders, albumCount, albumOffset), username, album -> new AlbumID3())
                        .forEach(searchResult.getAlbum()::add);
            }
            if (songCount > 0) {
                jaxbContentService.createJaxbChildren(player, mediaFileService.getSongs(musicFolders, songCount, songOffset), username, song -> new Child())
                        .forEach(searchResult.getSong()::add);
            }
        } else {
            SearchCriteria criteria = new SearchCriteria();
            criteria.setQuery(StringUtils.trimToEmpty(query));
            criteria.setCount(artistCount);
            criteria.setOffset(artistOffset);
            org.airsonic.player.domain.SearchResult result = searchService.search(criteria, musicFolders, IndexType.ARTIST_ID3);
            jaxbContentService.createJaxbArtists(result.getArtists(), username, artist -> new ArtistID3())
                    .forEach(searchResult.getArtist()::add);

            criteria.setCount(albumCount);
            criteria.setOffset(albumOffset);
            result = searchService.search(criteria, musicFolders, IndexType.ALBUM_ID3);
            jaxbContentService.createJaxbAlbums(result.getAlbums(), username, album -> new AlbumID3())
                    .forEach(searchResult.getAlbum()::add);

            criteria.setCount(songCount);
            criteria.setOffset(songOffset);
            result = searchService.search(criteria, musicFolders, IndexType.SONG);
            jaxbContentService.createJaxbChildren(player, result.getMediaFiles().stream().filter(mediaFileService::showMediaFile).toList(), username, song -> new Child())
                    .forEach(searchResult.getSong()::add);
        }

        Response res = createResponse();
        res.setSearchResult3(searchResult);
        jaxbWriter.writeResponse(request, response, res);
    }

    /**
     * Bounds a per-request page size to {@code [0, MAX_COUNT]} (#262). Silent — a client
     * requesting more simply receives {@code MAX_COUNT} back rather than an error. The lower floor
     * also normalises a negative value (which {@code getIntParameter} passes through unchanged).
     */
    static int clampCount(int value) {
        return Math.max(0, Math.min(MAX_COUNT, value));
    }

    /**
     * Bounds a per-request offset to {@code [0, MAX_OFFSET]}. Keeps {@code offset + count} from
     * overflowing (and the TopDocs allocation bounded) while permitting legitimate deep pagination
     * through a large library.
     */
    static int clampOffset(int value) {
        return Math.max(0, Math.min(MAX_OFFSET, value));
    }

}
