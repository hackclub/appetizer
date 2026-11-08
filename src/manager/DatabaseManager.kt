package com.fantamomo.slack.approver.manager

import com.fantamomo.slack.approver.data.Config
import com.fantamomo.slack.approver.db.*
import com.fantamomo.slack.approver.utils.invokeSuspendMethode
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.r2dbc.*
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.slf4j.LoggerFactory
import java.io.File
import java.lang.reflect.InaccessibleObjectException
import java.net.JarURLConnection
import java.net.URLDecoder
import java.util.jar.JarFile
import kotlin.coroutines.Continuation
import kotlin.time.Clock
import kotlin.time.measureTime

object DatabaseManager {

    private const val MIGRATION_FOLDER_PATH = "db/migrations"
    private const val CREATE_TABLE_DB_MIGRATIONS_FILE_NAME =
        "V0__CREATE_TABLE_MIGRATION.sql"

    private val logger = LoggerFactory.getLogger(DatabaseManager::class.java)

    private var db: R2dbcDatabase? = null

    // we only allow 16 concurrent database operations
    // this is to prevent too many database operations from being executed at the same time
    // which can lead to the database MAX_CONNECTIONS (already been there, never doing that again)
    private val semaphore = Semaphore(16)

    suspend fun init() {
        connect()
        initTables()
    }

    private fun connect() {
        if (db != null) return

        try {
            Class.forName("org.postgresql.Driver")
        } catch (e: ClassNotFoundException) {
            logger.error("PostgreSQL driver not found, check dependencies", e)
            throw e
        }

        db = R2dbcDatabase.connect(
            url = Config.POSTGRES_URL,
            driver = "postgresql",
            user = Config.POSTGRES_USER,
            password = Config.POSTGRES_PASSWORD,
        )
    }

    suspend fun <T> transaction(
        block: suspend R2dbcTransaction.() -> T
    ): T {
        val database = db ?: error("Database not connected")
        semaphore.withPermit {
            return suspendTransaction(db = database, statement = block)
        }
    }

    private suspend fun initTables() {
        logger.info("Initializing database migrations")

        transaction {
            SchemaUtils.create(MigrationTable)
        }

        val migrationFiles = loadAllMigrationFileNames()

        if (migrationFiles.isEmpty()) {
            logger.warn("No migration files found")
            return
        }

        val appliedMigrations = transaction {
            MigrationTable
                .select(MigrationTable.migration)
                .map { it[MigrationTable.migration] }
                .toList()
        }

        val firstRun = appliedMigrations.isEmpty()
        if (firstRun) {
            logger.info("First run, just initializing database, ignoring migrations")
            transaction {
                SchemaUtils.create(
                    AppCommandsTable,
                    ApproveTeamMemberTable,
                    CachedVerificationStatusTable,
                    InstallRequestPreviousResolutionTable,
                    InstallRequestScopeTable,
                    InstallRequestTable,
                    AppRestrictionTable,
                    InstallRequestHistoryTable,
                    RestrictedScopesTable,
                    ScopeRulesTable,
                )
                MigrationTable.batchInsert(migrationFiles) {
                    this[MigrationTable.migration] = it
                    this[MigrationTable.appliedAt] = Clock.System.now()
                }
            }
            return
        }

        if (CREATE_TABLE_DB_MIGRATIONS_FILE_NAME !in appliedMigrations) {
            transaction {
                MigrationTable.insert {
                    it[migration] = CREATE_TABLE_DB_MIGRATIONS_FILE_NAME
                    it[appliedAt] = Clock.System.now()
                }
            }
        }

        val migrationsToApply = migrationFiles
            .filter {
                it != CREATE_TABLE_DB_MIGRATIONS_FILE_NAME &&
                        it !in appliedMigrations
            }
            .sorted()

        if (migrationsToApply.isEmpty()) {
            logger.info("Database is up to date")
            return
        }

        logger.info("Found ${migrationsToApply.size} migrations to apply")

        val migrations = loadMigrations(migrationsToApply)

        val missing = migrationsToApply - migrations.keys

        if (missing.isNotEmpty()) {
            error(
                "Missing migration files: ${
                    missing.joinToString(", ")
                }"
            )
        }

        val softwareMigrationsClass = SoftwareMigrations::class.java

        for ((fileName, sql) in migrations.entries.sortedBy { it.key }) {
            logger.info("Applying migration {}", fileName)

            try {
                val duration = measureTime {
                    transaction {
                        exec(sql)
                    }
                }
                logger.info("Migration {} applied in {} ms", fileName, duration.inWholeMilliseconds)

                val methodName = fileName.removeSuffix(".sql")
                try {
                    val method = softwareMigrationsClass.getDeclaredMethod(methodName, Continuation::class.java)
                    method.isAccessible = true

                    logger.info("Applying software migration $methodName")
                    val duration = measureTime {
                        transaction {
                            invokeSuspendMethode(
                                method = method,
                                receiver = SoftwareMigrations
                            )
                        }
                    }
                    logger.info("Executed software migration $methodName in $duration")
                } catch (_: NoSuchMethodException) {
                } catch (e: InaccessibleObjectException) {
                    logger.warn("Could not enabled access on the '$methodName' methode")
                    throw e
                } catch (e: Exception) {
                    logger.warn("Failed to call software migration called '$methodName' methode", e)
                    throw e
                }
                transaction {
                    MigrationTable.insert {
                        it[migration] = fileName
                        it[appliedAt] = Clock.System.now()
                    }
                }
            } catch (e: Exception) {
                logger.error("Failed to apply migration {}", fileName, e)
                throw e
            }
        }

        logger.info("All migrations applied")
    }

    private fun loadMigrations(
        files: List<String>
    ): Map<String, String> {
        val classLoader = javaClass.classLoader

        return buildMap {
            for (file in files) {
                try {
                    val stream = classLoader.getResourceAsStream("$MIGRATION_FOLDER_PATH/$file")
                    if (stream == null) {
                        logger.error("Migration {} not found", file)
                        continue
                    }
                    stream.bufferedReader().use {
                        put(file, it.readText())
                    }
                } catch (e: Exception) {
                    logger.error("Failed to load migration {}", file, e)
                }
            }
        }
    }

    internal fun loadAllMigrationFileNames(): List<String> {
        val result = mutableListOf<String>()
        val resources = javaClass.classLoader.getResources(MIGRATION_FOLDER_PATH)

        while (resources.hasMoreElements()) {
            val url = resources.nextElement()
            when (url.protocol) {
                "jar" -> {
                    try {
                        val connection = url.openConnection()
                        if (connection is JarURLConnection) {
                            connection.jarFile.use { jar ->
                                jar.entries()
                                    .asSequence()
                                    .forEach { entry ->
                                        if (!entry.isDirectory && entry.name.endsWith(".sql") && entry.name.contains("$MIGRATION_FOLDER_PATH/")) {
                                            result.add(entry.name.substringAfter("$MIGRATION_FOLDER_PATH/"))
                                        }
                                    }
                            }
                        } else {
                            val path = url.path
                                .removePrefix("file:")
                                .removePrefix("nested:")
                                .substringBefore("!")

                            JarFile(
                                URLDecoder.decode(path, "UTF-8")
                            ).use { jar ->
                                jar.entries()
                                    .asSequence()
                                    .forEach { entry ->
                                        if (!entry.isDirectory && entry.name.endsWith(".sql") && entry.name.contains("$MIGRATION_FOLDER_PATH/")) {
                                            result.add(entry.name.substringAfter("$MIGRATION_FOLDER_PATH/"))
                                        }
                                    }
                            }
                        }
                    } catch (e: Exception) {
                        logger.warn("Failed to load migration files from jar url: $url", e)
                    }
                }

                "file" -> {
                    val file = try {
                        File(url.toURI())
                    } catch (_: Exception) {
                        File(url.path.removePrefix("file:"))
                    }
                    if (!file.isDirectory) continue

                    file.listFiles()
                        ?.filter {
                            it.isFile &&
                                    it.name.endsWith(".sql")
                        }
                        ?.forEach {
                            result += it.name
                        }
                }

                else -> {
                    logger.warn("Unsupported protocol {}", url.protocol)
                }
            }
        }

        return result.distinct().sorted()
    }

    @Suppress("FunctionName", "unused")
    private object SoftwareMigrations {

        suspend fun V20261108172512__ALTER_TABLE_INSTALL_REQUEST() {
            InstallRequestTable.update(
                where = { InstallRequestTable.reviewMessageChannel.isNull() }
            ) {
                it[InstallRequestTable.reviewMessageChannel] = Config.SLACK_CHANNEL_REVIEW
            }
        }
    }
}