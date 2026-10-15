Before starting any work in this repository, read the project notes:

    base64 -d .claude/notes.b64 | gunzip

Follow them. When the notes need to change, decode, edit, and re-encode with:

    gzip -9n -c notes.md | base64 -w 76 > .claude/notes.b64
