package org.azify.client;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import okhttp3.HttpUrl;

final class Catalog {
    static final String ROOT = "root";
    static final String ALBUMS = "albums";
    static final String FOLDERS = "folder:";
    static final String ALL = "all";
    static final String DOWNLOADS = "downloads";
    static final String OFFLINE = "offline:";

    enum Kind { ROOT, FOLDER, ALBUM, TRACK, ALL }

    static final class Entry {
        final String id;
        final String title;
        final String subtitle;
        final Kind kind;
        final String url;
        final String artwork;
        final String album;

        Entry(String id, String title, String subtitle, Kind kind,
                String url, String artwork, String album) {
            this.id = id;
            this.title = title;
            this.subtitle = subtitle;
            this.kind = kind;
            this.url = url;
            this.artwork = artwork;
            this.album = album;
        }

        boolean browsable() {
            return kind == Kind.ROOT || kind == Kind.FOLDER || kind == Kind.ALBUM;
        }

        boolean playable() {
            return kind == Kind.TRACK || kind == Kind.ALBUM || kind == Kind.ALL;
        }
    }

    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final Map<String, List<Entry>> children = new LinkedHashMap<>();
    private final Map<String, Integer> starts = new LinkedHashMap<>();
    private final List<Entry> tracks = new ArrayList<>();
    private String sourceJson;

    static Catalog parse(String json, String server) throws JSONException {
        Catalog catalog = new Catalog();
        catalog.sourceJson = json;
        catalog.add(new Entry(ROOT, "AZify", "", Kind.ROOT, null, null, null));
        catalog.add(new Entry(ALBUMS, "Albums", "", Kind.FOLDER, null, null, null));
        catalog.add(new Entry(FOLDERS, "Folders", "", Kind.FOLDER, null, null, null));
        catalog.add(new Entry(ALL, "Play all", "", Kind.ALL, null, null, null));
        catalog.add(new Entry(DOWNLOADS, "Downloads", "", Kind.FOLDER, null, null, null));
        catalog.children.get(ROOT).add(catalog.get(ALBUMS));
        catalog.children.get(ROOT).add(catalog.get(FOLDERS));
        catalog.children.get(ROOT).add(catalog.get(ALL));
        catalog.children.get(ROOT).add(catalog.get(DOWNLOADS));
        JSONArray albums = new JSONArray(json);
        for (int albumIndex = 0; albumIndex < albums.length(); albumIndex++) {
            JSONObject source = albums.getJSONObject(albumIndex);
            String folder = source.getString("folder");
            String name = source.getString("album");
            String albumId = "album:" + folder;
            String cover = source.optString("cover", "/music/default.jpg");
            String artwork = cover.equals("/music/default.jpg")
                    ? AzifyClient.endpoint(server, "nocover.jpg").toString()
                    : mediaUrl(server, cover);
            Entry album = new Entry(albumId, name, folder, Kind.ALBUM, null, artwork, name);
            catalog.add(album);
            catalog.children.get(ALBUMS).add(album);
            catalog.starts.put(albumId, catalog.tracks.size());
            String parent = FOLDERS;
            String path = "";
            for (String segment : folder.split("/")) {
                if (segment.isEmpty()) {
                    continue;
                }
                path = path.isEmpty() ? segment : path + "/" + segment;
                String folderId = FOLDERS + path;
                if (!catalog.entries.containsKey(folderId)) {
                    Entry directory = new Entry(folderId, segment, path, Kind.FOLDER,
                            null, null, null);
                    catalog.add(directory);
                    catalog.children.get(parent).add(directory);
                }
                parent = folderId;
            }
            JSONArray songs = source.getJSONArray("tracks");
            for (int trackIndex = 0; trackIndex < songs.length(); trackIndex++) {
                JSONObject song = songs.getJSONObject(trackIndex);
                String file = song.getString("file");
                String title = song.getString("title");
                int extension = title.lastIndexOf('.');
                Entry track = new Entry("track:" + file,
                        extension > 0 ? title.substring(0, extension) : title,
                        name + "  |  " + song.optString("duration", "--:--"), Kind.TRACK,
                        mediaUrl(server, file), artwork, name);
                if (catalog.entries.containsKey(track.id)) {
                    continue;
                }
                catalog.add(track);
                catalog.starts.put(track.id, catalog.tracks.size());
                catalog.tracks.add(track);
                catalog.children.get(albumId).add(track);
                catalog.children.get(parent).add(track);
            }
        }
        return catalog;
    }

    String json() {
        return sourceJson;
    }

    Catalog onlyTracks(String server, Set<String> urls) throws JSONException {
        JSONArray source = new JSONArray(sourceJson);
        JSONArray kept = new JSONArray();
        for (int index = 0; index < source.length(); index++) {
            JSONObject album = source.getJSONObject(index);
            JSONArray original = album.getJSONArray("tracks");
            JSONArray selected = new JSONArray();
            for (int position = 0; position < original.length(); position++) {
                JSONObject track = original.getJSONObject(position);
                if (urls.contains(mediaUrl(server, track.getString("file")))) {
                    selected.put(track);
                }
            }
            if (selected.length() > 0) {
                album.put("tracks", selected);
                kept.put(album);
            }
        }
        return parse(kept.toString(), server);
    }

    List<Entry> tracksUnder(String id) {
        if (id.startsWith(OFFLINE)) {
            id = id.substring(OFFLINE.length());
        }
        if (id.equals(ALL) || id.equals(DOWNLOADS)) {
            return tracks();
        }
        Entry entry = get(id);
        if (entry == null) {
            return Collections.emptyList();
        }
        if (entry.kind == Kind.TRACK) {
            return Collections.singletonList(entry);
        }
        List<Entry> found = new ArrayList<>();
        for (Entry child : children(id)) {
            found.addAll(tracksUnder(child.id));
        }
        return found;
    }

    static String mediaUrl(String server, String file) {
        if (!file.startsWith("/music/")) {
            throw new IllegalArgumentException("Invalid audio path");
        }
        HttpUrl.Builder builder = HttpUrl.get(AzifyClient.normalizeServer(server)).newBuilder()
            .addPathSegment("music");
        for (String segment : file.substring(7).split("/", -1)) {
            if (segment.isEmpty()) {
                continue;
            }
            String decoded = segment.replace("%23", "#");
            if (decoded.equals(".") || decoded.equals("..") || decoded.contains("\\")) {
                throw new IllegalArgumentException("Invalid audio path");
            }
            builder.addPathSegment(decoded);
        }
        return builder.build().toString();
    }

    private void add(Entry entry) {
        entries.put(entry.id, entry);
        if (entry.browsable()) {
            children.put(entry.id, new ArrayList<>());
        }
    }

    Entry get(String id) {
        return entries.get(id);
    }

    List<Entry> children(String id) {
        List<Entry> result = children.get(id);
        return result == null ? Collections.emptyList() : Collections.unmodifiableList(result);
    }

    List<Entry> tracks() {
        return Collections.unmodifiableList(tracks);
    }

    int startIndex(String id) {
        if (ALL.equals(id)) {
            return tracks.isEmpty() ? -1 : 0;
        }
        Entry entry = get(id);
        if (entry == null || !entry.playable()
                || (entry.kind == Kind.ALBUM && children(id).isEmpty())) {
            return -1;
        }
        return starts.getOrDefault(id, -1);
    }

    List<Entry> search(String query) {
        String needle = query.trim().toLowerCase(Locale.ROOT);
        List<Entry> found = new ArrayList<>();
        if (needle.isEmpty()) {
            return found;
        }
        for (Entry entry : entries.values()) {
            if ((entry.kind == Kind.ALBUM || entry.kind == Kind.TRACK)
                    && (entry.title.toLowerCase(Locale.ROOT).contains(needle)
                    || entry.subtitle.toLowerCase(Locale.ROOT).contains(needle))) {
                found.add(entry);
            }
        }
        return found;
    }

    static <T> List<T> page(List<T> items, int page, int pageSize) {
        if (page < 0 || pageSize < 1) {
            throw new IllegalArgumentException("Invalid page");
        }
        long start = (long) page * pageSize;
        if (start >= items.size()) {
            return Collections.emptyList();
        }
        return items.subList((int) start, (int) Math.min(start + pageSize, items.size()));
    }
}
