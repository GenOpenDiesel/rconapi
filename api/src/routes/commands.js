const express = require('express');
const { v4: uuidv4 } = require('uuid');
const { LRUCache } = require('lru-cache');
const { asyncStmts, insertBulkAsync, insertBroadcastBulkAsync } = require('../database');
const { masterAuth, serverAuth, combinedAuth } = require('../middleware/auth');
const config = require('../config');
const logger = require('../logger');

const router = express.Router();

const pendingCache = new LRUCache({
    max: 200,
    ttl: config.PENDING_CACHE_TTL_MS,
});

function computeExpiresAt(hoursFromNow) {
    const hours = hoursFromNow || config.COMMAND_EXPIRY_HOURS;
    const expires = new Date(Date.now() + hours * 3600 * 1000);
    return expires.toISOString().replace('T', ' ').substring(0, 19);
}

router.post('/', masterAuth, async (req, res) => {
    try {
        const { serverId, gameMode, command, player, executionType, expiryHours } = req.body;

        if (!serverId || !command || !executionType) {
            return res.status(400).json({
                error: 'Missing required fields: serverId, command, executionType',
            });
        }

        const validTypes = ['INSTANT', 'REQUIRE_ONLINE', 'BROADCAST_ONLINE'];
        if (!validTypes.includes(executionType)) {
            return res.status(400).json({
                error: `Invalid executionType. Must be one of: ${validTypes.join(', ')}`,
            });
        }

        if (!config.isValidServer(serverId)) {
            return res.status(404).json({ error: `Unknown server: ${serverId}` });
        }

        if ((executionType === 'REQUIRE_ONLINE' || executionType === 'BROADCAST_ONLINE') && !player) {
            return res.status(400).json({
                error: `Player is required for ${executionType} execution type`,
            });
        }

        const expiresAt = computeExpiresAt(expiryHours);

        if (executionType === 'BROADCAST_ONLINE') {
            const networkName = config.getNetworkForServer(serverId);
            const allServers = config.getServersByNetwork(networkName);
            const groupId = uuidv4();

            const cmds = allServers.map(srv => ({
                id: uuidv4(),
                serverId: srv,
                gameMode: gameMode || null,
                command,
                player,
                executionType,
                expiresAt,
                groupId,
            }));

            const created = await insertBroadcastBulkAsync(cmds);
            pendingCache.clear();
            res.status(201).json({ success: true, groupId, commands: created });
        } else {
            const id = uuidv4();
            await asyncStmts.insertCommand(id, serverId, gameMode || null, command, player || null, executionType, expiresAt);
            const created = await asyncStmts.getById(id);
            pendingCache.delete(serverId);
            res.status(201).json({ success: true, command: created });
        }
    } catch (err) {
        logger.error({ err }, 'Error creating command');
        res.status(500).json({ error: 'Internal server error' });
    }
});

router.post('/bulk', masterAuth, async (req, res) => {
    try {
        const { commands } = req.body;

        if (!Array.isArray(commands) || commands.length === 0) {
            return res.status(400).json({ error: 'commands must be a non-empty array' });
        }

        if (commands.length > 500) {
            return res.status(400).json({ error: 'Maximum 500 commands per bulk request' });
        }

        const validTypes = ['INSTANT', 'REQUIRE_ONLINE', 'BROADCAST_ONLINE'];
        const prepared = [];
        const errors = [];

        for (let i = 0; i < commands.length; i++) {
            const cmd = commands[i];
            if (!cmd.serverId || !cmd.command || !cmd.executionType) {
                errors.push({ index: i, error: 'Missing required fields' });
                continue;
            }
            if (!validTypes.includes(cmd.executionType)) {
                errors.push({ index: i, error: 'Invalid executionType' });
                continue;
            }
            if (!config.isValidServer(cmd.serverId)) {
                errors.push({ index: i, error: `Unknown server: ${cmd.serverId}` });
                continue;
            }

            prepared.push({
                id: uuidv4(),
                serverId: cmd.serverId,
                gameMode: cmd.gameMode || null,
                command: cmd.command,
                player: cmd.player || null,
                executionType: cmd.executionType,
                expiresAt: computeExpiresAt(cmd.expiryHours),
            });
        }

        if (prepared.length > 0) {
            await insertBulkAsync(prepared);
            pendingCache.clear();
        }

        res.status(201).json({
            success: true,
            created: prepared.length,
            errors: errors.length > 0 ? errors : undefined,
        });
    } catch (err) {
        logger.error({ err }, 'Error bulk creating commands');
        res.status(500).json({ error: 'Internal server error' });
    }
});

router.get('/pending/:serverName', serverAuth, async (req, res) => {
    try {
        const cached = pendingCache.get(req.serverName);
        if (cached) {
            return res.json({ success: true, commands: cached, cached: true });
        }

        const commands = await asyncStmts.getPendingByServer(req.serverName);
        pendingCache.set(req.serverName, commands);
        res.json({ success: true, commands });
    } catch (err) {
        logger.error({ err }, 'Error fetching pending commands');
        res.status(500).json({ error: 'Internal server error' });
    }
});

router.post('/:id/complete', combinedAuth, async (req, res) => {
    try {
        const cmd = await asyncStmts.getById(req.params.id);
        if (!cmd) {
            return res.status(404).json({ error: 'Command not found' });
        }

        if (req.authType === 'server' && cmd.server_id !== req.serverName) {
            return res.status(403).json({ error: 'Not authorized for this command' });
        }

        const response = req.body.response || null;
        await asyncStmts.markExecuted(response, req.params.id);

        if (cmd.group_id) {
            await asyncStmts.cancelGroupExcept(cmd.group_id, req.params.id);
        }

        pendingCache.delete(cmd.server_id);

        res.json({
            success: true,
            message: 'Command marked as executed',
        });
    } catch (err) {
        logger.error({ err }, 'Error completing command');
        res.status(500).json({ error: 'Internal server error' });
    }
});

router.post('/:id/fail', combinedAuth, async (req, res) => {
    try {
        const cmd = await asyncStmts.getById(req.params.id);
        if (!cmd) {
            return res.status(404).json({ error: 'Command not found' });
        }

        if (req.authType === 'server' && cmd.server_id !== req.serverName) {
            return res.status(403).json({ error: 'Not authorized for this command' });
        }

        const response = req.body.error || req.body.response || 'Unknown error';
        await asyncStmts.markFailed(response, req.params.id);

        pendingCache.delete(cmd.server_id);

        res.json({ success: true, message: 'Command marked as failed' });
    } catch (err) {
        logger.error({ err }, 'Error failing command');
        res.status(500).json({ error: 'Internal server error' });
    }
});

router.post('/:id/skip', combinedAuth, async (req, res) => {
    try {
        const cmd = await asyncStmts.getById(req.params.id);
        if (!cmd) {
            return res.status(404).json({ error: 'Command not found' });
        }

        if (req.authType === 'server' && cmd.server_id !== req.serverName) {
            return res.status(403).json({ error: 'Not authorized for this command' });
        }

        const response = req.body.response || 'Player not online on this server';
        await asyncStmts.markSkipped(response, req.params.id);

        pendingCache.delete(cmd.server_id);

        res.json({ success: true, message: 'Command skipped' });
    } catch (err) {
        logger.error({ err }, 'Error skipping command');
        res.status(500).json({ error: 'Internal server error' });
    }
});

router.get('/:id', masterAuth, async (req, res) => {
    try {
        const cmd = await asyncStmts.getById(req.params.id);
        if (!cmd) {
            return res.status(404).json({ error: 'Command not found' });
        }
        res.json({ success: true, command: cmd });
    } catch (err) {
        logger.error({ err }, 'Error fetching command');
        res.status(500).json({ error: 'Internal server error' });
    }
});

router.delete('/:id', masterAuth, async (req, res) => {
    try {
        const cmd = await asyncStmts.getById(req.params.id);
        if (!cmd) {
            return res.status(404).json({ error: 'Command not found' });
        }
        await asyncStmts.markCancelled(req.params.id);
        pendingCache.delete(cmd.server_id);
        res.json({ success: true, message: 'Command cancelled' });
    } catch (err) {
        logger.error({ err }, 'Error cancelling command');
        res.status(500).json({ error: 'Internal server error' });
    }
});

router.get('/', masterAuth, async (req, res) => {
    try {
        const { serverId, gameMode, player, status, limit, offset } = req.query;

        const params = {
            serverId: serverId || null,
            gameMode: gameMode || null,
            player: player || null,
            status: status || null,
            limit: Math.min(parseInt(limit || '50', 10), 200),
            offset: parseInt(offset || '0', 10),
        };

        const [commands, countResult] = await Promise.all([
            asyncStmts.listCommands(params),
            asyncStmts.countCommands(params),
        ]);
        const total = countResult ? countResult.total : 0;

        res.json({ success: true, commands, total, limit: params.limit, offset: params.offset });
    } catch (err) {
        logger.error({ err }, 'Error listing commands');
        res.status(500).json({ error: 'Internal server error' });
    }
});

module.exports = router;
