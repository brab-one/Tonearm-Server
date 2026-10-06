# Tonearm Server

Tonearm Connect for everyone on your Navidrome. The Tonearm apps use it to see and remote-control each
other (phone → desktop), and to share likes of songs that aren't in the library yet.

- Everybody signs in with their own Navidrome login: the apps send the same login they use for music,
  and the server asks Navidrome whether it's valid. Each user only sees their own devices.
- It sits behind the same reverse proxy as Navidrome, at `https://<your music server>/connect-tonearm/`,
  so whatever protects Navidrome (HTTPS, client certificates) protects it too.
- The apps find it by themselves. Without it they fall back to the
  [Tonearm Connect plugin in Lidarr](https://github.com/brab-one/Tonearm-Connect).

Image: `ghcr.io/brab-one/tonearm-server` (amd64 and arm64).

## 1. Start it

### With Dockge

1. Open Dockge and click **+ Compose**.
2. **Stack name:** `tonearm-server`.
3. Replace everything in the compose editor with [docker-compose.yml](docker-compose.yml):

   ```yaml
   services:
     tonearm-server:
       image: ghcr.io/brab-one/tonearm-server:latest
       container_name: tonearm-server
       restart: unless-stopped
       environment:
         # Navidrome as this container reaches it directly, not through the proxy.
         NAVIDROME_URL: http://192.168.1.11:4533
         # Has to match the custom location in Nginx Proxy Manager.
         BASE_PATH: /connect-tonearm
       ports:
         - "8790:8790"
       volumes:
         - tonearm-data:/data

   volumes:
     tonearm-data:
   ```

4. Set `NAVIDROME_URL` to Navidrome's own address and port (not the public https one).
5. Click **Deploy**. The log shows
   `Tonearm server 1.0.0 on port 8790 under /connect-tonearm, checking logins with Navidrome at …`.
6. Check it: open `http://192.168.1.11:8790/connect-tonearm/health`. It should say `ok`.

To update later, open the stack in Dockge and click **Update**.

### With plain Docker Compose

Put `docker-compose.yml` in a folder, change `NAVIDROME_URL`, then:

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

   The apps wait up to 25 s for commands on one request; this keeps the proxy from cutting them off.
5. **Save.** The host's **SSL** settings and **Advanced** tab stay as they are. A client-certificate check
   there (`ssl_verify_client on;`) covers this location too, so the apps' certificate is all they need.
6. Check it: in a browser that has your client certificate, open
   `https://musicdome.brab.one/connect-tonearm/`. It should say `Tonearm server 1.0.0 is running`.

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
address and use it when it's there. Desktop: **Settings → Tonearm Connect** then says
"Online through the Tonearm server". Other people on your Navidrome just sign in to Tonearm with their
own login (and their own client certificate, if your proxy asks for one).

Coming from the Lidarr plugin: nothing to move over. The apps share their likes again on their own, and
the plugin can stay installed or go.

## Settings

| Variable | Default | |
|---|---|---|
| `NAVIDROME_URL` | (required) | Navidrome as the container reaches it, e.g. `http://192.168.1.11:4533` |
| `BASE_PATH` | `/connect-tonearm` | The proxy location; requests without the prefix work too |
| `PORT` | `8790` | |
| `DATA_DIR` | `/data` | Each user's shared likes, one small JSON file per user |

Every request except `/connect-tonearm/health` needs a valid Navidrome login. An address with 20 failed
logins in 5 minutes is turned away for a while.

## Building it yourself

```bash
docker build -t tonearm-server .
```

Or without Docker (JDK 21):

```bash
./gradlew installDist
NAVIDROME_URL=http://192.168.1.11:4533 DATA_DIR=./data build/install/tonearm-server/bin/tonearm-server
```
