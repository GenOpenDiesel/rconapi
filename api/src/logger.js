const pino = require('pino');

const logger = pino({
    level: process.env.LOG_LEVEL_PINO || 'info',
    timestamp: pino.stdTimeFunctions.isoTime,
    formatters: {
        level(label) {
            return { level: label };
        },
    },
});

module.exports = logger;
