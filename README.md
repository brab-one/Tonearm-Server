# Tonearm Server

The Tonearm apps' helper next to your Navidrome, for everyone on it:

- **Tonearm Connect**: the apps see and remote-control each other (phone → desktop) and share likes of
  songs that aren't in the library yet.
- **Lidarr without handing out its key**: the server holds it, so the apps only need the music server
  login. Navidrome admins get all of Lidarr (weekly picks included); everyone else can look things up, see
  downloads and request albums and artists, nothing more.
- **Listening history**: the apps tell it every song they play, YouTube Music ones and skipped ones too, so
  the picks follow what each user really listens to, not only what's in the library. The phone's heavy
  rotation, rediscover and Daily Discovery come from it. No Maloja needed (see [Moving off Maloja](#moving-off-maloja)).
- **AI picks**: albums by artists you don't have, suggested by the AI of your choice (your own Ollama, any
  service with OpenAI's API, or Claude) from what each user plays, likes, skips and said no to, checked
  against Lidarr's metadata so made-up albums are dropped, and so are singles, EPs, live albums and
  compilations (unless "more like" is about one). Best fit first, one album per artist. Earlier picks only
  count as a hit once they're played again at least half a day after the first time, or liked. The apps can ask for "more like this" (a song, album, artist or playlist),
  and their **weekly picks** download the first few every week, as a playlist of their standout songs. With
  `PICKS_FOLDER`, each user's weekly picks land in a library only they see ([Private weekly picks](#private-weekly-picks)).
- **Search help**: the apps search Deezer (songs, albums, artists) through it alongside the library and YouTube
  Music, and can ask the AI what a search means ("dreamy 90s trip hop").
- **Discovery picks and similar artists**, no AI needed: the artists each user plays and likes most, their
  related artists on Deezer's public API (no account or key), ranked by how many of yours point to them,
  without what the library has, each with its best-known album. Artists someone keeps playing on YouTube
  Music without having them come first. Artist pages in the apps use the same source for similar artists.
- **Not for me**: a thumbs-down on any pick in the apps keeps that artist out of both kinds of picks, until
  they're played a few times again.
- **Disliked songs**: the apps' thumbs-down on a song is kept here, so the phone and the desktop share it. The
  AI is told to avoid music like them, discovery weighs their artists down, and "Ask the AI" leaves them out.

Everybody signs in with their own Navidrome login: the apps send the same login they use for music,
and the server asks Navidrome whether it's valid (and whether they're an admin).
- It sits behind the same reverse proxy as Navidrome, at `https://<your music server>/connect-tonearm/`,
  so whatever protects Navidrome (HTTPS, client certificates) protects it too.
- The apps find it by themselves. Without it they fall back to the
  [Tonearm Connect plugin in Lidarr](https://github.com/brab-one/Tonearm-Connect).

Image: `ghcr.io/brab-one/tonearm-server` (amd64 and arm64).

## 1. Start it

### With Dockge

1. Open Dockge and click **+ Compose**.
2. **Stack name:** `tonearm-server`.
3. Replace everything in the compose editor with [docker-compose.yml](docker-compose.yml).
4. Set `NAVIDROME_URL` to Navidrome's own address and port (not the public https one).
5. In the **.env** box under it, add what the server should hold (leave out what you don't use):

   ```
   LIDARR_URL=http://192.168.1.11:8686
   LIDARR_API_KEY=<Lidarr → Settings → General → API Key>
   AI_PROVIDER=ollama
   AI_URL=http://192.168.1.11:11435
   ```

   For another AI, see [Choosing the AI](#choosing-the-ai). Everything in the `.env` box reaches the server as it
   is; with a compose file from before 1.5.3, which listed each setting, only the listed ones did, so take the
   new one.
6. Click **Deploy**. The log lists what's set up:
   `Tonearm server 1.5.0 on port 8790 …`, then a line each for Lidarr, the AI and discovery.
7. Check it: open `http://192.168.1.11:8790/connect-tonearm/health`. It should say `ok`.

To update later, open the stack in Dockge and click **Update**.

The log's `Data:` line should say `/data is writable`. The container gives `/data` to the server's user
(uid 1000) when it starts, also when it's a folder mounted from the host; where the host doesn't allow that
(some NAS shares), it runs the server as root instead and says so in the log.

### With plain Docker Compose

Put `docker-compose.yml` in a folder, change `NAVIDROME_URL`, put the optional settings in a `.env` next to
it (as above), then:

```bash
docker compose up -d
```

## 2. Nginx Proxy Manager

1. **Hosts → Proxy Hosts**, then **⋮ → Edit** on your music server's host (e.g. `musicdome.brab.one`).
2. Open the **Custom locations** tab and click **Add location**.
3. Fill in:
   - **Define location:** `/connect-tonearm`
   - **Scheme:** `http`
   - **Forward Hostname / IP:** `192.168.1.11` (just the address, no slash or path)
   - **Forward Port:** `8790`
4. Click the **gear** next to the location and paste:

   ```nginx
   proxy_read_timeout 90s;
   proxy_send_timeout 90s;
   proxy_buffering off;
   ```

   The apps wait up to 25 s for commands on one request, and Lidarr searches can take a while; this keeps
   the proxy from cutting them off.
5. **Save.** The host's **SSL** settings and **Advanced** tab stay as they are. A client-certificate check
   there (`ssl_verify_client on;`) covers this location too, so the apps' certificate is all they need.
6. Check it: in a browser that has your client certificate, open
   `https://musicdome.brab.one/connect-tonearm/`. It should say `Tonearm server 1.5.0 is running`.

If your host's **Advanced** tab already has its own `location` blocks, add this one there instead of
using the Custom locations tab:

```nginx
location /connect-tonearm {
    proxy_pass http://192.168.1.11:8790;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_read_timeout 90s;
    proxy_send_timeout 90s;
    proxy_buffering off;
}
```

## 3. The apps

Nothing to set up: the apps look for the server at the music server's address and use it when it's there.
Desktop: **Settings → Tonearm Connect** then says "Online through the Tonearm server". Discovery and AI picks
are under Discover in both apps. Phone 1.10.0 and desktop 1.10.0 send what they play to the history; older
ones still work, they just don't add to it. Other people on your Navidrome just sign in to Tonearm with their
own login (and their own client certificate, if your proxy asks for one).

## Choosing the AI

Set `AI_PROVIDER` and what goes with it in the stack's `.env`, then **Update** the stack. The log says which
AI answers, and the apps show the model under AI picks.

| | `AI_PROVIDER` | `AI_URL` | `AI_API_KEY` | `AI_MODEL` |
|---|---|---|---|---|
| Ollama (your own) | `ollama` | `http://192.168.1.11:11434` | | default `qwen2.5`; `gemma4:26b` knows music much better (needs ~18 GB of RAM or VRAM) |
| OpenAI | `openai` | (leave out) | `sk-…` | the model's name, e.g. from platform.openai.com/docs/models |
| OpenRouter (many models, one key) | `openai` | `https://openrouter.ai/api/v1` | `sk-or-…` | e.g. `anthropic/…`, `google/…` as OpenRouter lists them |
| Google Gemini | `openai` | `https://generativelanguage.googleapis.com/v1beta/openai` | Gemini API key | e.g. a `gemini-…` model |
| LM Studio, llama.cpp, vLLM, LocalAI | `openai` | e.g. `http://192.168.1.11:1234/v1` | (if it wants one) | the loaded model |
| Claude | `claude` | (leave out) | `sk-ant-…` (console.anthropic.com) | default `claude-opus-5-5`; `claude-sonnet-5-5` or `claude-haiku-4-5` cost less |

With Ollama, the server downloads the model into Ollama when it doesn't have it yet (the log shows the
progress), so trying another model is just changing `AI_MODEL` and clicking **Update**; `AI_PULL=off` leaves
downloading to you. Models Ollama no longer needs stay on its disk until you remove them (`ollama rm <model>`).

For picks, a model that can think (gemma4, qwen3 and the like) thinks before answering: slower, but it
remembers more albums. AI search is asked without thinking, for speed. Models that can't think are asked
without, by themselves. Every question uses the same context length (`AI_CONTEXT`, 16384 tokens), because
another one makes Ollama load the model again.

To see how the picks are made, each run leaves a line in the log (`AI picks for alice: kept 12 of 20 (left
out: in the library 3, not in Lidarr 2, Live 1), thought 4.2k chars, 3100 + 2900 tokens, 140 s`). In
`DATA_DIR/recommendations/`, `<user>.runs.jsonl` keeps the last 1,000 of those runs, `<user>.picks.jsonl`
the last 5,000 picks, and `<user>.prompt.txt` what the model was last asked.

`openai` covers anything with OpenAI's chat completions API: it asks for an answer fitting a JSON schema, and
for plain JSON from servers that can't do that. A run asks for 20 albums with a few thousand words of
listening history, so with a paid service each run costs a few cents; picks are renewed weekly or when
someone asks. Without `AI_PROVIDER`, `OLLAMA_URL` (and `OLLAMA_MODEL`) alone still mean Ollama, as before.

## Private weekly picks

Weekly picks download albums the AI picked for one person. Without more setup they go into the shared library,
so everyone sees them, and only admins get weekly picks. With a picks folder, each user's picks go into a
folder of their own, which Navidrome shows as a library only that user can open (admins see every library).
Then everyone gets weekly picks, not only admins. A week later the albums are deleted again, unless the user
liked a song from them or put one in another playlist.

Navidrome libraries can't overlap, so the picks folder has to be **outside** your music folder:

1. Make the folder on the host, next to your music, e.g. `/mnt/tank/media/picks`, owned by the same user as
   your music folder (the one Lidarr writes as).
2. Mount it into **Lidarr** and **Navidrome** at the same path, e.g. `/picks`, in both stacks in Dockge, and
   **Update** them:
   ```yaml
       volumes:
         - /mnt/tank/media/picks:/picks
   ```
3. Add `PICKS_FOLDER=/picks` to this server's `.env` and **Update** it. The log says
   `Weekly picks: each user's in their own folder in /picks`.
4. For each user (the folder is their Navidrome login in lowercase; a login with spaces or other signs a path
   can't hold gets a short code added, and the apps show the exact path when it's missing):
   - make `/mnt/tank/media/picks/<user>` (owned like the music folder);
   - in Navidrome, **Settings → Libraries → +**: name it e.g. `<user>'s picks`, path `/picks/<user>`, and
     give only that user access.

   The server adds the folder to Lidarr by itself (named `Tonearm picks: <user>`) when that user's first weekly
   picks start. Until the folder is there, the apps say what's missing.

Everything else stays shared: albums someone requests or likes go into your usual root folder as before, and a
pick whose artist Lidarr already keeps elsewhere is skipped, since an album always goes where its artist is. For
the same reason, nobody can request, monitor or search for an album by an artist who is in someone's picks (the
apps say so). That stays as long as the artist is there, also when its owner keeps an album; an admin can move such
an artist to a shared root folder in Lidarr (edit the artist, change its root folder, move the files). Non-admins
can only delete what's in their own picks folder (which the weekly clean-up does for them).

## Moving off Maloja

The apps don't use Maloja anymore; the history lives here now. To bring what Maloja has over, leave
`MALOJA_URL` and `MALOJA_API_KEY` in the `.env` when you update to 1.5.0. The first time a Navidrome admin
(or one of `MALOJA_USERS`) opens an app, its scrobbles go into that user's history, once. The log says
`Brought … plays over from Maloja into …'s history`. Then take the `MALOJA_` lines out, and Maloja can go.

## Settings

| Variable | Default | |
|---|---|---|
| `NAVIDROME_URL` | (required) | Navidrome as the container reaches it, e.g. `http://192.168.1.11:4533` |
| `BASE_PATH` | `/connect-tonearm` | The proxy location; requests without the prefix work too |
| `PORT` | `8790` | |
| `DATA_DIR` | `/data` | Each user's listening history, shared likes and picks, small JSON files |
| `LIDARR_URL`, `LIDARR_API_KEY` | | Lidarr through the server |
| `LIDARR_REQUESTS` | `all` | `admins`: only Navidrome admins get Lidarr through the server |
| `PICKS_FOLDER` | | A folder (as Lidarr sees it) holding one folder per user for their weekly picks ([Private weekly picks](#private-weekly-picks)) |
| `AI_PROVIDER` | | `ollama`, `openai` or `claude`: the AI for AI picks and search ([Choosing the AI](#choosing-the-ai)) |
| `AI_URL`, `AI_API_KEY`, `AI_MODEL` | | Where it is, its key, which model |
| `AI_PULL` | on | `off`: don't download a missing Ollama model |
| `AI_CONTEXT` | `16384` | Ollama's context length in tokens, the same for every question |
| `OLLAMA_URL`, `OLLAMA_MODEL` | `qwen2.5` | The older way to say Ollama |
| `MALOJA_URL`, `MALOJA_API_KEY`, `MALOJA_USERS` | | Only to bring a Maloja's history over once ([Moving off Maloja](#moving-off-maloja)) |
| `DISCOVERY` | on | `off`: no discovery picks or similar artists (they ask Deezer) |

Every request except `/connect-tonearm/health` needs a valid Navidrome login. An address with 20 failed
logins in 5 minutes is turned away for a while. Lidarr's and the AI's keys never leave the server; what
non-admins may do in Lidarr is checked call by call (their requests go into one of Lidarr's root folders,
without tags). AI picks are made in the background (a few minutes on a small GPU), kept for a week, and
renewed when a user asks; discovery picks are kept for a day.

## Building it yourself

```bash
docker build -t tonearm-server .
```

Or without Docker (JDK 21):

```bash
./gradlew installDist
NAVIDROME_URL=http://192.168.1.11:4533 DATA_DIR=./data build/install/tonearm-server/bin/tonearm-server
```
