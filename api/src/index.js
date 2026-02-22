const express = require('express');
const helmet = require('helmet');
const cors = require('cors');
const pinoHttp = require('pino-http');
const rateLimit = require('express-rate-limit');
const config = require('./config');
const logger = require('./logger');
const requestTimeout = require('./middleware/timeout');
const { initDatabase, shutdown } = require('./database');

async function start() {
    await initDatabase();

    const commandRoutes = require('./routes/commands');
    const serverRoutes = require('./routes/servers');
    const { startCleanupJob } = require('./services/cleanupService');

    const app = express();

    app.use(helmet());
    app.use(cors());
    app.use(pinoHttp({ logger, autoLogging: { ignore: (req) => req.url === '/api/health' } }));
    app.use(express.json({ limit: '1mb' }));
    app.use(requestTimeout(config.REQUEST_TIMEOUT_MS));

    const limiter = rateLimit({
        windowMs: 60 * 1000,
        max: 600,
        standardHeaders: true,
        legacyHeaders: false,
        message: { error: 'Too many requests, please try again later' },
    });
    app.use('/api/', limiter);

    app.use('/api/commands', commandRoutes);
    app.use('/api/servers', serverRoutes);

    app.get('/api/health', (req, res) => {
        res.json({
            status: 'ok',
            uptime: process.uptime(),
            memoryMB: Math.round(process.memoryUsage().heapUsed / 1024 / 1024),
        });
    });

    app.use((req, res) => {
        res.status(404).json({ error: 'Endpoint not found' });
    });

    app.use((err, req, res, _next) => {
        logger.error({ err }, 'Unhandled error');
        res.status(500).json({ error: 'Internal server error' });
    });

    startCleanupJob();

    const server = app.listen(config.PORT, () => {
        logger.info(`API running on port ${config.PORT}`);
        logger.info(`Registered servers: ${config.getServerNames().join(', ')}`);
    });

    server.keepAliveTimeout = 65000;
    server.headersTimeout = 66000;

    const gracefulShutdown = (signal) => {
        logger.info(`${signal} received, shutting down...`);
        server.close(async () => {
            await shutdown();
            logger.info('Shutdown complete');
            process.exit(0);
        });
        setTimeout(() => {
            logger.error('Forced shutdown after timeout');
            process.exit(1);
        }, 10000);
    };

    process.on('SIGTERM', () => gracefulShutdown('SIGTERM'));
    process.on('SIGINT', () => gracefulShutdown('SIGINT'));
}

start().catch(err => {
    const logger = require('./logger');
    logger.fatal({ err }, 'Failed to start PluginRCON API');
    process.exit(1);
});
