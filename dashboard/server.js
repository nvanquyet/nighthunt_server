const express = require('express');
const axios = require('axios');
const si = require('systeminformation');
const cors = require('cors');

const app = express();
const PORT = process.env.PORT || 3000;
const BACKEND_URL = process.env.BACKEND_URL || 'http://localhost:8080';

app.use(cors());
app.use(express.json());
app.use(express.static('public'));

// Get system stats
app.get('/api/system/stats', async (req, res) => {
    try {
        const [cpu, mem, processes, network] = await Promise.all([
            si.cpu(),
            si.mem(),
            si.processes(),
            si.networkStats()
        ]);

        res.json({
            cpu: {
                manufacturer: cpu.manufacturer,
                brand: cpu.brand,
                cores: cpu.cores,
                physicalCores: cpu.physicalCores,
                speed: cpu.speed,
                usage: await si.currentLoad()
            },
            memory: {
                total: mem.total,
                free: mem.free,
                used: mem.used,
                active: mem.active,
                available: mem.available,
                usage: (mem.used / mem.total * 100).toFixed(2)
            },
            processes: {
                all: processes.all,
                running: processes.running,
                sleeping: processes.sleeping,
                zombie: processes.zombie
            },
            network: network[0] || {}
        });
    } catch (error) {
        res.status(500).json({ error: error.message });
    }
});

// Get backend server stats
app.get('/api/backend/stats', async (req, res) => {
    try {
        const response = await axios.get(`${BACKEND_URL}/api/servers/stats`);
        // Backend returns ApiResponse format: { success, data, message }
        if (response.data && response.data.success && response.data.data) {
            res.json({
                success: true,
                data: response.data.data
            });
        } else {
            res.json({
                success: false,
                data: {
                    totalServers: 0,
                    activeServers: 0,
                    totalMatches: 0,
                    availableCapacity: 0
                }
            });
        }
    } catch (error) {
        console.error('Error fetching backend stats:', error.message);
        res.json({
            success: false,
            data: {
                totalServers: 0,
                activeServers: 0,
                totalMatches: 0,
                availableCapacity: 0
            }
        });
    }
});

// Get headless servers
app.get('/api/servers/list', async (req, res) => {
    try {
        // This would need to be implemented in backend
        res.json({ servers: [] });
    } catch (error) {
        res.status(500).json({ error: error.message });
    }
});

// Health check
app.get('/health', (req, res) => {
    res.json({ status: 'ok' });
});

app.listen(PORT, () => {
    console.log(`Dashboard server running on port ${PORT}`);
});

