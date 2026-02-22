const mysql = require('mysql2/promise');
const config = require('./config');

let pool;

const SQL = {
    insert: `INSERT INTO commands (id, server_id, game_mode, command, player, execution_type, status, expires_at, group_id)
             VALUES (?, ?, ?, ?, ?, ?, 'PENDING', ?, ?)`,
    getPending: `SELECT * FROM commands WHERE server_id = ? AND status IN ('PENDING', 'QUEUED')
                 ORDER BY created_at ASC`,
    getById: 'SELECT * FROM commands WHERE id = ?',
    markExecuted: `UPDATE commands SET status = 'EXECUTED', executed_at = NOW(), response = ? WHERE id = ?`,
    markFailed: `UPDATE commands SET status = 'FAILED', executed_at = NOW(), response = ? WHERE id = ?`,
    markCancelled: `UPDATE commands SET status = 'CANCELLED' WHERE id = ?`,
    markSkipped: `UPDATE commands SET status = 'SKIPPED', executed_at = NOW(), response = ? WHERE id = ?`,
    cancelGroup: `UPDATE commands SET status = 'CANCELLED', response = 'Auto-cancelled: executed on another server'
                  WHERE group_id = ? AND id != ? AND status IN ('PENDING', 'QUEUED')`,
    getGroup: `SELECT id, server_id, status, response FROM commands WHERE group_id = ? ORDER BY created_at ASC`,
    getByGroupId: `SELECT * FROM commands WHERE group_id = ? ORDER BY created_at ASC`,
    expireOld: `UPDATE commands SET status = 'EXPIRED' WHERE status IN ('PENDING', 'QUEUED') AND expires_at < NOW()`,
    purgeOld: `DELETE FROM commands WHERE status IN ('EXECUTED', 'FAILED', 'CANCELLED', 'EXPIRED', 'SKIPPED') AND created_at < ?`,
};

async function initDatabase() {
    pool = mysql.createPool({
        host: config.DB_HOST,
        port: config.DB_PORT,
        user: config.DB_USER,
        password: config.DB_PASSWORD,
        database: config.DB_NAME,
        waitForConnections: true,
        connectionLimit: 20,
        queueLimit: 0,
        charset: 'utf8mb4',
        timezone: '+00:00',
    });

    await pool.query(`
        CREATE TABLE IF NOT EXISTS commands (
            id VARCHAR(36) PRIMARY KEY,
            server_id VARCHAR(255) NOT NULL,
            game_mode VARCHAR(255),
            command TEXT NOT NULL,
            player VARCHAR(255),
            execution_type VARCHAR(50) NOT NULL,
            status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
            response TEXT,
            created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            executed_at DATETIME,
            expires_at DATETIME NOT NULL,
            group_id VARCHAR(36),
            INDEX idx_commands_server_status (server_id, status),
            INDEX idx_commands_player_status (player, status),
            INDEX idx_commands_expires (expires_at),
            INDEX idx_commands_created (created_at),
            INDEX idx_commands_group (group_id)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
    `);

    return pool;
}

const asyncStmts = {
    async insertCommand(id, serverId, gameMode, command, player, executionType, expiresAt, groupId) {
        await pool.execute(SQL.insert, [id, serverId, gameMode, command, player, executionType, expiresAt, groupId || null]);
    },

    async getPendingByServer(serverId) {
        const [rows] = await pool.execute(SQL.getPending, [serverId]);
        return rows;
    },

    async getById(id) {
        const [rows] = await pool.execute(SQL.getById, [id]);
        return rows[0] || null;
    },

    async markExecuted(response, id) {
        const [result] = await pool.execute(SQL.markExecuted, [response, id]);
        return { changes: result.affectedRows };
    },

    async markFailed(response, id) {
        const [result] = await pool.execute(SQL.markFailed, [response, id]);
        return { changes: result.affectedRows };
    },

    async markCancelled(id) {
        const [result] = await pool.execute(SQL.markCancelled, [id]);
        return { changes: result.affectedRows };
    },

    async markSkipped(response, id) {
        const [result] = await pool.execute(SQL.markSkipped, [response, id]);
        return { changes: result.affectedRows };
    },

    async cancelGroupExcept(groupId, exceptId) {
        if (!groupId) return { changes: 0 };
        const [result] = await pool.execute(SQL.cancelGroup, [groupId, exceptId]);
        return { changes: result.affectedRows };
    },

    async getGroupInfo(groupId) {
        if (!groupId) return null;
        const [rows] = await pool.execute(SQL.getGroup, [groupId]);
        return rows;
    },

    async expireOld() {
        const [result] = await pool.execute(SQL.expireOld);
        return { changes: result.affectedRows };
    },

    async purgeOldCompleted(cutoff) {
        const [result] = await pool.execute(SQL.purgeOld, [cutoff]);
        return { changes: result.affectedRows };
    },

    async listCommands(params) {
        let sql = 'SELECT * FROM commands WHERE 1=1';
        const bindings = [];

        if (params.serverId) { sql += ' AND server_id = ?'; bindings.push(params.serverId); }
        if (params.gameMode) { sql += ' AND game_mode = ?'; bindings.push(params.gameMode); }
        if (params.player) { sql += ' AND player = ?'; bindings.push(params.player); }
        if (params.status) { sql += ' AND status = ?'; bindings.push(params.status); }

        sql += ' ORDER BY created_at DESC LIMIT ? OFFSET ?';
        bindings.push(params.limit, params.offset);

        const [rows] = await pool.execute(sql, bindings);
        return rows;
    },

    async countCommands(params) {
        let sql = 'SELECT COUNT(*) as total FROM commands WHERE 1=1';
        const bindings = [];

        if (params.serverId) { sql += ' AND server_id = ?'; bindings.push(params.serverId); }
        if (params.gameMode) { sql += ' AND game_mode = ?'; bindings.push(params.gameMode); }
        if (params.player) { sql += ' AND player = ?'; bindings.push(params.player); }
        if (params.status) { sql += ' AND status = ?'; bindings.push(params.status); }

        const [rows] = await pool.execute(sql, bindings);
        return rows[0] || { total: 0 };
    },
};

async function insertBulkAsync(commands) {
    const conn = await pool.getConnection();
    try {
        await conn.beginTransaction();
        for (const cmd of commands) {
            await conn.execute(SQL.insert, [
                cmd.id, cmd.serverId, cmd.gameMode, cmd.command,
                cmd.player, cmd.executionType, cmd.expiresAt, cmd.groupId || null,
            ]);
        }
        await conn.commit();
    } catch (err) {
        await conn.rollback();
        throw err;
    } finally {
        conn.release();
    }
}

async function insertBroadcastBulkAsync(commands) {
    const conn = await pool.getConnection();
    try {
        await conn.beginTransaction();
        for (const cmd of commands) {
            await conn.execute(SQL.insert, [
                cmd.id, cmd.serverId, cmd.gameMode, cmd.command,
                cmd.player, cmd.executionType, cmd.expiresAt, cmd.groupId,
            ]);
        }
        await conn.commit();
    } catch (err) {
        await conn.rollback();
        throw err;
    } finally {
        conn.release();
    }

    const groupId = commands[0].groupId;
    const [created] = await pool.execute(SQL.getByGroupId, [groupId]);
    return created;
}

async function shutdown() {
    if (pool) {
        await pool.end();
    }
}

module.exports = {
    initDatabase,
    asyncStmts,
    insertBulkAsync,
    insertBroadcastBulkAsync,
    shutdown,
};
