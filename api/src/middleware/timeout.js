function requestTimeout(ms) {
    return (req, res, next) => {
        const timer = setTimeout(() => {
            if (!res.headersSent) {
                res.status(408).json({ error: 'Request timeout' });
            }
        }, ms);

        res.on('finish', () => clearTimeout(timer));
        res.on('close', () => clearTimeout(timer));
        next();
    };
}

module.exports = requestTimeout;
