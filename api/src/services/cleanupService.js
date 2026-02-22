const { asyncStmts } = require('../database');
const config = require('../config');
const logger = require('../logger');

async function cleanupExpiredCommands() {
    try {
        const result = await asyncStmts.expireOld();
        if (result.changes > 0) {
            logger.info({ expired: result.changes }, 'Expired commands');
        }
    } catch (err) {
        logger.error({ err }, 'Error expiring commands');
    }
}

function getPreviousBusinessDay() {
    const now = new Date();
    const day = now.getDay();
    const daysBack = day === 0 ? 2 : day === 1 ? 3 : 1;
    const prev = new Date(now);
    prev.setDate(prev.getDate() - daysBack);
    prev.setHours(0, 0, 0, 0);
    return prev.toISOString().replace('T', ' ').substring(0, 19);
}

async function purgeOldCompletedCommands() {
    try {
        const cutoff = getPreviousBusinessDay();
        const result = await asyncStmts.purgeOldCompleted(cutoff);
        if (result.changes > 0) {
            logger.info({ purged: result.changes, cutoff }, 'Purged old commands');
        }
    } catch (err) {
        logger.error({ err }, 'Error purging old commands');
    }
}

function startCleanupJob() {
    cleanupExpiredCommands();
    purgeOldCompletedCommands();
    const intervalMs = config.CLEANUP_INTERVAL_MINUTES * 60 * 1000;
    setInterval(() => {
        cleanupExpiredCommands();
        purgeOldCompletedCommands();
    }, intervalMs);
    logger.info({ intervalMinutes: config.CLEANUP_INTERVAL_MINUTES }, 'Cleanup job started');
}

module.exports = { startCleanupJob };
