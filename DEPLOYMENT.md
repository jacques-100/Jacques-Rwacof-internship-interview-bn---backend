# Deploying the backend (API + database)

The API is a Java program, so it cannot run on shared cPanel hosting. It runs on a small Linux server
(a VPS or a free cloud VM) with Docker. This takes about 30 minutes the first time.

```text
Browser / Android app --HTTPS--> api.alpharesearchlabs.org --> Caddy --> Spring Boot API --> MySQL
Web app files ------------------> cherrytrack.alpharesearchlabs.org   (cPanel, see the frontend repo)
```

Both names are under `alpharesearchlabs.org`, so the browser treats them as the same site and the secure
sign-in cookie keeps working. Use other names if you like; they only appear in `deploy/.env`.

## What you need
- A Linux server, **Ubuntu 22.04 or 24.04, at least 2 GB RAM**, with a public IP address. Options:
  Oracle Cloud "Always Free" VM, or a small VPS from Namecheap, Hetzner or DigitalOcean (about $5/month).
- Access to your domain's DNS (cPanel, Zone Editor).

## 1. Point `api.alpharesearchlabs.org` at the server
In cPanel open **Domains > Zone Editor > Manage** for `alpharesearchlabs.org`, then **Add Record**:

| Type | Name | Address / Value | TTL |
| --- | --- | --- | --- |
| A | `api` | the server's public IP | 300 |

If the domain's DNS is managed in the Namecheap dashboard instead, add the same record under
**Advanced DNS**. Check it from your PC (can take a few minutes): `nslookup api.alpharesearchlabs.org`
must show the server's IP. Caddy cannot get a certificate until this works.

## 2. Prepare the server
Connect with SSH (`ssh ubuntu@SERVER_IP`; the user name depends on the provider), then:

```bash
curl -fsSL https://get.docker.com | sudo sh          # installs Docker and the compose plugin
sudo usermod -aG docker $USER && newgrp docker
sudo ufw allow OpenSSH && sudo ufw allow 80 && sudo ufw allow 443 && sudo ufw --force enable
```

On Oracle Cloud also open ports 80 and 443 in the VM's **Security List / Network Security Group**
(Ingress rules, source 0.0.0.0/0). Do not open 3306 or 8080: the database and API must stay private.

## 3. Get the code and configure it
```bash
git clone https://github.com/jacques-100/Jacques-Rwacof-internship-interview-bn---backend.git backend
cd backend/deploy
cp .env.prod.example .env
nano .env
```
Fill in `.env`. Generate each secret on the server with `openssl rand -base64 36`:
`DB_PASSWORD`, `DB_ROOT_PASSWORD`, `JWT_SECRET`, and choose a strong `BOOTSTRAP_ADMIN_PASSWORD`.
Check `API_DOMAIN` and `WEB_ORIGIN` match your real addresses (`WEB_ORIGIN` has `https://` and no trailing slash).
Keep a copy of `.env` somewhere safe: it is not in git, and losing `DB_ROOT_PASSWORD` means losing database access.

## 4. Start it
```bash
docker compose -f docker-compose.prod.yml up -d --build
docker compose -f docker-compose.prod.yml logs -f backend     # Ctrl+C to leave; wait for "Started CherrytrackApplication"
```
The first start builds the Java image (a few minutes), creates the database schema, and creates the
`admin` account from `BOOTSTRAP_ADMIN_PASSWORD`. Check, from any computer:

- `https://api.alpharesearchlabs.org/api/v1/branding` shows a small JSON reply (with a valid padlock).
- `https://api.alpharesearchlabs.org/actuator/health` is blocked (401): correct, only admins can see it.

## 5. First use
Open the web app, sign in as `admin`, then: change the password (My profile), register your station
(Stations), create users and assign them to it, set grades and prices (Prices and Grades), and upload the
company logo (Settings). This is a clean database: there is no demo data in production.

## Everyday operations
```bash
cd ~/backend/deploy
docker compose -f docker-compose.prod.yml ps                      # is everything up?
docker compose -f docker-compose.prod.yml logs --tail 100 backend # recent logs
git pull && docker compose -f docker-compose.prod.yml up -d --build   # deploy a new version
./backup.sh                                                       # database backup into deploy/backups
```
Schedule `./backup.sh` nightly with `crontab -e` (example in the script) and copy the backups off the
server now and then. To restore: `gunzip -c backups/FILE.sql.gz | docker compose -f docker-compose.prod.yml exec -T mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" cherrytrack'`.

Database changes are applied automatically at start-up by Flyway; the data lives in the `mysql-data`
Docker volume and survives rebuilds. Do not run `docker compose down -v`: the `-v` deletes the database.

## Using Render, Railway or Fly.io instead of a server
Create a MySQL database there, then a web service from this repository's `Dockerfile`. Set the same
variables as `deploy/.env` using the names in `docker-compose.prod.yml` (`DB_URL`, `DB_USERNAME`,
`DB_PASSWORD`, `JWT_SECRET`, `CORS_ALLOWED_ORIGINS`, `REFRESH_COOKIE_SECURE=true`), and add the custom
domain `api.alpharesearchlabs.org` in their dashboard (they give you a DNS record to add as in step 1).
Free tiers may sleep when idle, so the first request after a pause is slow.

## Security notes
- Secrets live only in `deploy/.env` on the server (ignored by git). The API refuses to start without a `JWT_SECRET`.
- The sign-in cookie is `Secure`, `HttpOnly` and `SameSite=Strict`; sign-in is rate limited per client address.
- Swagger and demo data are off. Only ports 22, 80 and 443 are open.
- Keep the server updated: `sudo apt update && sudo apt upgrade` now and then.
