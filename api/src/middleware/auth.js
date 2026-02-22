const crypto = require('crypto');
const config = require('../config');

function extractToken(req) {
    const authHeader = req.headers.authorization;
    if (authHeader && authHeader.startsWith('Bearer ')) {
        return authHeader.substring(7);
    }
    return req.headers['x-api-token'] || req.query.token || null;
}

function safeEqual(a, b) {
    if (typeof a !== 'string' || typeof b !== 'string') return false;
    const bufA = Buffer.from(a);
    const bufB = Buffer.from(b);
    if (bufA.length !== bufB.length) {
        crypto.timingSafeEqual(bufA, bufA);
        return false;
    }
    return crypto.timingSafeEqual(bufA, bufB);
}

function masterAuth(req, res, next) {
    const token = extractToken(req);
    if (!token) {
        return res.status(401).json({ error: 'Missing authentication token' });
    }
    if (!safeEqual(token, config.MASTER_TOKEN)) {
        return res.status(403).json({ error: 'Invalid master token' });
    }
    req.authType = 'master';
    next();
}

function serverAuth(req, res, next) {
    const token = extractToken(req);
    const serverName = req.params.serverName || req.headers['x-server-name'];

    if (!token) {
        return res.status(401).json({ error: 'Missing authentication token' });
    }
    if (!serverName) {
        return res.status(400).json({ error: 'Missing server name' });
    }
    if (!config.isValidServer(serverName)) {
        return res.status(404).json({ error: `Unknown server: ${serverName}` });
    }

    const expectedToken = config.getServerToken(serverName);
    if (!safeEqual(token, expectedToken)) {
        return res.status(403).json({ error: 'Invalid network token' });
    }

    req.authType = 'server';
    req.serverName = serverName;
    req.network = config.getNetworkForServer(serverName);
    next();
}

function combinedAuth(req, res, next) {
    const token = extractToken(req);
    if (!token) {
        return res.status(401).json({ error: 'Missing authentication token' });
    }

    if (safeEqual(token, config.MASTER_TOKEN)) {
        req.authType = 'master';
        return next();
    }

    const serverName = req.params.serverName || req.headers['x-server-name'];
    if (serverName && config.isValidServer(serverName)) {
        const expectedToken = config.getServerToken(serverName);
        if (safeEqual(token, expectedToken)) {
            req.authType = 'server';
            req.serverName = serverName;
            req.network = config.getNetworkForServer(serverName);
            return next();
        }
    }

    return res.status(403).json({ error: 'Invalid token' });
}

module.exports = { masterAuth, serverAuth, combinedAuth };
