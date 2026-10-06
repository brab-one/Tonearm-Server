# Tonearm Server

The Tonearm apps' helper next to your Navidrome, for everyone on it:

- **Tonearm Connect**: the apps see and remote-control each other (phone → desktop) and share likes of
  songs that aren't in the library yet.
- **Lidarr and Maloja without handing out their keys**: the server holds them, so the apps only need the
  music server login. Navidrome admins get all of Lidarr (Brainarr, weekly picks); everyone else can look
  things up, see downloads and request albums and artists, nothing more. Maloja is one person's history,
  so by default only admins get it.
- **AI picks**: albums by artists you don't have, suggested by your Ollama from what each user plays and
  likes on Navidrome, checked against Lidarr's metadata so made-up albums are dropped.

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
   MALOJA_URL=http://192.168.1.11:42010
   MALOJA_API_KEY=<Maloja → Settings → API Keys>
   OLLAMA_URL=http://192.168.1.11:11435
   ```

6. Click **Deploy**. The log lists what's set up:
   `Tonearm server 1.1.0 on port 8790 …`, then a line each for Lidarr, Maloja and Recommendations.
7. Check it: open `http://192.168.1.11:8790/connect-tonearm/health`. It should say `ok`.

To update later, open the stack in Dockge and click **Update**.

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
   `https://musicdome.brab.one/connect-tonearm/`. It should say `Tonearm server 1.1.0 is running`.

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

Nothing to set up. Tonearm phone 1.5.0+ and desktop 1.4.0+ look for the server at the music server's
address and use it when it's there (Lidarr, Maloja and AI picks from phone 1.6.0 / desktop 1.5.0). Desktop:
**Settings → Tonearm Connect** then says "Online through the Tonearm server", and the Lidarr and Maloja
settings say they come through it; whatever is entered there is only used without the server. AI picks are
under Discover on the phone and at the bottom of Home on the desktop. Other people on your Navidrome just
sign in to Tonearm with their own login (and their own client certificate, if your proxy asks for one).

Coming from the Lidarr plugin: nothing to move over. The apps share their likes again on their own, and
the plugin can stay installed or go.

## Settings

| Variable | Default | |
|---|---|---|
| `NAVIDROME_URL` | (required) | Navidrome as the container reaches it, e.g. `http://192.168.1.11:4533` |
| `BASE_PATH` | `/connect-tonearm` | The proxy location; requests without the prefix work too |
| `PORT` | `8790` | |
| `DATA_DIR` | `/data` | Each user's shared likes and AI picks, small JSON files |
| `LIDARR_URL`, `LIDARR_API_KEY` | | Lidarr through the server |
| `LIDARR_REQUESTS` | `all` | `admins`: only Navidrome admins get Lidarr through the server |
| `MALOJA_URL`, `MALOJA_API_KEY` | | Maloja through the server |
| `MALOJA_USERS` | (Navidrome admins) | Comma-separated Navidrome users who get Maloja |
| `OLLAMA_URL` | | Ollama for AI picks |
| `OLLAMA_MODEL` | `qwen2.5` | |

Every request except `/connect-tonearm/health` needs a valid Navidrome login. An address with 20 failed
logins in 5 minutes is turned away for a while. Lidarr's and Maloja's keys never leave the server; what
non-admins may do in Lidarr is checked call by call (their requests go into one of Lidarr's root folders,
without tags). AI picks are made in the background (a few minutes on a small GPU), kept for a week, and
renewed when a user asks.

## Building it yourself

```bash
docker build -t tonearm-server .
```

Or without Docker (JDK 21):

```bash
./gradlew installDist
NAVIDROME_URL=http://192.168.1.11:4533 DATA_DIR=./data build/install/tonearm-server/bin/tonearm-server
```
