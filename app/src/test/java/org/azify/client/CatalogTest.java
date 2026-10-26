package org.azify.client;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.*;

public class CatalogTest {
    private static final String SERVER = "https://example.com/";

    private JSONObject album(String folder, String title, String file) throws Exception {
        return new JSONObject().put("album", title).put("folder", folder)
                .put("cover", "/music/" + folder + "/cover.jpg")
                .put("tracks", new JSONArray().put(new JSONObject()
                        .put("title", "01 Song.mp3").put("file", file).put("duration", "3:20")));
    }

    @Test public void nestedFoldersExposeOnlyImmediateChildrenAndTracks() throws Exception {
        Catalog catalog = Catalog.parse(new JSONArray()
                .put(album("Artist/Disc", "Disc", "/music/Artist/Disc/01 Song.mp3"))
                .put(album("Artist/Other", "Other", "/music/Artist/Other/01 Song.mp3"))
                .toString(), SERVER);
        assertEquals("folder:Artist", catalog.children(Catalog.FOLDERS).get(0).id);
        assertEquals(2, catalog.children("folder:Artist").size());
        assertEquals(Catalog.Kind.TRACK, catalog.children("folder:Artist/Disc").get(0).kind);
        assertEquals(2, catalog.children(Catalog.ALBUMS).size());
        assertEquals(2, catalog.tracks().size());
    }

    @Test public void rootLevelTracksNeverProduceDoubleSlashMusicPaths() throws Exception {
        Catalog catalog = Catalog.parse(new JSONArray()
                .put(album("", "Root", "/music//01 Song.mp3")).toString(), SERVER);
        assertEquals("https://example.com/music/01%20Song.mp3", catalog.tracks().get(0).url);
        assertEquals(1, catalog.children(Catalog.FOLDERS).size());
    }

    @Test public void urlEncodingPreservesAzifyHashesPlusQuestionMarksAndLiteralPercent() {
        assertEquals("https://example.com/music/A%20%23%20B/01%20a+b%3F%25.mp3",
                Catalog.mediaUrl(SERVER, "/music/A %23 B/01 a+b?%.mp3"));
        assertEquals("https://example.com/music/%C3%B1.mp3",
                Catalog.mediaUrl(SERVER, "/music/\u00f1.mp3"));
    }

    @Test public void encodedBasePathsArePreservedWithoutDoubleEncoding() {
        assertEquals("https://example.com:8443/my%20player/music/Disc/01%20%23%20%25.mp3",
                Catalog.mediaUrl("https://example.com:8443/my%20player/", "/music/Disc/01 %23 %.mp3"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsExternalMediaUrls() {
        Catalog.mediaUrl(SERVER, "https://other.example/song.mp3");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsParentTraversal() {
        Catalog.mediaUrl(SERVER, "/music/../pass.txt");
    }

    @Test public void queueStartsAtSelectedTrackAndContinuesAcrossAlbums() throws Exception {
        Catalog catalog = Catalog.parse(new JSONArray()
                .put(album("First", "First", "/music/First/01.mp3"))
                .put(album("Second", "Second", "/music/Second/01.mp3")).toString(), SERVER);
        assertEquals(0, catalog.startIndex(Catalog.ALL));
        assertEquals(1, catalog.startIndex("album:Second"));
        assertEquals(1, catalog.startIndex("track:/music/Second/01.mp3"));
        assertEquals(-1, catalog.startIndex("track:missing"));
        assertEquals(-1, catalog.startIndex("folder:First"));
    }

    @Test public void searchMatchesAlbumsAndTracksWithoutCaseSensitivity() throws Exception {
        Catalog catalog = Catalog.parse(new JSONArray()
                .put(album("Artist/Disc", "Disc", "/music/Artist/Disc/01.mp3")).toString(), SERVER);
        assertEquals(2, catalog.search("DISC").size());
        assertEquals(1, catalog.search("song").size());
        assertTrue(catalog.search("   ").isEmpty());
    }

    @Test public void paginationHandlesOversizedRequestsWithoutIntegerOverflow() {
        assertEquals(Collections.singletonList(3), Catalog.page(Arrays.asList(1, 2, 3), 1, 2));
        assertTrue(Catalog.page(Arrays.asList(1, 2, 3), Integer.MAX_VALUE, Integer.MAX_VALUE).isEmpty());
    }

    @Test public void emptyLibraryHasNoPlayableQueue() throws Exception {
        Catalog catalog = Catalog.parse("[]", SERVER);
        assertEquals(-1, catalog.startIndex(Catalog.ALL));
        assertTrue(catalog.tracks().isEmpty());
        assertEquals(4, catalog.children(Catalog.ROOT).size());
    }

    @Test public void offlineLibraryPreservesFoldersAndRemovesIncompleteAlbums() throws Exception {
        Catalog original = Catalog.parse(new JSONArray()
                .put(album("Artist/First", "First", "/music/Artist/First/01.mp3"))
                .put(album("Artist/Second", "Second", "/music/Artist/Second/01.mp3")).toString(), SERVER);
        Catalog offline = original.onlyTracks(SERVER,
                Collections.singleton("https://example.com/music/Artist/Second/01.mp3"));
        assertEquals(1, offline.tracks().size());
        assertEquals("Second", offline.children(Catalog.ALBUMS).get(0).title);
        assertEquals(1, offline.children("folder:Artist").size());
        assertEquals(2, original.tracks().size());
        assertTrue(original.onlyTracks(SERVER, Collections.emptySet()).tracks().isEmpty());
    }

    @Test public void downloadingAFolderIncludesItsNestedTracksInOrder() throws Exception {
        Catalog catalog = Catalog.parse(new JSONArray()
                .put(album("Artist/First", "First", "/music/Artist/First/01.mp3"))
                .put(album("Artist/Second", "Second", "/music/Artist/Second/01.mp3")).toString(), SERVER);
        assertEquals(2, catalog.tracksUnder("folder:Artist").size());
        assertEquals(1, catalog.tracksUnder("album:Artist/First").size());
        assertEquals(1, catalog.tracksUnder("offline:track:/music/Artist/First/01.mp3").size());
    }
}
