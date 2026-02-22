const express = require('express');
const { masterAuth, serverAuth } = require('../middleware/auth');
const config = require('../config');

const router = express.Router();

router.get('/network/:serverName', serverAuth, (req, res) => {
    const networkName = req.network;
    const servers = config.getServersByNetwork(networkName);

    res.json({
        success: true,
        network: networkName,
        currentServer: req.serverName,
        servers,
    });
});

router.get('/', masterAuth, (req, res) => {
    const networks = config.getNetworks();

    const result = Object.entries(networks).map(([networkName, network]) => ({
        network: networkName,
        servers: network.servers,
    }));

    res.json({ success: true, networks: result });
});

router.get('/:serverName/status', masterAuth, (req, res) => {
    const { serverName } = req.params;
    if (!config.isValidServer(serverName)) {
        return res.status(404).json({ error: `Unknown server: ${serverName}` });
    }

    res.json({
        success: true,
        server: {
            name: serverName,
            network: config.getNetworkForServer(serverName),
        },
    });
});

router.post('/reload', masterAuth, (req, res) => {
    config.reloadServers();
    const networks = config.getNetworks();
    const summary = Object.entries(networks).map(([name, net]) => ({
        network: name,
        serverCount: net.servers.length,
    }));
    res.json({ success: true, message: 'Server configuration reloaded', networks: summary });
});

module.exports = router;
