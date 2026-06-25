package org.airsonic.player.service;

import org.airsonic.player.config.AirsonicScanConfig;
import org.airsonic.player.domain.MediaLibraryStatistics;
import org.airsonic.player.repository.AlbumRepository;
import org.airsonic.player.repository.ArtistRepository;
import org.airsonic.player.repository.MediaFileRepository;
import org.airsonic.player.service.cache.ArtistByNameCache;
import org.airsonic.player.service.search.IndexManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;


@ExtendWith(MockitoExtension.class)
public class MediaScannerServiceUnitTest {

    @Mock
    private SettingsService settingsService;
    @Mock
    private PlaylistFileService playlistFileService;
    @Mock
    private MediaFileService mediaFileService;
    @Mock
    private MediaFolderService mediaFolderService;
    @Mock
    private CoverArtService coverArtService;
    @Mock
    private ArtistService artistService;
    @Mock
    private AlbumService albumService;
    @Mock
    private TaskSchedulingService taskService;
    @Mock
    private SimpMessagingTemplate messagingTemplate;
    @Mock
    IndexManager indexManager;
    @Mock
    AirsonicScanConfig scanConfig;
    @Mock
    ArtistByNameCache artistByNameCache;
    @Mock
    MediaFileRepository mediaFileRepository;
    @Mock
    ArtistRepository artistRepository;
    @Mock
    AlbumRepository albumRepository;

    @Test
    public void neverScanned() {
        when(settingsService.getIndexCreationInterval()).thenReturn(-1);
        when(settingsService.getIndexCreationHour()).thenReturn(-1);
        when(indexManager.getStatistics()).thenReturn(null);
        MediaScannerService mediaScannerService = new MediaScannerService(settingsService, indexManager, playlistFileService, mediaFileService, mediaFolderService, coverArtService, artistService, albumService, taskService, messagingTemplate, scanConfig, artistByNameCache, mediaFileRepository, artistRepository, albumRepository);
        assertTrue(mediaScannerService.neverScanned());

        when(indexManager.getStatistics()).thenReturn(new MediaLibraryStatistics());
        assertFalse(mediaScannerService.neverScanned());
    }
}
