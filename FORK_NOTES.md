# Auxio + yt-dlp fork

Changes compared to upstream Auxio:

- Home tabs: **Artists** and **Genres** removed, new **yt-dlp** tab added (Songs, Albums, Playlists, yt-dlp).
- yt-dlp tab: paste a link, optionally pick a playlist (or skip), audio is downloaded as MP3 via
  youtubedl-android (bundles python + yt-dlp + ffmpeg), saved to `Music/yt-dlp`, indexed and added to the playlist.
- UI is English only (all translations removed).
- Build: `.github/workflows/android.yml` (GitHub Actions) fetches the git submodules and builds a debug APK.

New code lives in `app/src/main/java/org/oxycblt/auxio/ytdlp/`.

## Playlist folders
Every playlist gets a folder (named after the playlist) inside the main music folder: the first folder
chosen in Settings -> Music folders, or the public `Music` folder in MediaStore mode. Songs added to the
playlist are copied there; yt-dlp downloads are saved there directly (or into `yt-dlp` when no playlist is
chosen). Renaming/deleting a playlist does not touch its folder. In MediaStore mode (Android 10+) the
folder appears when the first file is written.
