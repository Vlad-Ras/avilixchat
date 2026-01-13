# MultiChat (NeoForge 1.21.1)

MultiChat splits chat into 4 channels with tabs (client) and server-side routing:

* Global
* Local (radius configurable)
* Trade
* Clan

## Sending messages

The selected tab automatically prefixes outgoing chat so the server can route it:

* Global: no prefix
* Local: `#l `
* Trade: `#t `
* Clan: `#c `

Players without the client mod can still type these prefixes manually.

## Timestamp + formatting

Server formats routed messages as:

`[HH:mm:ss] [X] <prefix+name>: message`

## Clan chat compatibility

If **Open Parties and Claims** is present, Clan chat targets your party members.
If not, it falls back to your vanilla scoreboard team.

## LuckPerms prefixes

If the LuckPerms API is available at runtime, MultiChat prepends the user's prefix.

Prefix parsing mode is configurable:

* `luckPermsPrefixFormat = "AUTO"` (default) — tries MiniMessage when it looks like `<red>...`, otherwise legacy `&`/`§` codes
* `"MINIMESSAGE"` — always treat prefixes as MiniMessage
* `"LEGACY"` — always treat prefixes as legacy codes
* `"PLAIN"` — no formatting

## Chat logging to database (MariaDB / MySQL)

MultiChat can log all routed player chat into a MariaDB/MySQL table.

Logged fields:

* time (epoch ms + ISO)
* channel
* username + uuid
* message
* dimension
* x/y/z (block position)

Config keys (in `config/multichat-common.toml`):

* `chatLogEnabled = true`
* `chatLogJdbcUrl = "jdbc:mariadb://127.0.0.1:3306/multichat?useUnicode=true&characterEncoding=utf8"`
* `chatLogDbUser = "root"`
* `chatLogDbPassword = ""`
* `chatLogTable = "chat_logs"`
* `chatLogAutoCreateTable = true`

If `chatLogAutoCreateTable` is enabled, the mod will `CREATE TABLE IF NOT EXISTS` on server start.

## Notes


* For full experience (tabs + filtering), install on both **client and server**.
* Server-only installation still routes/format/logs, but clients won't get tabs.bs.
