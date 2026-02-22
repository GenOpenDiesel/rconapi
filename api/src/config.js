require('dotenv').config();
const path = require('path');
const fs = require('fs');

const serversPath = path.join(__dirname, '..', 'servers.json');

let networks = {};
let serverLookup = {};

function buildServerLookup(networksData) {
    const lookup = {};
    for (const [networkName, network] of Object.entries(networksData)) {
        for (const serverName of network.servers) {
            lookup[serverName] = {
                network: networkName,
                token: network.token,
            };
        }
    }
    return lookup;
}

function loadServers() {
    if (!fs.existsSync(serversPath)) {
        const defaultConfig = {
            networks: {
                "default": {
                    token: "CHANGE_ME_default_network_token",
                    servers: ["lobby", "skyblock-1", "survival-1"]
                }
            }
        };
        fs.writeFileSync(serversPath, JSON.stringify(defaultConfig, null, 2));
        networks = defaultConfig.networks;
    } else {
        const raw = JSON.parse(fs.readFileSync(serversPath, 'utf-8'));
        networks = raw.networks || {};
    }
    serverLookup = buildServerLookup(networks);
    return networks;
}

loadServers();

function reloadServers() {
    loadServers();
    return networks;
}

function getNetworks() {
    return networks;
}

function getNetworkForServer(serverName) {
    const entry = serverLookup[serverName];
    return entry ? entry.network : null;
}

function getServerToken(serverName) {
    const entry = serverLookup[serverName];
    return entry ? entry.token : null;
}

function isValidServer(serverName) {
    return !!serverLookup[serverName];
}

function getServerNames() {
    return Object.keys(serverLookup);
}

function getServersByNetwork(networkName) {
    const network = networks[networkName];
    return network ? network.servers : [];
}

function getNetworkNames() {
    return Object.keys(networks);
}

module.exports = {
    PORT: parseInt(process.env.PORT || '3000', 10),
    MASTER_TOKEN: process.env.MASTER_TOKEN || 'change-me-to-a-secure-random-token',
    COMMAND_EXPIRY_HOURS: parseInt(process.env.COMMAND_EXPIRY_HOURS || '24', 10),
    CLEANUP_INTERVAL_MINUTES: parseInt(process.env.CLEANUP_INTERVAL_MINUTES || '5', 10),
    BROADCAST_STAGGER_SECONDS: parseFloat(process.env.BROADCAST_STAGGER_SECONDS || '2'),
    DB_HOST: process.env.DB_HOST || 'localhost',
    DB_PORT: parseInt(process.env.DB_PORT || '3306', 10),
    DB_USER: process.env.DB_USER || 'pluginrcon',
    DB_PASSWORD: process.env.DB_PASSWORD || '',
    DB_NAME: process.env.DB_NAME || 'pluginrcon',
    REQUEST_TIMEOUT_MS: parseInt(process.env.REQUEST_TIMEOUT_MS || '15000', 10),
    PENDING_CACHE_TTL_MS: parseInt(process.env.PENDING_CACHE_TTL_MS || '1000', 10),
    getNetworks,
    getNetworkForServer,
    getServerToken,
    isValidServer,
    getServerNames,
    getServersByNetwork,
    getNetworkNames,
    reloadServers,
};
