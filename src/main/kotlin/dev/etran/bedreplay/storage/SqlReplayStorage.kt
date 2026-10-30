package dev.etran.bedreplay.storage

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.etran.bedreplay.config.MySqlSettings
import dev.etran.bedreplay.format.ReplayMeta
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.sql.Connection

/**
 * MySQL / MariaDB storage. Useful when several servers share one replay library.
 * The replay body is a LONGBLOB, so `max_allowed_packet` on the database must exceed the largest
 * replay (a few MB for a normal match).
 */
class SqlReplayStorage(private val settings: MySqlSettings) : ReplayStorage {
    private lateinit var ds: HikariDataSource
    private val table = "${settings.tablePrefix}replays"

    override fun init() {
        val cfg = HikariConfig()
        cfg.driverClassName = "org.mariadb.jdbc.Driver"
        cfg.jdbcUrl = "jdbc:mariadb://${settings.host}:${settings.port}/${settings.database}" +
            "?useSsl=${settings.useSsl}&characterEncoding=utf8"
        cfg.username = settings.user
        cfg.password = settings.password
        cfg.maximumPoolSize = 4
        cfg.poolName = "BedReplay-SQL"
        cfg.connectionTimeout = 10_000
        ds = HikariDataSource(cfg)
        use { c ->
            c.createStatement().use {
                it.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS `$table` (
                      id VARCHAR(64) NOT NULL PRIMARY KEY,
                      arena VARCHAR(128) NOT NULL,
                      map_name VARCHAR(128) NOT NULL,
                      grp VARCHAR(64) NULL,
                      mc_version VARCHAR(32) NOT NULL,
                      started_at BIGINT NOT NULL,
                      duration_ticks INT NOT NULL,
                      winner VARCHAR(64) NULL,
                      players MEDIUMTEXT NOT NULL,
                      size_bytes BIGINT NOT NULL,
                      expires_at BIGINT NOT NULL DEFAULT 0,
                      data LONGBLOB NOT NULL,
                      INDEX idx_expires (expires_at),
                      INDEX idx_started (started_at)
                    ) DEFAULT CHARSET = utf8mb4
                    """.trimIndent(),
                )
            }
        }
    }

    private inline fun <T> use(block: (Connection) -> T): T = ds.connection.use(block)

    override fun save(spool: File, meta: ReplayMeta, expiresAtMillis: Long): StoredReplay {
        FileReplayStorage.requireSafeId(meta.id)
        val size = spool.length()
        use { c ->
            c.prepareStatement(
                """
                INSERT INTO `$table`
                  (id, arena, map_name, grp, mc_version, started_at, duration_ticks, winner, players, size_bytes, expires_at, data)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                ON DUPLICATE KEY UPDATE duration_ticks = VALUES(duration_ticks), winner = VALUES(winner),
                  players = VALUES(players), size_bytes = VALUES(size_bytes), expires_at = VALUES(expires_at),
                  data = VALUES(data)
                """.trimIndent(),
            ).use { st ->
                FileInputStream(spool).use { body ->
                    st.setString(1, meta.id)
                    st.setString(2, meta.arena)
                    st.setString(3, meta.mapName)
                    st.setString(4, meta.group)
                    st.setString(5, meta.mcVersion)
                    st.setLong(6, meta.startedAtMillis)
                    st.setInt(7, meta.durationTicks)
                    st.setString(8, meta.winnerTeam)
                    st.setString(9, PlayerIndex.encode(meta.players))
                    st.setLong(10, size)
                    st.setLong(11, expiresAtMillis)
                    st.setBinaryStream(12, body, size)
                    st.executeUpdate()
                }
            }
        }
        spool.delete()
        return StoredReplay.from(meta, size, expiresAtMillis)
    }

    override fun list(): List<StoredReplay> = use { c ->
        c.createStatement().use { st ->
            st.executeQuery(
                "SELECT id, arena, map_name, grp, mc_version, started_at, duration_ticks, winner, players, size_bytes, expires_at FROM `$table`",
            ).use { rs ->
                val out = ArrayList<StoredReplay>()
                while (rs.next()) {
                    out += StoredReplay(
                        id = rs.getString("id"),
                        arena = rs.getString("arena"),
                        mapName = rs.getString("map_name"),
                        group = rs.getString("grp"),
                        mcVersion = rs.getString("mc_version"),
                        startedAtMillis = rs.getLong("started_at"),
                        durationTicks = rs.getInt("duration_ticks"),
                        winnerTeam = rs.getString("winner"),
                        players = PlayerIndex.decode(rs.getString("players")),
                        sizeBytes = rs.getLong("size_bytes"),
                        expiresAtMillis = rs.getLong("expires_at"),
                    )
                }
                out
            }
        }
    }

    override fun open(id: String): InputStream? = use { c ->
        c.prepareStatement("SELECT data FROM `$table` WHERE id = ?").use { st ->
            st.setString(1, id)
            st.executeQuery().use { rs -> if (rs.next()) ByteArrayInputStream(rs.getBytes(1)) else null }
        }
    }

    override fun delete(id: String): Boolean = use { c ->
        c.prepareStatement("DELETE FROM `$table` WHERE id = ?").use { st ->
            st.setString(1, id)
            st.executeUpdate() > 0
        }
    }

    override fun setExpiry(id: String, expiresAtMillis: Long): Boolean = use { c ->
        c.prepareStatement("UPDATE `$table` SET expires_at = ? WHERE id = ?").use { st ->
            st.setLong(1, expiresAtMillis)
            st.setString(2, id)
            st.executeUpdate() > 0
        }
    }

    override fun close() {
        if (::ds.isInitialized) ds.close()
    }
}
