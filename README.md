# CherryTrack Backend

Spring Boot 3.4 (Java 21) REST API on MySQL 8 with Flyway migrations. It owns every business rule for the coffee washing station: delivery workflow, weight limits, daily capacity, pricing, payment, audit, users and permissions. The web UI (`web-frontend/`) and the Android app (`mobile-app/`) are separate projects that only call this API.

## Business rules (enforced here, never on a client)

- A delivery moves `RECEIVED → GRADED → PAID` or `RECEIVED → REJECTED`. Nothing is ever deleted; every change is audited.
- Weight must be within the station's per-delivery maximum. The **daily capacity is never exceeded**, even under concurrent requests. Rejected deliveries do not count toward it.
- A delivery date cannot be in the future. Weight can be corrected only while `RECEIVED`.
- Grades and prices are data. Prices are append-only per grade; a graded delivery keeps the price it was graded at.
- Amounts are computed only by the backend (`weight × price per kg`). A client never sends a price or an amount.

## Architecture

- **Stations.** Every delivery, capacity row and report belongs to a station. Clients send `X-Station-Id`; the backend checks the caller is assigned to it (administrators may use any). References look like `DLV-<STATION>-yyyyMMdd-00001`.
- **Authorization.** Each job role carries a set of permissions, edited at runtime; authorities are rebuilt from the database on every request, so changes apply immediately. The Administrator role is fixed; nobody can grant a permission they do not hold.
- **Authentication.** 30-minute JWT access token. Browsers get a rotating 7-day refresh token in an httpOnly `SameSite=Strict` cookie. Native apps send `X-Client: native` and receive the refresh token in the response body instead, and send it back in `X-Refresh-Token`. Tokens are stored hashed; replaying an already-rotated token revokes all of that user's sessions. Login is rate limited.
- **Concurrency.** One `daily_capacity` row per `(station, day)`: `INSERT … ON DUPLICATE KEY UPDATE`, then `SELECT … FOR UPDATE`, check the limit, write the delivery, all in one READ COMMITTED transaction. Lock order is always capacity row, then delivery. A multi-threaded integration test against real MySQL covers it.
- **Errors.** RFC 7807 `problem+json` with a stable `code`, `fieldErrors` and a `correlationId` that also appears in the logs.
- **Logo and photos.** The company logo and each user's profile photo are stored in the database (`stored_images`, LONGBLOB). Uploads (`PUT /branding/logo`, `PUT /auth/me/avatar`, multipart `file`) are identified by their actual bytes, not the filename or claimed type: only PNG, JPEG and WebP are accepted (SVG is refused because it can carry scripts), logos up to 2 MB and photos up to 1 MB. Changing the logo needs the settings permission and is audited; a user can only change their own photo. The logo and branding are public so the sign-in page can show them; photos need a signed-in user.
- **Settings.** Organisation name, currency, new-station defaults, report range and rejection reasons live in `system_settings`.

## Run locally

Requirements: JDK 21, MySQL 8.

```bash
export DB_USERNAME=... DB_PASSWORD=...
export JWT_SECRET="$(openssl rand -base64 48)"        # 32+ characters, required
export BOOTSTRAP_ADMIN_PASSWORD='choose-a-strong-password'
./mvnw spring-boot:run                                 # http://localhost:8080
```

On an empty database the first start creates the schema and an `admin` account. Sign in, register a station, assign people to it and set up grades and prices. For sample data (two stations, users, farmers, two weeks of history) also set `SEED_DEMO_DATA=true` and `SEED_DEMO_PASSWORD=...` before the first start. Never use demo data in production.

Swagger UI: `/swagger-ui.html` (`SWAGGER_ENABLED=false` disables it).

CORS: `CORS_ALLOWED_ORIGINS` (comma separated). The default allows the Vite dev server and the Android app's origins (`http://localhost`, `https://localhost`, `capacitor://localhost`). Set it explicitly in production.

## Docker

```bash
cp .env.example .env      # fill in the secrets
docker compose up --build  # MySQL + API on http://localhost:8080
```

Set `REFRESH_COOKIE_SECURE=true` once served over HTTPS. Logs are structured JSON (ECS).

## Tests

```bash
TEST_DB_URL='jdbc:mysql://localhost:3306/cherrytrack_test?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC' \
TEST_DB_USER=root TEST_DB_PASSWORD=... ./mvnw verify
```

Integration tests run against a real MySQL (`TEST_DB_URL`), or Testcontainers when it is unset. CI is in `.github/workflows/ci.yml`.
