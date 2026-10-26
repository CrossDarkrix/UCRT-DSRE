# Metadata editor tab

A separate metadata editing tab reads common existing tags with Android MediaMetadataRetriever and writes a new sibling output through FFmpeg 9 remuxing without audio re-encoding. Editable tags are title, artist, album, album artist, composer, genre, date, track, disc, comment, copyright and lyrics. Cover handling supports keep, replace and remove. MP3/M4A-family covers are normalized to MJPEG using the existing cover path; compatible FLAC covers may remain PNG/JPEG. The source file is never overwritten and partial output is deleted on failure.
